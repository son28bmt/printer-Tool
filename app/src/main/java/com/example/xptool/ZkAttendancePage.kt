package com.example.xptool

import android.content.Intent
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import java.text.SimpleDateFormat
import java.util.Locale

internal fun MainActivity.pageZkAttendance() = showPage("Máy chấm công qua Wi-Fi/LAN", false) { body ->
    val activity = this
    val prefs = getSharedPreferences("zk_attendance", Context.MODE_PRIVATE)
    val brands = listOf("ZKTeco", "Hikvision ISAPI", "Dahua CGI", "Tự nhận diện hãng hỗ trợ", "Ronald Jack RJ3030 (ZK)")
    if (!prefs.getBoolean("port_defaults_corrected", false)) {
        prefs.edit()
            .putInt("port_0", if (prefs.getInt("port_0", 4370) == 80) 4370 else prefs.getInt("port_0", 4370))
            .putInt("port_1", if (prefs.getInt("port_1", 80) == 4370) 80 else prefs.getInt("port_1", 80))
            .putInt("port_2", if (prefs.getInt("port_2", 80) == 4370) 80 else prefs.getInt("port_2", 80))
            .putBoolean("port_defaults_corrected", true)
            .apply()
    }
    val savedBrand = (if (prefs.getBoolean("auto_detect_option_added", false)) prefs.getInt("brand", 3) else 3).coerceIn(brands.indices)
    prefs.edit().putBoolean("auto_detect_option_added", true).putInt("brand", savedBrand).apply()
    val brandPicker = Spinner(activity).apply {
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, brands)
        setSelection(savedBrand)
    }
    body.addView(brandPicker)
    val rj3030Hint = label("RJ3030 được thử bằng giao thức ZK trên cổng 4370; khả năng tương thích có thể tùy firmware từng máy.", 12f).apply {
        setTextColor(colorMuted)
        visibility = View.GONE
    }
    body.addView(rj3030Hint)
    val ipField = edit("IP máy chấm công", prefs.getString("ip", "") ?: "", InputType.TYPE_CLASS_TEXT)
    val defaultPort = if (savedBrand == 0 || savedBrand == 4) 4370 else 80
    val portField = edit("Cổng TCP", prefs.getInt("port_$savedBrand", defaultPort).toString(), InputType.TYPE_CLASS_NUMBER)
    val keyField = edit("Khóa giao tiếp (để 0 nếu máy không đặt)", "0", InputType.TYPE_CLASS_NUMBER)
    body.addView(label("Điện thoại và máy chấm công cần ở cùng mạng Wi‑Fi/LAN. Chức năng này chỉ đọc bản ghi, không xóa dữ liệu trên máy.", 13f).apply {
        setTextColor(colorMuted)
    })
    body.addView(row(ipField to 2.2f, portField to 1f))
    body.addView(keyField)
    val userField = edit("Device username", prefs.getString("user", "") ?: "", InputType.TYPE_CLASS_TEXT)
    val passwordField = edit("Device password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    body.addView(userField)
    body.addView(passwordField)
    fun updateBrandFields(index: Int) {
        keyField.visibility = if (index == 0 || index == 4) View.VISIBLE else View.GONE
        userField.visibility = if (index == 1 || index == 2) View.VISIBLE else View.GONE
        passwordField.visibility = if (index == 1 || index == 2) View.VISIBLE else View.GONE
        rj3030Hint.visibility = if (index == 4) View.VISIBLE else View.GONE
    }
    var activeBrand = savedBrand
    updateBrandFields(brandPicker.selectedItemPosition)
    brandPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (position != activeBrand) {
                prefs.edit().putInt("port_$activeBrand", portField.text.toString().toIntOrNull() ?: if (activeBrand == 0 || activeBrand == 4) 4370 else 80).apply()
                activeBrand = position
                val nextDefault = if (position == 0 || position == 4) 4370 else 80
                portField.setText(prefs.getInt("port_$position", nextDefault).toString())
                updateBrandFields(position)
            }
            prefs.edit().putInt("brand", position).apply()
        }
    }

    val scanStatus = label("Quét mạng Wi‑Fi hiện tại để dò máy chấm công.", 12f).apply {
        setTextColor(colorMuted)
    }
    body.addView(scanStatus)
    val discoveredDevices = column()
    body.addView(discoveredDevices)
    var cancelScan: (() -> Unit)? = null
    val scanButton = btn("Tự quét tìm máy chấm công") {}
    scanButton.setOnClickListener {
        val activeCancel = cancelScan
        if (activeCancel != null) {
            activeCancel()
            cancelScan = null
            scanButton.text = "Tự quét tìm máy chấm công"
            scanStatus.text = "Đã dừng quét."
            return@setOnClickListener
        }
        val network = phoneNet()
        if (network == null) {
            scanStatus.text = "Không đọc được mạng Wi‑Fi hiện tại. Kết nối Wi‑Fi rồi thử lại."
            return@setOnClickListener
        }
        val prefix = "${network.ip[0].toInt() and 0xFF}.${network.ip[1].toInt() and 0xFF}.${network.ip[2].toInt() and 0xFF}."
        val brandIndex = brandPicker.selectedItemPosition
        if (brandIndex == 3) {
            val detected = java.util.Collections.synchronizedList(mutableListOf<AttendanceDeviceCandidate>())
            discoveredDevices.removeAllViews()
            scanButton.text = "Dừng quét"
            scanStatus.text = "Đang dò giao thức ZKTeco, Hikvision và Dahua trong mạng $prefix…"
            cancelScan = ZkAttendanceReader.scanKnownProtocols(
                prefix = prefix,
                onFound = { candidate ->
                    detected.add(candidate)
                    activity.runOnUiThread {
                        scanStatus.text = "Tìm thấy ${detected.size} phản hồi giao thức. Chạm để chọn thiết bị."
                        discoveredDevices.addView(activity.btn("${brands[candidate.brandIndex]} · ${candidate.ip}") {
                            ipField.setText(candidate.ip)
                            brandPicker.setSelection(candidate.brandIndex)
                            portField.setText(candidate.port.toString())
                            activity.log("Selected ${brands[candidate.brandIndex]} at ${candidate.ip}:${candidate.port}.")
                        })
                    }
                },
                onProgress = { done, total ->
                    if (done == total || done % 32 == 0) activity.runOnUiThread {
                        scanStatus.text = "Đang tự nhận diện… $done/$total địa chỉ · ${detected.size} kết quả"
                    }
                },
                onComplete = { matches ->
                    activity.runOnUiThread {
                        cancelScan = null
                        scanButton.text = "Tự quét tìm máy chấm công"
                        scanStatus.text = if (matches.isEmpty()) {
                            "Không giao thức nào trong danh sách phản hồi trên mạng hiện tại."
                        } else {
                            "Tìm thấy ${matches.size} phản hồi giao thức. Chạm vào hãng/IP để chọn."
                        }
                    }
                }
            )
            return@setOnClickListener
        }
        val brandDefaultPort = if (brandIndex == 0 || brandIndex == 4) 4370 else 80
        val scanPort = portField.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: brandDefaultPort
        val httpProbePath = when (brandIndex) {
            1 -> "/ISAPI/System/deviceInfo"
            2 -> "/cgi-bin/magicBox.cgi?action=getSystemInfo"
            else -> null
        }
        val found = java.util.Collections.synchronizedList(mutableListOf<String>())
        discoveredDevices.removeAllViews()
        scanButton.text = "Dừng quét"
        scanStatus.text = "Đang dò ${prefix}1–254 qua TCP/UDP cổng $scanPort…"
        log("Đang dò ${brands[brandIndex]} trong mạng $prefix qua cổng $scanPort.")
        cancelScan = ZkAttendanceReader.scanNetwork(
            prefix = prefix,
            port = scanPort,
            requireZkResponse = brandIndex == 0 || brandIndex == 4,
            httpProbePath = httpProbePath,
            onFound = { foundIp ->
                found.add(foundIp)
                activity.runOnUiThread {
                    scanStatus.text = "Đã tìm thấy ${found.size} thiết bị. Chạm vào IP để chọn."
                    discoveredDevices.addView(activity.btn("Chọn $foundIp") {
                        ipField.setText(foundIp)
                        portField.setText(scanPort.toString())
                        activity.log("Đã chọn máy chấm công tại $foundIp:$scanPort.")
                    })
                }
            },
            onProgress = { done, total ->
                if (done == total || done % 32 == 0) {
                    activity.runOnUiThread { scanStatus.text = "Đang quét… $done/$total địa chỉ · tìm thấy ${found.size}" }
                }
            },
            onComplete = { ips ->
                activity.runOnUiThread {
                    cancelScan = null
                    scanButton.text = "Tự quét tìm máy chấm công"
                    scanStatus.text = if (ips.isEmpty()) {
                        "Không phát hiện thiết bị ${brands[brandIndex]} ở cổng $scanPort trong dải $prefix."
                    } else {
                        "Tìm thấy ${ips.size} thiết bị. Chạm vào IP cần dùng."
                    }
                    activity.log("Dò xong qua TCP/UDP cổng $scanPort: tìm thấy ${ips.size} thiết bị.")
                }
            }
        )
    }
    body.addView(scanButton)

    val results = column().apply {
        visibility = View.GONE
        setPadding(dp(12), dp(10), dp(12), dp(10))
        styleCard(this)
    }
    val summary = label("", 13f, true)
    results.addView(summary)
    val rows = column()
    results.addView(rows)
    body.addView(results)

    var lastRecords: List<AttendancePunch> = emptyList()
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val fetchButton = btn("Đọc bản ghi chấm công") {}
    fetchButton.setOnClickListener {
        val ip = ipField.text.toString().trim()
        val brandIndex = brandPicker.selectedItemPosition
        val brandDefaultPort = if (brandIndex == 0 || brandIndex == 4) 4370 else 80
        val port = portField.text.toString().trim().toIntOrNull() ?: brandDefaultPort
        val key = keyField.text.toString().trim().toIntOrNull() ?: 0
        if (ip.isBlank()) {
            log("Nhập IP của máy chấm công.")
            return@setOnClickListener
        }
        if (brandIndex == 3) {
            log("Chạy tự nhận diện rồi chọn hãng/thiết bị trước khi đọc dữ liệu.")
            return@setOnClickListener
        }
        prefs.edit().putString("ip", ip).putInt("port_$brandIndex", port).apply()
        fetchButton.isEnabled = false
        fetchButton.text = "Đang kết nối…"
        log("Đang đọc bản ghi từ ${brands[brandIndex]} tại $ip:$port.")
        val deviceUser = userField.text.toString()
        val devicePassword = passwordField.text.toString()
        prefs.edit().putString("user", deviceUser).apply()
        Thread {
            val outcome = runCatching {
                when (brandIndex) {
                    1 -> OtherAttendanceReaders.hikvision(ip, port, deviceUser, devicePassword)
                    2 -> OtherAttendanceReaders.dahua(ip, port, deviceUser, devicePassword)
                    else -> ZkAttendanceReader.read(ip, port, key)
                }
            }
            runOnUiThread {
                fetchButton.isEnabled = true
                fetchButton.text = "Đọc bản ghi chấm công"
                outcome.onSuccess { punches ->
                    lastRecords = punches
                    rows.removeAllViews()
                    summary.text = "Đọc được ${punches.size} bản ghi${if (punches.isNotEmpty()) " · hiển thị tối đa 200 dòng mới nhất" else ""}"
                    punches.takeLast(200).asReversed().forEach { punch ->
                        rows.addView(TextView(this).apply {
                            text = "${formatter.format(punch.time)}   •   ID ${punch.userId}   •   trạng thái ${punch.status}"
                            textSize = 12f
                            setTextColor(colorText)
                            setPadding(0, dp(6), 0, dp(6))
                        })
                    }
                    results.visibility = View.VISIBLE
                    log("Đã đọc ${punches.size} bản ghi chấm công từ ${brands[brandIndex]}.")
                }.onFailure { error ->
                    val message = error.message ?: error.javaClass.simpleName
                    log("Không đọc được dữ liệu chấm công: $message")
                    summary.text = message
                    rows.removeAllViews()
                    results.visibility = View.VISIBLE
                }
            }
        }.start()
    }
    body.addView(fetchButton)

    val exportButton = btn("Chia sẻ CSV") {}
    exportButton.setOnClickListener {
        if (lastRecords.isEmpty()) {
            log("Chưa có dữ liệu để xuất CSV.")
            return@setOnClickListener
        }
        val csv = buildString {
            append("user_id,timestamp,status,verification_method\n")
            lastRecords.forEach { punch ->
                append("\"${punch.userId.replace("\"", "\"\"")}\",${formatter.format(punch.time)},${punch.status},${punch.method}\n")
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Attendance records")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(send, "Chia sẻ dữ liệu chấm công"))
    }
    body.addView(exportButton)
}
