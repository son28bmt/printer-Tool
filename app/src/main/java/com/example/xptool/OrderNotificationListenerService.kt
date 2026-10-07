package com.example.xptool

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import net.posprinter.IConnectListener
import net.posprinter.POSConnect
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class OrderNotificationListenerService : NotificationListenerService() {

    companion object {
        const val PREF_NAME = "xp_notif_config"
        const val KEY_ENABLED = "enabled"
        const val KEY_PKG_FILTER = "pkg_filter"
        const val KEY_KEYWORD_FILTER = "keyword_filter"
        const val KEY_PRINT_TITLE = "print_title"
        const val KEY_PRINT_CONTENT = "print_content"
        const val KEY_CUT_PAPER = "cut_paper"

        data class NotifLog(
            val time: String,
            val pkgName: String,
            val title: String,
            val status: String
        )

        val recentLogs = mutableListOf<NotifLog>()
        var onLogUpdated: (() -> Unit)? = null

        fun addLog(pkg: String, title: String, status: String) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            synchronized(recentLogs) {
                if (recentLogs.size >= 30) recentLogs.removeAt(0)
                recentLogs.add(NotifLog(time, pkg, title, status))
            }
            onLogUpdated?.invoke()
        }
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.US)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkgName = sbn.packageName ?: ""
        if (pkgName == packageName) return // Bỏ qua thông báo của chính XP Tool

        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        if (!enabled) return

        val pkgFilter = prefs.getString(KEY_PKG_FILTER, "")?.trim() ?: ""
        val keywordFilter = prefs.getString(KEY_KEYWORD_FILTER, "")?.trim() ?: ""
        val printTitle = prefs.getBoolean(KEY_PRINT_TITLE, true)
        val printContent = prefs.getBoolean(KEY_PRINT_CONTENT, true)
        val cutPaper = prefs.getBoolean(KEY_CUT_PAPER, true)

        // Lấy dữ liệu tiêu đề và nội dung thông báo
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        val fullText = ("$text $bigText $subText").trim()

        // 1. Kiểm tra lọc tên gói (Package Filter)
        if (pkgFilter.isNotEmpty()) {
            if (!pkgName.contains(pkgFilter, ignoreCase = true)) {
                return
            }
        }

        // 2. Kiểm tra lọc từ khóa (Keyword Filter)
        if (keywordFilter.isNotEmpty()) {
            val combined = "$title $fullText"
            if (!combined.contains(keywordFilter, ignoreCase = true)) {
                return
            }
        }

        // Bắt đầu in đơn hàng từ thông báo
        thread {
            sendNotificationToPrinter(pkgName, title, fullText, printTitle, printContent, cutPaper)
        }
    }

    private fun sendNotificationToPrinter(
        pkgName: String,
        title: String,
        content: String,
        printTitle: Boolean,
        printContent: Boolean,
        cutPaper: Boolean
    ) {
        val bytes = buildNotificationReceiptBytes(pkgName, title, content, printTitle, printContent, cutPaper)

        // Lấy thông tin máy in đang chọn
        val activePrinter = PrinterManager.getActivePrinter()
        val xpPrefs = getSharedPreferences("xp", Context.MODE_PRIVATE)

        val connectType = activePrinter?.connectType ?: xpPrefs.getInt("connType", POSConnect.DEVICE_TYPE_ETHERNET)
        val targetIp = activePrinter?.ip ?: xpPrefs.getString("ip", "192.168.4.2") ?: "192.168.4.2"
        val targetPort = activePrinter?.port ?: xpPrefs.getInt("port", 9100)
        val btMac = activePrinter?.btMac ?: xpPrefs.getString("btMac", "") ?: ""
        val usbPath = activePrinter?.usbPath ?: xpPrefs.getString("usbPath", "") ?: ""

        var success = false
        var errorMsg = ""

        if (connectType == POSConnect.DEVICE_TYPE_ETHERNET) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(targetIp, targetPort), 3000)
                    val out = socket.getOutputStream()
                    out.write(bytes)
                    out.flush()
                }
                success = true
            } catch (e: Exception) {
                errorMsg = "Lỗi Socket LAN: ${e.message}"
            }
        } else {
            try {
                val conn = POSConnect.createDevice(connectType)
                val infoStr = if (connectType == POSConnect.DEVICE_TYPE_BLUETOOTH) btMac else usbPath
                val connected = AtomicBoolean(false)

                conn.connect(infoStr) { code, _, msg ->
                    if (code == POSConnect.CONNECT_SUCCESS) connected.set(true)
                    else errorMsg = msg ?: "Mã $code"
                }
                Thread.sleep(1200)

                if (connected.get()) {
                    conn.sendData(bytes)
                    success = true
                    try { conn.close() } catch (_: Exception) {}
                } else {
                    errorMsg = "Lỗi kết nối SDK: $errorMsg"
                    try { conn.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                errorMsg = "Lỗi SDK: ${e.message}"
            }
        }

        if (success) {
            addLog(pkgName, title, "✔ In thành công")
        } else {
            addLog(pkgName, title, "❌ In thất bại ($errorMsg)")
        }
    }

    private fun buildNotificationReceiptBytes(
        pkgName: String,
        title: String,
        content: String,
        printTitle: Boolean,
        printContent: Boolean,
        cutPaper: Boolean
    ): ByteArray {
        val b = ByteArrayOutputStream()
        val escInit = byteArrayOf(0x1B, 0x40)
        val cutCmd = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

        b.write(escInit)
        b.write("=== TỰ ĐỘNG IN THÔNG BÁO ===\n".toByteArray(Charsets.US_ASCII))
        b.write("App: $pkgName\n".toByteArray(Charsets.US_ASCII))
        b.write("Gio: ${timeFmt.format(Date())}\n".toByteArray(Charsets.US_ASCII))
        b.write("--------------------------------\n".toByteArray(Charsets.US_ASCII))

        if (printTitle && title.isNotEmpty()) {
            b.write("TIEU DE: $title\n".toByteArray(Charsets.US_ASCII))
        }

        if (printContent && content.isNotEmpty()) {
            b.write("NOI DUNG: $content\n".toByteArray(Charsets.US_ASCII))
        }

        b.write("--------------------------------\n\n\n".toByteArray(Charsets.US_ASCII))

        if (cutPaper) {
            b.write(cutCmd)
        }

        return b.toByteArray()
    }
}
