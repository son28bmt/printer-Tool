package com.example.xptool

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CÃ¡c háº±ng sá»‘ cáº¥u hÃ¬nh hÃ ng Ä‘á»£i in vÃ  sá»‘ láº§n thá»­ láº¡i (khÃ´ng dÃ¹ng magic number).
 */
const val DEFAULT_MAX_RETRY = 2
val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(1000L, 3000L)

/**
 * HÃ ng Ä‘á»£i lá»‡nh in theo tá»«ng mÃ¡y in (Giai Ä‘oáº¡n B).
 * - Má»—i mÃ¡y in (printerKey) cÃ³ má»™t hÃ ng Ä‘á»£i riÃªng, cháº¡y tuáº§n tá»± tá»«ng lá»‡nh (FIFO).
 * - CÃ¡c mÃ¡y in khÃ¡c nhau cháº¡y song song trÃªn cÃ¡c luá»“ng worker Ä‘á»™c láº­p.
 * - CÆ¡ cháº¿ tá»± Ä‘á»™ng thá»­ láº¡i (Retry) vá»›i Ä‘á»™ trá»… tÃ¹y chá»‰nh khi gáº·p lá»—i máº¡ng/rá»›t gÃ³i.
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
     * ThÃªm lá»‡nh in vÃ o hÃ ng Ä‘á»£i cá»§a mÃ¡y in tÆ°Æ¡ng á»©ng.
     * @param printerKey KhÃ³a Ä‘á»‹nh danh mÃ¡y in (MAC náº¿u cÃ³, hoáº·c IP:Port)
     * @param job HÃ m thá»±c thi lá»‡nh in (tráº£ vá» true = thÃ nh cÃ´ng, false = tháº¥t báº¡i)
     * @param maxRetry Sá»‘ láº§n thá»­ láº¡i tá»‘i Ä‘a (máº·c Ä‘á»‹nh 2)
     * @param retryDelaysMs Danh sÃ¡ch khoáº£ng chá» giá»¯a cÃ¡c láº§n thá»­ láº¡i (mili-giÃ¢y, máº·c Ä‘á»‹nh 1000ms, 3000ms)
     * @param onResult Callback tráº£ káº¿t quáº£ (thÃ nh cÃ´ng hay khÃ´ng, tá»•ng sá»‘ láº§n Ä‘Ã£ thá»­)
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

        // Báº­t worker thread náº¿u chÆ°a cÃ³ worker Ä‘ang xá»­ lÃ½ cho printerKey nÃ y
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
                        // Double-check phÃ²ng trÆ°á»ng há»£p cÃ³ item má»›i vá»«a Ä‘Æ°á»£c enqueue ngay lÃºc ngáº¯t cá»
                        if (!queue.isEmpty() && isRunning.compareAndSet(false, true)) {
                            continue
                        }
                        break
                    }

                    executeItem(item)
                }
            } catch (_: Exception) {
                // Báº¯t má»i ngoáº¡i lá»‡ cáº¥p cao phÃ²ng crash app
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
                // Ngoáº¡i lá»‡ bÃªn trong job coi nhÆ° tháº¥t báº¡i cho láº§n thá»­ nÃ y
                success = false
            }

            if (success) {
                break
            }

            // Náº¿u tháº¥t báº¡i vÃ  váº«n cÃ²n láº§n thá»­ láº¡i
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
     * Láº¥y sá»‘ lÆ°á»£ng lá»‡nh in Ä‘ang chá» trong hÃ ng Ä‘á»£i cá»§a mÃ¡y in.
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