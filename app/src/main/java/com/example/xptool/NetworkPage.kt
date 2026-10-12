package com.example.xptool

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import net.posprinter.IConnectListener
import net.posprinter.POSConnect
import net.posprinter.POSConst
import net.posprinter.POSPrinter
import net.posprinter.TSPLPrinter
import net.posprinter.esc.PosUdpNet
import net.posprinter.model.UdpDevice
import net.posprinter.posprinterface.UdpCallback
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal fun MainActivity.pageNetwork(): Unit {
    val activity = this
    showPage("Tìm máy in LAN & Đổi IP", false) { b ->
        val pn0 = phoneNet()
        b.addView(
            label(
                if (pn0 != null) "Điện thoại: ${ipStr(pn0.ip)}, gateway ${ipStr(pn0.gw)}"
                else "Không đọc được WiFi điện thoại, hãy bật WiFi."
            )
        )

        val foundAdapter = ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item, mutableListOf<String>()
        )
        val spFound = Spinner(this)
        spFound.adapter = foundAdapter
        val tvWarn = label("")
        val panel = column()
        val result = column().apply { visibility = View.GONE }

        fun refreshWarn() {
            try {
                val d = found.getOrNull(spFound.selectedItemPosition) ?: return
                val pn = phoneNet()
                val dIp = safeIpStr(d)
                val dIpBytes = d.ipAddress
                tvWarn.text =
                    if (pn != null && dIpBytes != null && dIpBytes.size >= 4 && !sameSubnet(dIpBytes, pn.ip, pn.mask))
                        "⚠ Máy in ($dIp) khác dải điện thoại (${ipStr(pn.ip)}). Nên đổi IP máy in về cùng dải."
                    else "Máy in cùng dải với điện thoại."
            } catch (_: Exception) {
                tvWarn.text = ""
            }
        }

        spFound.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshWarn()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        b.addView(btn("Tìm máy in trong mạng (UDP Broadcast - Xprinter)") {
            found.clear()
            foundAdapter.clear()
            result.visibility = View.GONE
            log("Gửi broadcast UDP tìm máy in (cổng 9000)...")
            thread {
                try {
                    udp.searchNetDevice(udpCallback)
                } catch (e: Exception) {
                    log("Tìm lỗi: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            mainHandler.postDelayed({
                if (found.isEmpty()) {
                    log(
                        "Không thấy máy in Xprinter UDP. Bạn có thể dùng nút 'Quét dải IP LAN' bên dưới để quét Zywell/Epson."
                    )
                }
            }, 4000)
        })

        b.addView(btn("Quét dải IP mạng LAN cổng 9100 (Zywell, Epson, Xprinter...)") {
            scanLanPort9100 { ips ->
                try {
                    if (ips.isNotEmpty()) {
                        for (ip in ips) {
                            if (found.none { safeIpStr(it) == ip }) {
                                val ipB = ip4(ip) ?: byteArrayOf(0, 0, 0, 0)
                                val dev = createDummyUdpDevice(ip, ipB)
                                found.add(dev)
                                foundAdapter.add("$ip  [LAN Port 9100]")
                            }
                        }
                        result.visibility = View.VISIBLE
                        refreshWarn()
                        log("Đã quét thấy ${ips.size} máy in mở cổng 9100: ${ips.joinToString(", ")}. Chọn máy in trong danh sách bên dưới.")
                    } else {
                        log("Không tìm thấy địa chỉ IP nào mở cổng 9100 trong dải WiFi hiện tại.")
                    }
                } catch (e: Exception) {
                    log("Lỗi xử lý kết quả quét LAN: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        })
        b.addView(btn("➕ Thêm máy in theo IP (không cần quét - Đa dải mạng)") {
            ManagePrintersPage.showAddManualPrinterDialog(activity) {
                pageNetwork()
            }
        })

        // Bảng quét dải IP tùy chỉnh (cho mạng nhiều dải IP có định tuyến)
        val customScanPanel = column().apply {
            visibility = View.GONE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            activity.styleCard(this)
        }
        val prefixSuggested = pn0?.let { "${it.ip[0].toInt() and 0xFF}.${it.ip[1].toInt() and 0xFF}.${it.ip[2].toInt() and 0xFF}." } ?: "192.168.1."
        val etBaseOctets = edit("3 Octet đầu (vd: 192.168.1.)", prefixSuggested, IPTYPE)
        val etStartHost = edit("Từ Host (1-254)", "1", InputType.TYPE_CLASS_NUMBER)
        val etEndHost = edit("Đến Host (1-254)", "254", InputType.TYPE_CLASS_NUMBER)
        val etPort = edit("Cổng in TCP", "9100", InputType.TYPE_CLASS_NUMBER)
        val tvCustomProgress = label("Nhập dải mạng IP cần quét rồi bấm 'Bắt đầu quét'. Quá trình có thể dừng bất cứ lúc nào.")

        var cancelCustomScan: (() -> Unit)? = null
        var isCustomScanning = false
        val btnRunCustomScan = btn("▶ Bắt đầu quét dải IP") {}
        btnRunCustomScan.setOnClickListener {
            if (isCustomScanning) {
                cancelCustomScan?.invoke()
                isCustomScanning = false
                btnRunCustomScan.text = "▶ Bắt đầu quét dải IP"
                tvCustomProgress.text = "Đã dừng quét giữa chừng."
                log("Đã dừng tiến trình quét dải IP tùy chỉnh.")
            } else {
                val prefix = etBaseOctets.text.toString().trim()
                val sHost = etStartHost.text.toString().trim().toIntOrNull() ?: 1
                val eHost = etEndHost.text.toString().trim().toIntOrNull() ?: 254
                val targetPortNum = etPort.text.toString().trim().toIntOrNull() ?: 9100

                if (prefix.isEmpty()) {
                    log("Vui lòng nhập 3 octet đầu của dải IP (vd: 192.168.1.)")
                    return@setOnClickListener
                }

                isCustomScanning = true
                btnRunCustomScan.text = "⏹ Dừng quét"
                tvCustomProgress.text = "Đang quét song song dải $prefix$sHost ~ $prefix$eHost (cổng $targetPortNum)..."
                log("Bắt đầu quét song song dải IP: $prefix$sHost ~ $prefix$eHost cổng $targetPortNum...")

                cancelCustomScan = scanCustomRange(
                    baseIp3Octets = prefix,
                    startHost = sHost,
                    endHost = eHost,
                    port = targetPortNum,
                    timeoutMs = DEFAULT_TCP_TIMEOUT_MS,
                    maxParallel = SCAN_MAX_PARALLEL,
                    onEachFound = { foundIp ->
                        runOnUiThread {
                            if (found.none { safeIpStr(it) == foundIp }) {
                                val ipB = ip4(foundIp) ?: byteArrayOf(0, 0, 0, 0)
                                val dev = createDummyUdpDevice(foundIp, ipB)
                                found.add(dev)
                                foundAdapter.add("$foundIp  [Port $targetPortNum]")
                                result.visibility = View.VISIBLE
                                refreshWarn()
                            }
                            log("✅ Phát hiện máy in phản hồi cổng $targetPortNum tại: $foundIp")
                        }
                    },
                    onProgress = { done, total ->
                        runOnUiThread {
                            tvCustomProgress.text = "Đang quét: $done/$total IP..."
                        }
                    },
                    onComplete = { foundList ->
                        runOnUiThread {
                            isCustomScanning = false
                            btnRunCustomScan.text = "▶ Bắt đầu quét dải IP"
                            tvCustomProgress.text = "Quét hoàn tất! Tìm thấy ${foundList.size} máy in."
                            log("Quét hoàn tất dải $prefix: tìm thấy ${foundList.size} máy in.")
                        }
                    }
                )
            }
        }

        customScanPanel.addView(label("Quét dải IP tùy chỉnh (Hỗ trợ mạng đa dải có định tuyến):", 13f, true))
        val scanRangeManager = column()
        val scanRangeInput = EditText(this).apply {
            hint = "Ví dụ: 192.168.1."
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        fun refreshScanRangeManager() {
            scanRangeManager.removeAllViews()
            PrinterStore.loadScanRanges().forEach { range ->
                val removeButton = btn("Xóa") {
                    val updated = PrinterStore.loadScanRanges().apply { remove(range) }
                    PrinterStore.saveScanRanges(updated)
                    refreshScanRangeManager()
                }
                scanRangeManager.addView(row(label(range) to 1f, removeButton to 0.35f))
            }
        }
        scanRangeManager.addView(label("Quản lý danh sách dải quét", 14f, true))
        scanRangeManager.addView(label("Danh sách chỉ là ước lượng các dải thường gặp. Mạng của bạn có thể dùng dải khác; hãy thêm dải nếu biết chính xác."))
        scanRangeManager.addView(row(
            scanRangeInput to 1f,
            btn("Thêm dải") {
                val range = scanRangeInput.text.toString().trim()
                val valid = range.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.")) &&
                    range.dropLast(1).split('.').all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
                if (!valid) {
                    log("Dải không hợp lệ. Hãy nhập dạng x.x.x., ví dụ 192.168.1.")
                } else {
                    val updated = PrinterStore.loadScanRanges()
                    if (range !in updated) updated.add(range)
                    PrinterStore.saveScanRanges(updated)
                    scanRangeInput.text.clear()
                    refreshScanRangeManager()
                }
            } to 0.65f
        ))
        scanRangeManager.addView(btn("Khôi phục danh sách mặc định") {
            PrinterStore.saveScanRanges(DefaultScanRanges.BUILTIN_RANGES)
            refreshScanRangeManager()
        })
        refreshScanRangeManager()

        val quickScanProgress = label("")
        var isQuickScanning = false
        var cancelQuickScan: (() -> Unit)? = null
        var quickScanStartedAt = 0L
        val quickScanButton = btn("Quét nhanh các dải mạng phổ biến") {}
        quickScanButton.setOnClickListener {
            if (isQuickScanning) {
                cancelQuickScan?.invoke()
            } else {
                val ranges = PrinterStore.loadScanRanges()
                val targetPort = etPort.text.toString().trim().toIntOrNull() ?: 9100
                if (ranges.isEmpty()) {
                    log("Danh sách dải quét đang trống.")
                    return@setOnClickListener
                }
                isQuickScanning = true
                quickScanStartedAt = System.currentTimeMillis()
                quickScanButton.text = "Dừng quét nhanh"
                quickScanProgress.text = "Đang chuẩn bị quét ${ranges.size} dải..."
                cancelQuickScan = scanAllRangesSequentially(
                    ranges = ranges,
                    port = targetPort,
                    onEachFound = { foundIp ->
                        runOnUiThread {
                            if (found.none { safeIpStr(it) == foundIp }) {
                                val ipBytes = ip4(foundIp) ?: byteArrayOf(0, 0, 0, 0)
                                found.add(createDummyUdpDevice(foundIp, ipBytes))
                                foundAdapter.add("$foundIp  [Port $targetPort]")
                            }
                            result.visibility = View.VISIBLE
                            log("Tìm thấy máy in tại $foundIp")
                        }
                    },
                    onRangeProgress = { index, total, prefix ->
                        runOnUiThread { quickScanProgress.text = "Đang quét dải $index/$total: ${prefix}x..." }
                    },
                    onFinalComplete = { summary ->
                        runOnUiThread {
                            isQuickScanning = false
                            quickScanButton.text = "Quét nhanh các dải mạng phổ biến"
                            cancelQuickScan = null
                            val elapsedSeconds = (System.currentTimeMillis() - quickScanStartedAt) / 1000.0
                            quickScanProgress.text = "Đã quét ${summary.rangesScanned} dải (${summary.addressesChecked} địa chỉ), tìm thấy ${summary.printersFound} máy in. Thời gian: ${"%.1f".format(elapsedSeconds)} giây."
                        }
                    }
                )
            }
        }

        customScanPanel.addView(etBaseOctets)
        customScanPanel.addView(row(etStartHost to 1f, etEndHost to 1f, etPort to 1f))
        customScanPanel.addView(btnRunCustomScan)
        customScanPanel.addView(tvCustomProgress)

        b.addView(btn("🌐 Quét dải IP tùy chỉnh (Port 9100 - Đa dải mạng)") {
            customScanPanel.visibility = if (customScanPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        b.addView(customScanPanel)
        b.addView(quickScanButton)
        b.addView(quickScanProgress)
        b.addView(scanRangeManager)

        result.addView(label("Máy in tìm thấy (chọn để sử dụng):", 15f, true))
        result.addView(spFound)
        result.addView(tvWarn)
        result.addView(btn("Dùng máy in này cho kết nối LAN") {
            val d = found.getOrNull(spFound.selectedItemPosition)
            val selectedIp = safeIpStr(d).ifEmpty {
                spFound.selectedItem?.toString()?.split(" ")?.firstOrNull() ?: ""
            }
            if (selectedIp.isEmpty()) return@btn log("Chưa chọn máy in.")
            printerIp = selectedIp
            connectType = POSConnect.DEVICE_TYPE_ETHERNET
            savePrinter()
            log("Đã chọn máy in LAN đang dùng: $printerIp")
        })
        result.addView(btn("➕ Lưu máy in này vào danh sách Máy in của tôi") {
            val d = found.getOrNull(spFound.selectedItemPosition)
            val selectedIp = safeIpStr(d).ifEmpty {
                spFound.selectedItem?.toString()?.split(" ")?.firstOrNull() ?: ""
            }
            if (selectedIp.isEmpty()) return@btn log("Chưa chọn máy in.")
            val mac = safeMacStr(d)
            val defaultName = "Máy in $selectedIp" + (if (mac.isNotEmpty()) " [$mac]" else "")
            val etName = EditText(this).apply { setText(defaultName) }
            AlertDialog.Builder(this)
                .setTitle("Lưu vào Máy in của tôi")
                .setMessage("Nhập tên gợi nhớ cho máy in này (Ví dụ: Máy in Quầy 1):")
                .setView(etName)
                .setPositiveButton("Lưu") { _, _ ->
                    val name = etName.text.toString().trim().ifEmpty { defaultName }
                    val newSaved = SavedPrinter(
                        name = name,
                        mac = mac.takeIf { it.isNotEmpty() },
                        lastIp = selectedIp,
                        port = printerPort,
                        connType = POSConnect.DEVICE_TYPE_ETHERNET,
                        paperWidth = paperWidth
                    )
                    PrinterManager.addOrUpdatePrinter(this, newSaved)
                    PrinterManager.setActivePrinter(this, newSaved.id)
                    printerIp = selectedIp
                    savePrinter()
                    log("✔ Đã lưu '$name' vào danh sách Máy in của tôi.")
                }
                .setNegativeButton("Hủy", null)
                .show()
        })
        result.addView(label("Tùy chọn đổi IP máy in:", 15f, true))

        val rg = RadioGroup(this)
        val rbAuto = radio("Tự động: về cùng dải điện thoại")
        val rbManual = radio("Nhập tay IP / mask / gateway")
        val rbDhcp = radio("DHCP: router tự cấp IP")
        rg.addView(rbAuto)
        rg.addView(rbManual)
        rg.addView(rbDhcp)

        val autoInfo = label(
            if (pn0 != null)
                "Máy in sẽ nhận IP trống cùng dải ${ipStr(pn0.ip)}, mask ${ipStr(pn0.mask)}, gateway ${ipStr(pn0.gw)}."
            else "Cần kết nối WiFi điện thoại để tự động."
        )
        val manualPanel = column().apply { visibility = View.GONE }
        val etNewIp = edit("IP mới, vd 192.168.1.100", "", IPTYPE)
        val etNewMask = edit("Netmask", "255.255.255.0", IPTYPE)
        val etNewGw = edit("Gateway, vd 192.168.1.1", pn0?.let { ipStr(it.gw) } ?: "", IPTYPE)
        manualPanel.addView(etNewIp)
        manualPanel.addView(row(etNewMask to 1f, etNewGw to 1f))
        val dhcpInfo = label("Máy in tự xin IP từ router. Sau đó bấm 'Tìm máy in' để xem IP mới.")
        dhcpInfo.visibility = View.GONE

        rg.setOnCheckedChangeListener { _, id ->
            autoInfo.visibility = if (id == rbAuto.id) View.VISIBLE else View.GONE
            manualPanel.visibility = if (id == rbManual.id) View.VISIBLE else View.GONE
            dhcpInfo.visibility = if (id == rbDhcp.id) View.VISIBLE else View.GONE
        }
        rg.check(rbAuto.id)

        panel.addView(rg)
        panel.addView(autoInfo)
        panel.addView(manualPanel)
        panel.addView(dhcpInfo)
        panel.addView(btn("Áp dụng đổi IP") {
            val dev = found.getOrNull(spFound.selectedItemPosition)
                ?: return@btn log("Chưa chọn máy in.")
            when (rg.checkedRadioButtonId) {
                rbAuto.id -> {
                    val pn = phoneNet()
                        ?: return@btn log("Không đọc được mạng WiFi điện thoại.")
                    thread {
                        val free = findFreeIp(pn)
                        runOnUiThread {
                            val ipB = free?.let { ip4(it) }
                            if (ipB == null) {
                                log("Không tìm được IP trống, hãy chọn 'Nhập tay'.")
                            } else {
                                confirmChange(dev, ipB, pn.mask, pn.gw, false)
                            }
                        }
                    }
                }
                rbManual.id -> {
                    val ip = ip4(etNewIp.text.toString()) ?: return@btn log("IP mới không hợp lệ")
                    val mask = ip4(etNewMask.text.toString()) ?: return@btn log("Netmask không hợp lệ")
                    val gw = ip4(etNewGw.text.toString()) ?: return@btn log("Gateway không hợp lệ")
                    confirmChange(dev, ip, mask, gw, false)
                }
                rbDhcp.id -> confirmChange(dev, dev.ipAddress, dev.mask, dev.gateway, true)
                else -> log("Hãy chọn một cách đổi IP.")
            }
        })
        result.addView(panel)
        b.addView(result)

        onFoundUi = { d ->
            foundAdapter.add(foundLabel(d))
            result.visibility = View.VISIBLE
            refreshWarn()
        }
        if (found.isNotEmpty()) {
            found.forEach { foundAdapter.add(foundLabel(it)) }
            result.visibility = View.VISIBLE
        }
    }
    }


