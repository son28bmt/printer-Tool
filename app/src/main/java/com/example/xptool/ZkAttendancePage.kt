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

internal fun MainActivity.pageZkAttendance() = showPage("Chấm công ZKTeco K20", false) { body ->
    val activity = this
    val prefs = getSharedPreferences("zk_attendance", Context.MODE_PRIVATE)
    val brands = listOf("ZKTeco", "Hikvision ISAPI", "Dahua CGI")
    val savedBrand = prefs.getInt("brand", 0).coerceIn(brands.indices)
    val brandPicker = Spinner(activity).apply {
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, brands)
        setSelection(savedBrand)
    }
    body.addView(brandPicker)
    val ipField = edit("IP máy chấm công", prefs.getString("ip", "") ?: "", InputType.TYPE_CLASS_TEXT)
    val defaultPort = if (savedBrand == 0) 4370 else 80
    val portField = edit("Cổng TCP", prefs.getInt("port_$savedBrand", defaultPort).toString(), InputType.TYPE_CLASS_NUMBER)
    val keyField = edit("Khóa giao tiếp (để 0 nếu máy không đặt)", "0", InputType.TYPE_CLASS_NUMBER)
    body.addView(label("Điện thoại và K20 cần ở cùng mạng Wi‑Fi/LAN. Chức năng này chỉ đọc bản ghi, không xóa dữ liệu trên máy.", 13f).apply {
        setTextColor(colorMuted)
    })
    body.addView(row(ipField to 2.2f, portField to 1f))
    body.addView(keyField)
    val userField = edit("Device username", prefs.getString("user", "") ?: "", InputType.TYPE_CLASS_TEXT)
    val passwordField = edit("Device password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    body.addView(userField)
    body.addView(passwordField)
    fun updateBrandFields(index: Int) {
        keyField.visibility = if (index == 0) View.VISIBLE else View.GONE
        userField.visibility = if (index == 0) View.GONE else View.VISIBLE
        passwordField.visibility = if (index == 0) View.GONE else View.VISIBLE
    }
    var activeBrand = savedBrand
    updateBrandFields(brandPicker.selectedItemPosition)
    brandPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (position != activeBrand) {
                prefs.edit().putInt("port_$activeBrand", portField.text.toString().toIntOrNull() ?: if (activeBrand == 0) 4370 else 80).apply()
                activeBrand = position
                val nextDefault = if (position == 0) 4370 else 80
                portField.setText(prefs.getInt("port_$position", nextDefault).toString())
                updateBrandFields(position)
            }
            prefs.edit().putInt("brand", position).apply()
        }
    }

    val scanStatus = label("Quét mạng Wi‑Fi hiện tại để tìm thiết bị đang mở cổng 4370.", 12f).apply {
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
        val brandDefaultPort = if (brandIndex == 0) 4370 else 80
        val scanPort = portField.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: brandDefaultPort
        val found = java.util.Collections.synchronizedList(mutableListOf<String>())
        discoveredDevices.removeAllViews()
        scanButton.text = "Dừng quét"
        scanStatus.text = "Đang dò ${prefix}1–254 qua TCP/UDP cổng $scanPort…"
        log("Đang dò máy ZKTeco trong mạng $prefix qua TCP/UDP cổng $scanPort.")
        cancelScan = ZkAttendanceReader.scanNetwork(
            prefix = prefix,
            port = scanPort,
            requireZkResponse = brandIndex == 0,
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
                        "Không phát hiện máy ở cổng $scanPort trong dải $prefix. Có thể K20 ở dải mạng khác hoặc Wi‑Fi đang chặn thiết bị giao tiếp với nhau."
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
        val brandDefaultPort = if (brandIndex == 0) 4370 else 80
        val port = portField.text.toString().trim().toIntOrNull() ?: brandDefaultPort
        val key = keyField.text.toString().trim().toIntOrNull() ?: 0
        if (ip.isBlank()) {
            log("Nhập IP của máy chấm công ZKTeco K20.")
            return@setOnClickListener
        }
        prefs.edit().putString("ip", ip).putInt("port_$brandIndex", port).apply()
        fetchButton.isEnabled = false
        fetchButton.text = "Đang kết nối…"
        log("Đang đọc bản ghi từ K20 tại $ip:$port.")
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
                    log("Đã đọc ${punches.size} bản ghi chấm công từ K20.")
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
            putExtra(Intent.EXTRA_SUBJECT, "Bản ghi chấm công ZKTeco K20")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(send, "Chia sẻ dữ liệu chấm công"))
    }
    body.addView(exportButton)
}
