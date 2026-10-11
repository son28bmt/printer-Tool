package com.example.xptool

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Các hằng số cấu hình mạng cho Giai đoạn A (không dùng magic number).
 */
const val DEFAULT_TCP_TIMEOUT_MS = 800
const val DEFAULT_PING_TIMEOUT_MS = 500
const val SCAN_MAX_PARALLEL = 30

/**
 * Kiểm tra kết nối TCP tới IP:Port với timeout xác định.
 * Dùng java.net.Socket().use { it.connect(...) }.
 * Trả về true/false, không ném ngoại lệ ra ngoài.
 */
fun tcpConnectWithTimeout(
    ip: String,
    port: Int = 9100,
    timeoutMs: Int = DEFAULT_TCP_TIMEOUT_MS
): Boolean {
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
        }
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Gửi ICMP Ping kiểm tra máy chủ (InetAddress.getByName(ip).isReachable).
 * LƯU Ý: ICMP ping có thể bị chặn bởi tường lửa/switch mạng dù máy in vẫn đang hoạt động.
 * Kết quả này chỉ dùng làm gợi ý, KHÔNG dùng làm điều kiện quyết định duy nhất.
 * Luôn ưu tiên kết quả của tcpConnectWithTimeout khi hai kết quả mâu thuẫn.
 */
fun pingHost(
    ip: String,
    timeoutMs: Int = DEFAULT_PING_TIMEOUT_MS
): Boolean {
    return try {
        InetAddress.getByName(ip).isReachable(timeoutMs)
    } catch (_: Exception) {
        false
    }
}

/**
 * Quét dải IP tùy chỉnh theo cổng chỉ định (mặc định 9100).
 * - baseIp3Octets: ví dụ "192.168.1." hoặc "192.168.1"
 * - startHost: host bắt đầu (mặc định 1)
 * - endHost: host kết thúc (mặc định 254)
 * - onEachFound: gọi NGAY khi phát hiện 1 IP thành công để UI cập nhật dần dần, không phải đợi quét hết
 * - onProgress: gọi sau mỗi IP được kiểm tra để cập nhật thanh tiến trình (done: số đã xong, total: tổng số)
 * - onComplete: gọi khi toàn bộ dải quét xong hoặc khi tiến trình kết thúc
 * 
 * Trả về một hàm cancel() để dừng giữa chừng. Quá trình dừng được kiểm tra thật qua AtomicBoolean
 * và shutdownNow() trên ExecutorService.
 */
fun scanCustomRange(
    baseIp3Octets: String,
    startHost: Int = 1,
    endHost: Int = 254,
    port: Int = 9100,
    timeoutMs: Int = DEFAULT_TCP_TIMEOUT_MS,
    maxParallel: Int = SCAN_MAX_PARALLEL,
    onEachFound: (String) -> Unit,
    onProgress: (done: Int, total: Int) -> Unit,
    onComplete: (List<String>) -> Unit
): () -> Unit {
    val cancelled = AtomicBoolean(false)
    val prefix = if (baseIp3Octets.endsWith(".")) baseIp3Octets else "$baseIp3Octets."
    val safeStart = startHost.coerceIn(1, 254)
    val safeEnd = endHost.coerceIn(safeStart, 254)
    val total = safeEnd - safeStart + 1

    val executor = Executors.newFixedThreadPool(maxParallel.coerceAtLeast(1))
    val foundList = Collections.synchronizedList(mutableListOf<String>())
    val doneCounter = AtomicInteger(0)

    val cancelFunc: () -> Unit = {
        if (cancelled.compareAndSet(false, true)) {
            try {
                executor.shutdownNow()
            } catch (_: Exception) {
            }
        }
    }

    kotlin.concurrent.thread {
        for (host in safeStart..safeEnd) {
            if (cancelled.get()) break
            val targetIp = "$prefix$host"
            try {
                executor.execute {
                    if (cancelled.get()) return@execute
                    val success = tcpConnectWithTimeout(targetIp, port, timeoutMs)
                    if (success && !cancelled.get()) {
                        foundList.add(targetIp)
                        onEachFound(targetIp)
                    }
                    val currentDone = doneCounter.incrementAndGet()
                    onProgress(currentDone, total)
                    if (currentDone >= total && !cancelled.get()) {
                        executor.shutdown()
                        onComplete(synchronized(foundList) { foundList.sorted() })
                    }
                }
            } catch (_: Exception) {
                // Executor có thể bị đóng khi hủy giữa chừng
            }
        }
    }

    return cancelFunc
}

data class MultiRangeScanSummary(
    val rangesScanned: Int,
    val addressesChecked: Int,
    val printersFound: Int
)

fun scanAllRangesSequentially(
    ranges: List<String>,
    port: Int = 9100,
    onEachFound: (String) -> Unit,
    onRangeProgress: (rangeIndex: Int, totalRanges: Int, rangePrefix: String) -> Unit,
    onFinalComplete: (MultiRangeScanSummary) -> Unit
): () -> Unit {
    val validRanges = ranges.map { it.trim() }
        .filter { range ->
            range.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.")) &&
                range.dropLast(1).split('.').all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
        }
        .distinct()
    val cancelled = AtomicBoolean(false)
    val completed = AtomicBoolean(false)
    val rangesStarted = AtomicInteger(0)
    val addressesChecked = AtomicInteger(0)
    val found = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val activeCancel = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

    fun finish() {
        if (completed.compareAndSet(false, true)) {
            onFinalComplete(MultiRangeScanSummary(rangesStarted.get(), addressesChecked.get(), found.size))
        }
    }

    fun scanNext(index: Int) {
        if (cancelled.get() || index >= validRanges.size) {
            finish()
            return
        }
        val prefix = validRanges[index]
        rangesStarted.incrementAndGet()
        onRangeProgress(index + 1, validRanges.size, prefix)
        activeCancel.set(
            scanCustomRange(
                baseIp3Octets = prefix,
                startHost = 1,
                endHost = 254,
                port = port,
                timeoutMs = DEFAULT_TCP_TIMEOUT_MS,
                maxParallel = SCAN_MAX_PARALLEL,
                onEachFound = { ip -> if (found.add(ip)) onEachFound(ip) },
                onProgress = { _, _ -> addressesChecked.incrementAndGet() },
                onComplete = { if (cancelled.get()) finish() else scanNext(index + 1) }
            )
        )
        if (cancelled.get()) activeCancel.get()?.invoke()
    }

    if (validRanges.isEmpty()) finish() else scanNext(0)
    return {
        if (cancelled.compareAndSet(false, true)) {
            activeCancel.get()?.invoke()
            finish()
        }
    }
}

object NetworkUtil {
    fun tcpConnectWithTimeout(ip: String, port: Int = 9100, timeoutMs: Int = DEFAULT_TCP_TIMEOUT_MS) =
        com.example.xptool.tcpConnectWithTimeout(ip, port, timeoutMs)

    fun pingHost(ip: String, timeoutMs: Int = DEFAULT_PING_TIMEOUT_MS) =
        com.example.xptool.pingHost(ip, timeoutMs)

    fun scanCustomRange(
        baseIp3Octets: String,
        startHost: Int = 1,
        endHost: Int = 254,
        port: Int = 9100,
        timeoutMs: Int = DEFAULT_TCP_TIMEOUT_MS,
        maxParallel: Int = SCAN_MAX_PARALLEL,
        onEachFound: (String) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
        onComplete: (List<String>) -> Unit
    ) = com.example.xptool.scanCustomRange(
        baseIp3Octets, startHost, endHost, port, timeoutMs, maxParallel,
        onEachFound, onProgress, onComplete
    )
}
