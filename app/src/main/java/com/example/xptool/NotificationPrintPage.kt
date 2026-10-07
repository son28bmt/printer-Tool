package com.example.xptool

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

object NotificationPrintPage {

    fun buildView(
        activity: MainActivity,
        container: LinearLayout,
        log: (String) -> Unit
    ) {
        val prefs = activity.getSharedPreferences(OrderNotificationListenerService.PREF_NAME, Context.MODE_PRIVATE)

        val isListenerPermissionGranted = isNotificationServiceEnabled(activity)

        // Banner cảnh báo quyền
        val tvPermStatus = activity.label(
            if (isListenerPermissionGranted) "🟢 Quyền đọc thông báo: ĐÃ CẤP"
            else "🔴 Quyền đọc thông báo: CHƯA CẤP (Cần cấp quyền trong Cài đặt hệ thống)",
            14f, true
        ).apply {
            setTextColor(if (isListenerPermissionGranted) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        }

        val btnOpenListenerSettings = activity.btn("🔑 Cấp quyền Đọc thông báo (Notification Listener)") {
            try {
                activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                log("Đã mở Cài đặt đọc thông báo. Hãy tìm 'XP Tool' và BẬT công tắc.")
            } catch (e: Exception) {
                log("Không thể mở Cài đặt đọc thông báo: ${e.message}")
            }
        }

        container.addView(tvPermStatus)
        container.addView(btnOpenListenerSettings)
        container.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(8))
        })

        // Form cấu hình
        val cbEnable = CheckBox(activity).apply {
            text = "Bật Tự động in khi nhận thông báo"
            isChecked = prefs.getBoolean(OrderNotificationListenerService.KEY_ENABLED, false)
        }

        val etPkgFilter = activity.edit(
            "Tên gói App cần đọc (VD: com.grabtaxi, kgvn, zalopay... Để rỗng = đọc tất cả)",
            prefs.getString(OrderNotificationListenerService.KEY_PKG_FILTER, "") ?: ""
        )

        val etKeywordFilter = activity.edit(
            "Từ khóa lọc nội dung (VD: 'Đơn hàng mới', 'Chuyển khoản', để rỗng = không lọc)",
            prefs.getString(OrderNotificationListenerService.KEY_KEYWORD_FILTER, "") ?: ""
        )

        val cbPrintTitle = CheckBox(activity).apply {
            text = "In Tiêu đề thông báo"
            isChecked = prefs.getBoolean(OrderNotificationListenerService.KEY_PRINT_TITLE, true)
        }

        val cbPrintContent = CheckBox(activity).apply {
            text = "In Nội dung chi tiết thông báo"
            isChecked = prefs.getBoolean(OrderNotificationListenerService.KEY_PRINT_CONTENT, true)
        }

        val cbCutPaper = CheckBox(activity).apply {
            text = "Cắt giấy sau khi in"
            isChecked = prefs.getBoolean(OrderNotificationListenerService.KEY_CUT_PAPER, true)
        }

        val btnSave = activity.btn("💾 Lưu Cài đặt Tự động In") {
            prefs.edit()
                .putBoolean(OrderNotificationListenerService.KEY_ENABLED, cbEnable.isChecked)
                .putString(OrderNotificationListenerService.KEY_PKG_FILTER, etPkgFilter.text.toString().trim())
                .putString(OrderNotificationListenerService.KEY_KEYWORD_FILTER, etKeywordFilter.text.toString().trim())
                .putBoolean(OrderNotificationListenerService.KEY_PRINT_TITLE, cbPrintTitle.isChecked)
                .putBoolean(OrderNotificationListenerService.KEY_PRINT_CONTENT, cbPrintContent.isChecked)
                .putBoolean(OrderNotificationListenerService.KEY_CUT_PAPER, cbCutPaper.isChecked)
                .apply()

            log("✔ Đã lưu cấu hình Tự động in từ thông báo (Bật=${cbEnable.isChecked}).")
        }

        container.addView(activity.label("Cấu hình Tự động in từ thông báo App khác:", 14f, true))
        container.addView(cbEnable)
        container.addView(etPkgFilter)
        container.addView(etKeywordFilter)
        container.addView(cbPrintTitle)
        container.addView(cbPrintContent)
        container.addView(cbCutPaper)
        container.addView(btnSave)

        container.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(10))
        })

        // Nút Giả lập In thử
        val btnTestSimulate = activity.btn("🧪 Giả lập In thử 1 Thông báo Đơn hàng") {
            log("Đang giả lập in thông báo mẫu: GrabFood #8899...")
            thread {
                val demoTitle = "Đơn hàng GrabFood #8899"
                val demoText = "Khách hàng: Nguyễn Văn A. Tổng tiền: 125.000d. Giao đến: 123 Lê Lợi."
                val cut = cbCutPaper.isChecked

                val b = java.io.ByteArrayOutputStream()
                b.write(byteArrayOf(0x1B, 0x40))
                b.write("=== THÔNG BÁO MẪU GIẢ LẬP ===\n".toByteArray(Charsets.US_ASCII))
                b.write("App: com.grabtaxi.passenger\n".toByteArray(Charsets.US_ASCII))
                b.write("TIEU DE: $demoTitle\n".toByteArray(Charsets.US_ASCII))
                b.write("NOI DUNG: $demoText\n".toByteArray(Charsets.US_ASCII))
                b.write("--------------------------------\n\n\n".toByteArray(Charsets.US_ASCII))
                if (cut) b.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))

                activity.sendPrintData("Giả lập In Thông báo", b.toByteArray())
            }
        }
        container.addView(btnTestSimulate)

        // Danh sách Lịch sử đọc thông báo
        val cardLog = activity.column().apply {
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        val tvLogTitle = activity.label("Lịch sử nhận thông báo gần đây (30 bản ghi):", 13f, true)
        val tvLogContent = TextView(activity).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val updateLogsText = {
            val sb = StringBuilder()
            synchronized(OrderNotificationListenerService.recentLogs) {
                if (OrderNotificationListenerService.recentLogs.isEmpty()) {
                    sb.append("Chưa có thông báo nào được nhận.")
                } else {
                    OrderNotificationListenerService.recentLogs.asReversed().forEach { item ->
                        sb.append("[${item.time}] [${item.pkgName}] ${item.title} -> ${item.status}\n")
                    }
                }
            }
            activity.runOnUiThread {
                tvLogContent.text = sb.toString()
            }
        }

        OrderNotificationListenerService.onLogUpdated = { updateLogsText() }
        updateLogsText()

        cardLog.addView(tvLogTitle)
        cardLog.addView(tvLogContent)
        container.addView(cardLog)
    }

    private fun isNotificationServiceEnabled(context: Context): Boolean {
        val pkgName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        if (flat != null && flat.isNotEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = android.content.ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) {
                    return true
                }
            }
        }
        return false
    }
}
