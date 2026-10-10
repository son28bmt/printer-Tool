package com.example.xptool

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * CÃ¡c háº±ng sá»‘ cáº¥u hÃ¬nh máº¡ng cho Giai Ä‘oáº¡n A (khÃ´ng dÃ¹ng magic number).
 */
const val DEFAULT_TCP_TIMEOUT_MS = 800
const val DEFAULT_PING_TIMEOUT_MS = 500
const val SCAN_MAX_PARALLEL = 30

/**
 * Kiá»ƒm tra káº¿t ná»‘i TCP tá»›i IP:Port vá»›i timeout xÃ¡c Ä‘á»‹nh.
 * DÃ¹ng java.net.Socket().use { it.connect(...) }.
 * Tráº£ vá» true/false, khÃ´ng nÃ©m ngoáº¡i lá»‡ ra ngoÃ i.
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
 * Gá»­i ICMP Ping kiá»ƒm tra mÃ¡y chá»§ (InetAddress.getByName(ip).isReachable).
 * LÆ¯U Ã: ICMP ping cÃ³ thá»ƒ bá»‹ cháº·n bá»Ÿi tÆ°á»ng lá»­a/switch máº¡ng dÃ¹ mÃ¡y in váº«n Ä‘ang hoáº¡t Ä‘á»™ng.
 * Káº¿t quáº£ nÃ y chá»‰ dÃ¹ng lÃ m gá»£i Ã½, KHÃ”NG dÃ¹ng lÃ m Ä‘iá»u kiá»‡n quyáº¿t Ä‘á»‹nh duy nháº¥t.
 * LuÃ´n Æ°u tiÃªn káº¿t quáº£ cá»§a tcpConnectWithTimeout khi hai káº¿t quáº£ mÃ¢u thuáº«n.
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
 * QuÃ©t dáº£i IP tÃ¹y chá»‰nh theo cá»•ng chá»‰ Ä‘á»‹nh (máº·c Ä‘á»‹nh 9100).
 * - baseIp3Octets: vÃ­ dá»¥ "192.168.1." hoáº·c "192.168.1"
 * - startHost: host báº¯t Ä‘áº§u (máº·c Ä‘á»‹nh 1)
 * - endHost: host káº¿t thÃºc (máº·c Ä‘á»‹nh 254)
 * - onEachFound: gá»i NGAY khi phÃ¡t hiá»‡n 1 IP thÃ nh cÃ´ng Ä‘á»ƒ UI cáº­p nháº­t dáº§n dáº§n, khÃ´ng pháº£i Ä‘á»£i quÃ©t háº¿t
 * - onProgress: gá»i sau má»—i IP Ä‘Æ°á»£c kiá»ƒm tra Ä‘á»ƒ cáº­p nháº­t thanh tiáº¿n trÃ¬nh (done: sá»‘ Ä‘Ã£ xong, total: tá»•ng sá»‘)
 * - onComplete: gá»i khi toÃ n bá»™ dáº£i quÃ©t xong hoáº·c khi tiáº¿n trÃ¬nh káº¿t thÃºc
 * 
 * Tráº£ vá» má»™t hÃ m cancel() Ä‘á»ƒ dá»«ng giá»¯a chá»«ng. QuÃ¡ trÃ¬nh dá»«ng Ä‘Æ°á»£c kiá»ƒm tra tháº­t qua AtomicBoolean
 * vÃ  shutdownNow() trÃªn ExecutorService.
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
                // Executor cÃ³ thá»ƒ bá»‹ Ä‘Ã³ng khi há»§y giá»¯a chá»«ng
            }
        }
    }

    return cancelFunc
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