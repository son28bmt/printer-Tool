package com.example.xptool

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Các hằng số cấu hình hàng đợi in và số lần thử lại (không dùng magic number).
 */
const val DEFAULT_MAX_RETRY = 2
val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(1000L, 3000L)

/**
 * Hàng đợi lệnh in theo từng máy in (Giai đoạn B).
 * - Mỗi máy in (printerKey) có một hàng đợi riêng, chạy tuần tự từng lệnh (FIFO).
 * - Các máy in khác nhau chạy song song trên các luồng worker độc lập.
 * - Cơ chế tự động thử lại (Retry) với độ trễ tùy chỉnh khi gặp lỗi mạng/rớt gói.
 */
class PrintQueue {

    private data class QueueItem(
        val job: () -> Boolean,
        val maxRetry: Int,
        val retryDelaysMs: List<Long>,
        val onResult: (success: Boolean, attempts: Int) -> Unit
    )

    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<QueueItem>>()
    private val activeWorkers = ConcurrentHashMap<String, AtomicBoolean>()

    /**
     * Thêm lệnh in vào hàng đợi của máy in tương ứng.
     * @param printerKey Khóa định danh máy in (MAC nếu có, hoặc IP:Port)
     * @param job Hàm thực thi lệnh in (trả về true = thành công, false = thất bại)
     * @param maxRetry Số lần thử lại tối đa (mặc định 2)
     * @param retryDelaysMs Danh sách khoảng chờ giữa các lần thử lại (mili-giây, mặc định 1000ms, 3000ms)
     * @param onResult Callback trả kết quả (thành công hay không, tổng số lần đã thử)
     */
    fun enqueue(
        printerKey: String,
        job: () -> Boolean,
        maxRetry: Int = DEFAULT_MAX_RETRY,
        retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
        onResult: (success: Boolean, attempts: Int) -> Unit
    ) {
        val queue = queues.computeIfAbsent(printerKey) { ConcurrentLinkedQueue() }
        queue.add(QueueItem(job, maxRetry, retryDelaysMs, onResult))

        val isRunning = activeWorkers.computeIfAbsent(printerKey) { AtomicBoolean(false) }

        // Bật worker thread nếu chưa có worker đang xử lý cho printerKey này
        if (isRunning.compareAndSet(false, true)) {
            startWorkerThread(printerKey, queue, isRunning)
        }
    }

    private fun startWorkerThread(
        printerKey: String,
        queue: ConcurrentLinkedQueue<QueueItem>,
        isRunning: AtomicBoolean
    ) {
        kotlin.concurrent.thread(name = "PrintQueue-$printerKey") {
            try {
                while (true) {
                    val item = queue.poll()
                    if (item == null) {
                        isRunning.set(false)
                        // Double-check phòng trường hợp có item mới vừa được enqueue ngay lúc ngắt cờ
                        if (!queue.isEmpty() && isRunning.compareAndSet(false, true)) {
                            continue
                        }
                        break
                    }

                    executeItem(item)
                }
            } catch (_: Exception) {
                // Bắt mọi ngoại lệ cấp cao phòng crash app
            } finally {
                isRunning.set(false)
            }
        }
    }

    private fun executeItem(item: QueueItem) {
        var attempts = 0
        var success = false
        val totalAllowedAttempts = 1 + item.maxRetry.coerceAtLeast(0)

        while (attempts < totalAllowedAttempts) {
            attempts++
            try {
                success = item.job()
            } catch (_: Exception) {
                // Ngoại lệ bên trong job coi như thất bại cho lần thử này
                success = false
            }

            if (success) {
                break
            }

            // Nếu thất bại và vẫn còn lần thử lại
            if (attempts < totalAllowedAttempts) {
                val delayIdx = attempts - 1
                val delayMs = item.retryDelaysMs.getOrNull(delayIdx)
                    ?: item.retryDelaysMs.lastOrNull()
                    ?: 1000L
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        try {
            item.onResult(success, attempts)
        } catch (_: Exception) {
        }
    }

    /**
     * Lấy số lượng lệnh in đang chờ trong hàng đợi của máy in.
     */
    fun getPendingCount(printerKey: String): Int {
        return queues[printerKey]?.size ?: 0
    }

    companion object {
        val default = PrintQueue()

        fun enqueue(
            printerKey: String,
            job: () -> Boolean,
            maxRetry: Int = DEFAULT_MAX_RETRY,
            retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
            onResult: (success: Boolean, attempts: Int) -> Unit
        ) = default.enqueue(printerKey, job, maxRetry, retryDelaysMs, onResult)

        fun getPendingCount(printerKey: String): Int = default.getPendingCount(printerKey)
    }
}