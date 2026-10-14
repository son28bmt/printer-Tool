package com.example.xptool

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal data class MacRangeHit(val ip: String, val mac: String)

internal data class MacRangeScanSummary(
    val rangesScanned: Int,
    val addressesChecked: Int,
    val portCandidates: Int
)

/** Test-only scan: reuse the existing multi-range TCP scan, then ask SNMP for each candidate's interface MAC. */
internal object MultiRangeMacDiscovery {
    fun scan(
        ranges: List<String>,
        community: String,
        onProgress: (String) -> Unit,
        onComplete: (MacRangeScanSummary, List<MacRangeHit>) -> Unit
    ): () -> Unit {
        val cancelled = AtomicBoolean(false)
        val cancelIpScan = AtomicReference<(() -> Unit)?>(null)
        val candidates = Collections.synchronizedSet(mutableSetOf<String>())

        cancelIpScan.set(
            scanAllRangesSequentially(
                ranges = ranges,
                port = 9100,
                onEachFound = { ip ->
                    candidates.add(ip)
                    onProgress("Tìm thấy ${candidates.size} IP mở cổng 9100; đang tiếp tục quét.")
                },
                onRangeProgress = { index, total, prefix ->
                    onProgress("Quét dải $index/$total: ${prefix}x…")
                },
                onFinalComplete = { scanSummary ->
                    if (!cancelled.get()) {
                        thread(name = "snmp-mac-probe") {
                            val hits = Collections.synchronizedList(mutableListOf<MacRangeHit>())
                            val ipList = synchronized(candidates) { candidates.toList().sorted() }
                            ipList.forEachIndexed { index, ip ->
                                if (cancelled.get()) return@thread
                                onProgress("Đọc MAC bằng SNMP: ${index + 1}/${ipList.size} · $ip")
                                SnmpMacProbe.readMacAddresses(ip, community).forEach { mac ->
                                    hits.add(MacRangeHit(ip, mac))
                                }
                            }
                            if (!cancelled.get()) {
                                onComplete(
                                    MacRangeScanSummary(
                                        rangesScanned = scanSummary.rangesScanned,
                                        addressesChecked = scanSummary.addressesChecked,
                                        portCandidates = ipList.size
                                    ),
                                    synchronized(hits) { hits.toList() }
                                )
                            }
                        }
                    }
                }
            )
        )

        return {
            if (cancelled.compareAndSet(false, true)) {
                cancelIpScan.get()?.invoke()
            }
        }
    }
}
