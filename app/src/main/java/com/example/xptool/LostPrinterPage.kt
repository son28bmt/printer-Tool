package com.example.xptool

import android.app.AlertDialog
import net.posprinter.POSConnect

/** Test-only multi-range SNMP discovery; saved IPs change only after an exact MAC match and confirmation. */
internal fun MainActivity.pageLostPrinter() {
    val activity = this
    showPage("Tìm máy in mất tích", false) { body ->
    body.addView(label("Quét các dải IP đã cấu hình bằng TCP cổng 9100. Khi thấy ứng viên, hãy xác nhận đúng máy trước khi cập nhật IP đã lưu.", 13f))
    body.addView(label("Bộ quét nhận diện IP có cổng máy in phản hồi, không lấy được MAC qua mạng định tuyến. Vì vậy app không tự kết luận ứng viên nào là máy in cần tìm.", 12f).apply {
        setTextColor(colorMuted)
    })

    val recoverable = PrinterManager.savedPrinters.filter {
        it.connectType == POSConnect.DEVICE_TYPE_ETHERNET
    }

    body.addView(label("Thử lấy MAC qua SNMP", 15f, true))
    body.addView(label("Chỉ đọc SNMP v1 trên UDP/161 sau khi bộ quét nhiều dải tìm thấy IP mở TCP/9100. Máy in cần bật SNMP và cho phép community đã nhập. Community truyền không mã hóa; chỉ dùng trên mạng tin cậy.", 12f).apply {
        setTextColor(colorMuted)
    })
    val communityField = edit("SNMP read-only community", "public")
    body.addView(communityField)
    val macScanStatus = label("")
    val macScanResults = column()
    val macScanButton = btn("Thử dò MAC đa dải (SNMP)") {}
    var isMacScanRunning = false
    var cancelMacScan: (() -> Unit)? = null

    macScanButton.setOnClickListener {
        if (isMacScanRunning) {
            isMacScanRunning = false
            cancelMacScan?.invoke()
            cancelMacScan = null
            macScanButton.isEnabled = true
            macScanButton.text = "Thử dò MAC đa dải (SNMP)"
            macScanStatus.text = "Đã yêu cầu dừng quét."
            return@setOnClickListener
        }
        val community = communityField.text.toString().trim()
        if (community.isEmpty()) {
            macScanStatus.text = "Nhập SNMP community đã cấu hình trên máy in."
            return@setOnClickListener
        }
        val ranges = PrinterStore.loadScanRanges()
        if (ranges.isEmpty()) {
            macScanStatus.text = "Chưa có dải IP để quét. Hãy cấu hình trong mục Tìm máy in LAN & đổi IP."
            return@setOnClickListener
        }

        isMacScanRunning = true
        macScanResults.removeAllViews()
        macScanButton.text = "Dừng dò MAC"
        macScanStatus.text = "Quét ${ranges.size} dải IP, sau đó hỏi SNMP từng thiết bị mở cổng 9100…"
        cancelMacScan = MultiRangeMacDiscovery.scan(
            ranges = ranges,
            community = community,
            onProgress = { message -> runOnUiThread { if (isMacScanRunning) macScanStatus.text = message } },
            onComplete = { summary, hits ->
                runOnUiThread {
                    if (!isMacScanRunning) return@runOnUiThread
                    isMacScanRunning = false
                    cancelMacScan = null
                    macScanButton.text = "Thử dò MAC đa dải (SNMP)"
                    macScanStatus.text = "Đã quét ${summary.rangesScanned} dải, ${summary.addressesChecked} IP; ${summary.portCandidates} ứng viên cổng 9100, đọc được ${hits.size} MAC qua SNMP."

                    fun normalizeMac(value: String?) = value.orEmpty().filter { it.isLetterOrDigit() }.uppercase()
                    if (hits.isEmpty()) {
                        macScanResults.addView(label("Không đọc được MAC. Có thể SNMP chưa bật, community không đúng, hoặc máy in không hỗ trợ IF-MIB."))
                    }
                    hits.forEach { hit ->
                        val matchedPrinter = recoverable.firstOrNull { normalizeMac(it.mac) == normalizeMac(hit.mac) }
                        macScanResults.addView(label("${hit.ip} · ${hit.mac}" + (matchedPrinter?.let { " · Khớp ${it.name}" } ?: " · Chưa khớp máy in đã lưu")))
                        if (matchedPrinter != null) {
                            macScanResults.addView(btn("Cập nhật ${matchedPrinter.name} về ${hit.ip}") {
                                AlertDialog.Builder(activity)
                                    .setTitle("Xác nhận máy in khớp MAC")
                                    .setMessage("MAC ${hit.mac} khớp với ${matchedPrinter.name}. Cập nhật IP đã lưu thành ${hit.ip}?")
                                    .setPositiveButton("Cập nhật IP") { _, _ ->
                                        matchedPrinter.lastIp = hit.ip
                                        matchedPrinter.lastStatus = "online"
                                        matchedPrinter.lastSeenEpochMs = System.currentTimeMillis()
                                        PrinterStore.saveOrUpdate(matchedPrinter)
                                        macScanStatus.text = "Đã cập nhật ${matchedPrinter.name} thành ${hit.ip} theo MAC khớp."
                                        log("Đã tìm ${matchedPrinter.name} theo MAC ${hit.mac}; IP mới $hit.ip.")
                                    }
                                    .setNegativeButton("Hủy", null)
                                    .show()
                            })
                        }
                    }
                }
            }
        )
    }
    body.addView(macScanButton)
    body.addView(macScanStatus)
    body.addView(macScanResults)

    if (recoverable.isEmpty()) {
        body.addView(label("Chưa có máy in LAN/Wi-Fi nào trong danh sách đã lưu."))
        return@showPage
    }

    }
}
