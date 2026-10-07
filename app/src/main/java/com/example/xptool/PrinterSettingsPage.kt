package com.example.xptool

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

object PrinterSettingsPage {

    fun buildView(
        activity: MainActivity,
        container: LinearLayout,
        sendPrintData: (label: String, data: ByteArray) -> Unit,
        log: (String) -> Unit
    ) {
        // Banner chú thích ở đầu trang
        val tvBanner = TextView(activity).apply {
            text = "⚠️ CHÚ THÍCH:\nĐây là các lệnh cài đặt cấu hình riêng của hãng Xprinter. Các tính năng chưa có dữ liệu bắt gói sẽ tạm thời bị khóa (\"Chưa có lệnh\") để đảm bảo an toàn tuyệt đối cho máy in."
            textSize = 13f
            setTextColor(0xFFD84315.toInt())
            setBackgroundColor(0xFFFBE9E7.toInt())
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
        }
        container.addView(tvBanner)
        container.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(10))
        })

        // 1. Mật độ in (Density)
        val spDensity = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("Mức 1 (Nhạt)", "Mức 2 (Vừa)", "Mức 3 (Đậm)", "Mức 4 (Rất đậm)"))
        }
        addSettingSection(
            activity = activity,
            container = container,
            cmd = CapturedCommands.DENSITY,
            customView = spDensity,
            log = log,
            onApply = {
                val selectedIndex = spDensity.selectedItemPosition + 1
                val bytes = CapturedCommands.DENSITY.getBytes?.invoke(selectedIndex)
                confirmAndSend(activity, "Mật độ in", "Mức $selectedIndex", bytes, sendPrintData, log)
            }
        )

        // 2. Độ rộng giấy (Width)
        val spWidth = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("58 mm", "72 mm", "80 mm"))
        }
        addSettingSection(
            activity = activity,
            container = container,
            cmd = CapturedCommands.PAPER_WIDTH,
            customView = spWidth,
            log = log,
            onApply = {
                val selectedWidth = spWidth.selectedItem.toString()
                val bytes = CapturedCommands.PAPER_WIDTH.getBytes?.invoke(selectedWidth)
                confirmAndSend(activity, "Độ rộng giấy", selectedWidth, bytes, sendPrintData, log)
            }
        )

        // 3. Cut with beep (Cắt giấy kèm tiếng bíp)
        val cbCutBeep = CheckBox(activity).apply { text = "Bật tiếng bíp khi cắt giấy" }
        addSettingSection(
            activity = activity,
            container = container,
            cmd = CapturedCommands.CUT_WITH_BEEP,
            customView = cbCutBeep,
            log = log,
            onApply = {
                val enable = cbCutBeep.isChecked
                val labelVal = if (enable) "BẬT (ON)" else "TẮT (OFF)"
                val bytes = CapturedCommands.CUT_WITH_BEEP.getBytes?.invoke(enable)
                confirmAndSend(activity, "Cut with beep", labelVal, bytes, sendPrintData, log)
            }
        )

        // 4. Cảnh báo chuông & đèn (Sound and Light Alarm)
        val alarmCol = activity.column()
        val cbAlarm = CheckBox(activity).apply { text = "Bật chuông & đèn khi có đơn" }
        val etAlarmTime = activity.edit("Thời gian báo (giây)", "3", InputType.TYPE_CLASS_NUMBER)
        alarmCol.addView(cbAlarm)
        alarmCol.addView(etAlarmTime)
        addSettingSection(
            activity = activity,
            container = container,
            cmd = CapturedCommands.SOUND_LIGHT_ALARM,
            customView = alarmCol,
            log = log,
            onApply = {
                val enable = cbAlarm.isChecked
                val duration = etAlarmTime.text.toString().toIntOrNull() ?: 3
                val labelVal = if (enable) "BẬT ($duration giây)" else "TẮT"
                val bytes = CapturedCommands.SOUND_LIGHT_ALARM.getBytes?.invoke(Pair(enable, duration))
                confirmAndSend(activity, "Cảnh báo chuông & đèn", labelVal, bytes, sendPrintData, log)
            }
        )

        // 5. In lại khi lỗi (Replay)
        val cbReplay = CheckBox(activity).apply { text = "Tự động in lại khi hết giấy hoặc lỗi" }
        addSettingSection(
            activity = activity,
            container = container,
            cmd = CapturedCommands.REPLAY_ON_ERROR,
            customView = cbReplay,
            log = log,
            onApply = {
                val enable = cbReplay.isChecked
                val labelVal = if (enable) "BẬT (ON)" else "TẮT (OFF)"
                val bytes = CapturedCommands.REPLAY_ON_ERROR.getBytes?.invoke(enable)
                confirmAndSend(activity, "Replay khi lỗi", labelVal, bytes, sendPrintData, log)
            }
        )

        // 6. In trang cấu hình (Print H / PrintCodePage)
        addSettingSection(
            activity = activity,
            container = container,
            cmd = CapturedCommands.PRINT_CONFIG_PAGE,
            customView = null,
            log = log,
            onApply = {
                val bytes = CapturedCommands.PRINT_CONFIG_PAGE.getBytes?.invoke(null)
                confirmAndSend(activity, "In trang cấu hình", "In thông tin máy in", bytes, sendPrintData, log)
            }
        )

        // 7. Khôi phục cài đặt gốc (Restore Factory)
        val cardReset = activity.column().apply {
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
            setBackgroundColor(0xFFFFF3E0.toInt())
        }
        val tvResetTitle = activity.label(CapturedCommands.RESTORE_FACTORY.name, 15f, true).apply {
            setTextColor(0xFFC62828.toInt())
        }
        val tvResetNote = activity.label("Ghi chú: ${CapturedCommands.RESTORE_FACTORY.notes}", 12f)

        val btnReset = activity.btn(if (CapturedCommands.RESTORE_FACTORY.verified) "🔥 Khôi phục cài đặt gốc" else "Chưa có lệnh (Khóa)") {
            if (!CapturedCommands.RESTORE_FACTORY.verified || CapturedCommands.RESTORE_FACTORY.getBytes == null) {
                log("Chưa có lệnh khôi phục cài đặt gốc.")
                return@btn
            }

            // Hộp thoại xác nhận 1
            AlertDialog.Builder(activity)
                .setTitle("🚨 CẢNH BÁO NGUY HIỂM")
                .setMessage("Khôi phục cài đặt gốc sẽ xóa toàn bộ IP, tên máy in, thông số WiFi về mặc định nhà sản xuất. Bạn có chắc chắn muốn tiếp tục?")
                .setPositiveButton("Tiếp tục") { _, _ ->
                    // Hộp thoại xác nhận 2 (Nhập RESET)
                    val etConfirm = EditText(activity).apply {
                        hint = "Gõ RESET để xác nhận"
                        inputType = InputType.TYPE_CLASS_TEXT
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("🔒 XÁC NHẬN KÉP")
                        .setMessage("Để tránh bấm nhầm, hãy nhập đúng chữ RESET vào ô dưới đây:")
                        .setView(etConfirm)
                        .setPositiveButton("XÁC NHẬN KHÔI PHỤC") { _, _ ->
                            val txt = etConfirm.text.toString().trim()
                            if (txt == "RESET") {
                                val bytes = CapturedCommands.RESTORE_FACTORY.getBytes.invoke(null)
                                if (bytes != null) {
                                    sendPrintData("Khôi phục cài đặt gốc", bytes)
                                    log("Gửi lệnh Khôi phục cài đặt gốc: ${bytesToHex(bytes)}")
                                } else {
                                    log("Lỗi: Byte lệnh khôi phục cài đặt gốc bị rỗng.")
                                }
                            } else {
                                log("Hủy thao tác: Mã xác nhận không đúng (bạn đã gõ: '$txt').")
                            }
                        }
                        .setNegativeButton("Hủy", null)
                        .show()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }.apply {
            isEnabled = CapturedCommands.RESTORE_FACTORY.verified
        }

        cardReset.addView(tvResetTitle)
        cardReset.addView(tvResetNote)
        cardReset.addView(btnReset)
        container.addView(cardReset)
    }

    private fun addSettingSection(
        activity: MainActivity,
        container: LinearLayout,
        cmd: CapturedCommands.CommandItem,
        customView: View?,
        log: (String) -> Unit,
        onApply: () -> Unit
    ) {
        val card = activity.column().apply {
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        val tvTitle = activity.label(cmd.name, 15f, true)
        val tvNotes = activity.label("Ghi chú: ${cmd.notes}", 12f)

        val btnApply = activity.btn(if (cmd.verified) "Áp dụng ${cmd.name}" else "Chưa có lệnh") {
            if (!cmd.verified || cmd.getBytes == null) {
                log("Chưa có byte lệnh cho ${cmd.name}.")
                return@btn
            }
            onApply()
        }.apply {
            isEnabled = cmd.verified
        }

        card.addView(tvTitle)
        card.addView(tvNotes)
        if (customView != null) {
            card.addView(customView)
        }
        card.addView(btnApply)

        container.addView(card)
        container.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(8))
        })
    }

    private fun confirmAndSend(
        activity: MainActivity,
        settingName: String,
        newValueStr: String,
        cmdBytes: ByteArray?,
        sendPrintData: (label: String, data: ByteArray) -> Unit,
        log: (String) -> Unit
    ) {
        if (cmdBytes == null) {
            log("Lỗi: Chưa có dữ liệu byte lệnh cho $settingName.")
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Xác nhận thay đổi $settingName")
            .setMessage("Áp dụng giá trị mới: '$newValueStr' vào máy in?")
            .setPositiveButton("Áp dụng") { _, _ ->
                sendPrintData("Cài đặt $settingName", cmdBytes)
                log("Đã gửi lệnh cài đặt $settingName ('$newValueStr'): ${bytesToHex(cmdBytes)}")
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }
}
