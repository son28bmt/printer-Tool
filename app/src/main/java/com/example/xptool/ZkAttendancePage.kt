package com.example.xptool

import android.content.Intent
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale

internal fun MainActivity.pageZkAttendance() = showPage("Chấm công ZKTeco K20", false) { body ->
    val prefs = getSharedPreferences("zk_attendance", Context.MODE_PRIVATE)
    val ipField = edit("IP máy chấm công", prefs.getString("ip", "") ?: "", InputType.TYPE_CLASS_TEXT)
    val portField = edit("Cổng TCP", prefs.getInt("port", 4370).toString(), InputType.TYPE_CLASS_NUMBER)
    val keyField = edit("Khóa giao tiếp (để 0 nếu máy không đặt)", "0", InputType.TYPE_CLASS_NUMBER)
    body.addView(label("Điện thoại và K20 cần ở cùng mạng Wi‑Fi/LAN. Chức năng này chỉ đọc bản ghi, không xóa dữ liệu trên máy.", 13f).apply {
        setTextColor(colorMuted)
    })
    body.addView(row(ipField to 2.2f, portField to 1f))
    body.addView(keyField)

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
        val port = portField.text.toString().trim().toIntOrNull() ?: 4370
        val key = keyField.text.toString().trim().toIntOrNull() ?: 0
        if (ip.isBlank()) {
            log("Nhập IP của máy chấm công ZKTeco K20.")
            return@setOnClickListener
        }
        prefs.edit().putString("ip", ip).putInt("port", port).apply()
        fetchButton.isEnabled = false
        fetchButton.text = "Đang kết nối…"
        log("Đang đọc bản ghi từ K20 tại $ip:$port.")
        Thread {
            val outcome = runCatching { ZkAttendanceReader.read(ip, port, key) }
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
