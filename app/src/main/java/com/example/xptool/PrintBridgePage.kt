package com.example.xptool

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import net.posprinter.POSConnect
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

object PrintBridgePage {

    fun buildView(
        activity: MainActivity,
        container: LinearLayout,
        log: (String) -> Unit
    ) {
        val activePrinter = PrinterManager.getActivePrinter()
        val connectType = activePrinter?.connectType ?: POSConnect.DEVICE_TYPE_ETHERNET
        val btMac = activePrinter?.btMac ?: ""
        val usbPath = activePrinter?.usbPath ?: ""

        // Kiểm tra loại kết nối: Chỉ áp dụng cho USB hoặc Bluetooth
        if (connectType == POSConnect.DEVICE_TYPE_ETHERNET) {
            val tvWarning = TextView(activity).apply {
                text = "⚠️ LƯU Ý GIAO TIẾP:\nCầu nối in (Print Bridge) chỉ sử dụng khi máy in kết nối với điện thoại qua USB OTG hoặc Bluetooth.\n\nMáy in hiện tại đang chọn kết nối LAN/WiFi (đã có IP & cổng 9100 riêng) nên không cần bật cầu nối."
                textSize = 14f
                setTextColor(0xFFD84315.toInt())
                setBackgroundColor(0xFFFBE9E7.toInt())
                setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            }
            container.addView(tvWarning)
            return
        }

        // Warning bảo mật
        val tvSecNotice = TextView(activity).apply {
            text = "🛡️ BẢO MẬT & MẠNG:\nKhi dịch vụ Cầu nối in hoạt động, điện thoại sẽ lắng nghe kết nối TCP tại cổng 9100. Các thiết bị khác trong cùng mạng WiFi (máy tính, phần mềm POS) có thể in ra máy in đang nối với điện thoại qua USB/Bluetooth."
            textSize = 13f
            setTextColor(0xFF1565C0.toInt())
            setBackgroundColor(0xFFE3F2FD.toInt())
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
        }
        container.addView(tvSecNotice)
        container.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(8))
        })

        // Form cấu hình
        val etPort = activity.edit("Cổng TCP (mặc định 9100)", PrintBridgeService.currentPort.toString(), InputType.TYPE_CLASS_NUMBER)
        val etAllowedIps = activity.edit("Danh sách IP được phép (phân cách bằng dấu phẩy, ví dụ: 192.168.1.50, để rỗng = dải WiFi local)", "")

        val tvStatus = activity.label("Trạng thái: Đang kiểm tra...", 14f, true)
        val tvJobs = activity.label("Số job đã xử lý: 0", 13f)
        val tvError = activity.label("Lỗi gần nhất: Không có", 12f)

        val btnToggle = activity.btn(if (PrintBridgeService.isRunning) "⏹ Dừng Cầu nối in" else "🚀 Bật Cầu nối in") {}

        val updateUiState = {
            activity.runOnUiThread {
                if (PrintBridgeService.isRunning) {
                    btnToggle.text = "⏹ Dừng Cầu nối in"
                    tvStatus.text = "Trạng thái: 🟢 ĐANG CHẠY tại IP: ${PrintBridgeService.serverIpStr}:${PrintBridgeService.currentPort}"
                    tvStatus.setTextColor(0xFF2E7D32.toInt())
                } else {
                    btnToggle.text = "🚀 Bật Cầu nối in"
                    tvStatus.text = "Trạng thái: 🔴 ĐANG TẮT"
                    tvStatus.setTextColor(0xFFC62828.toInt())
                }
                tvJobs.text = "Số job đã xử lý: ${PrintBridgeService.processedJobsCount}"
                tvError.text = "Lỗi gần nhất: ${PrintBridgeService.lastErrorStr}"
            }
        }

        PrintBridgeService.onStateChangeListener = { updateUiState() }

        btnToggle.setOnClickListener {
            if (PrintBridgeService.isRunning) {
                val stopIntent = Intent(activity, PrintBridgeService::class.java).apply {
                    action = PrintBridgeService.ACTION_STOP
                }
                activity.startService(stopIntent)
                log("Đã gửi yêu cầu Dừng Cầu nối in.")
            } else {
                val port = etPort.text.toString().trim().toIntOrNull() ?: 9100
                val allowedIps = etAllowedIps.text.toString().trim()

                // Kiểm tra xin quyền POST_NOTIFICATIONS trên Android 13+ (API 33+)
                if (Build.VERSION.SDK_INT >= 33) {
                    if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 102)
                        log("Cần cấp quyền Thông báo để dịch vụ Cầu nối in chạy nền cố định.")
                    }
                }

                val startIntent = Intent(activity, PrintBridgeService::class.java).apply {
                    action = PrintBridgeService.ACTION_START
                    putExtra(PrintBridgeService.EXTRA_PORT, port)
                    putExtra(PrintBridgeService.EXTRA_ALLOWED_IPS, allowedIps)
                    putExtra(PrintBridgeService.EXTRA_CONNECT_TYPE, connectType)
                    putExtra(PrintBridgeService.EXTRA_BT_MAC, btMac)
                    putExtra(PrintBridgeService.EXTRA_USB_PATH, usbPath)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(startIntent)
                } else {
                    activity.startService(startIntent)
                }
                log("Đã bật dịch vụ Cầu nối in tại cổng $port...")
            }
        }

        // Nút in thử qua cổng 9100 của chính điện thoại (loopback)
        val btnTestLocal = activity.btn("🧪 Gửi in thử qua cầu nối (Loopback 9100)") {
            if (!PrintBridgeService.isRunning) {
                log("Cầu nối in chưa bật. Vui lòng bật cầu nối trước khi gửi in thử.")
                return@btn
            }

            val targetPort = PrintBridgeService.currentPort
            log("Đang gửi bản in thử đến 127.0.0.1:$targetPort...")

            thread {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", targetPort), 3000)
                        val out = socket.getOutputStream()
                        val initBytes = byteArrayOf(0x1B, 0x40)
                        val textBytes = "=== IN THỦ QUA CẦU NỐI PRINT BRIDGE ===\nThời gian: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\nKết nối OK!\n\n\n".toByteArray(Charsets.US_ASCII)
                        val cutBytes = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

                        out.write(initBytes)
                        out.write(textBytes)
                        out.write(cutBytes)
                        out.flush()
                    }
                    activity.runOnUiThread {
                        log("✔ Đã gửi bản in thử qua Cầu nối 9100 thành công.")
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        log("Gửi in thử lỗi: ${e.message}")
                    }
                }
            }
        }

        // Nút Mở cài đặt tối ưu pin
        val btnBatteryOpt = activity.btn("⚙ Hướng dẫn Tắt Tối ưu Pin (Battery Optimization)") {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    activity.startActivity(intent)
                } else {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                }
                log("Đã mở màn hình Cài đặt Pin. Hãy chọn 'Không tối ưu' cho ứng dụng XP Tool để dịch vụ không bị Android ngắt.")
            } catch (e: Exception) {
                log("Không thể mở Cài đặt Pin tự động: ${e.message}")
            }
        }

        container.addView(activity.label("Cấu hình Cầu nối in (Print Bridge):", 14f, true))
        container.addView(etPort)
        container.addView(etAllowedIps)
        container.addView(btnToggle)

        val cardStatus = activity.column().apply {
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        cardStatus.addView(tvStatus)
        cardStatus.addView(tvJobs)
        cardStatus.addView(tvError)

        container.addView(cardStatus)
        container.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(8))
        })
        container.addView(btnTestLocal)
        container.addView(btnBatteryOpt)

        updateUiState()
    }
}
