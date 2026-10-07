package com.example.xptool

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import net.posprinter.IDeviceConnection
import net.posprinter.POSConnect
import net.posprinter.POSPrinter
import net.posprinter.posprinterface.IDataCallback
import net.posprinter.posprinterface.IStatusCallback
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object InfoTestPage {

    private var isStressTesting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Giữ tham chiếu mạnh tới callback
    private var lastDataCallback: IDataCallback? = null
    private var lastStatusCallback: IStatusCallback? = null

    fun stopStressTest() {
        isStressTesting.set(false)
    }

    fun buildView(
        activity: MainActivity,
        container: LinearLayout,
        log: (String) -> Unit
    ) {
        val activePrinter = PrinterManager.getActivePrinter()
        val connectType = activePrinter?.connectType ?: POSConnect.DEVICE_TYPE_ETHERNET
        val targetIp = activePrinter?.ip ?: "192.168.1.100"
        val targetPort = activePrinter?.port ?: 9100
        val btMac = activePrinter?.btMac ?: ""
        val usbPath = activePrinter?.usbPath ?: ""

        val rg = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val rbInfo = activity.radio("1. Đọc Serial & Trạng thái máy in")
        val rbBt = activity.radio("2. Đổi Tên & PIN Bluetooth")
        val rbStress = activity.radio("3. In thử liên tục (Stress Test)")
        rg.addView(rbInfo)
        rg.addView(rbBt)
        rg.addView(rbStress)

        container.addView(rg)

        // Ẩn khối đổi Bluetooth nếu không phải kết nối Bluetooth
        if (connectType != POSConnect.DEVICE_TYPE_BLUETOOTH) {
            rbBt.visibility = View.GONE
        }

        // ------------------------------------------------------------- Khối 1: Serial & Status
        val panelInfo = activity.column().apply { visibility = View.GONE }
        val tvSerialResult = activity.label("Chưa đọc Serial", 14f)
        val tvStatusResult = activity.label("Chưa đọc Trạng thái", 14f)

        val btnSerial = activity.btn("Đọc Serial Number") {
            tvSerialResult.text = "Đang đọc Serial Number..."
            log("Gửi lệnh đọc Serial Number...")

            val sdkSession = SdkSession(activity)
            val infoStr = when (connectType) {
                POSConnect.DEVICE_TYPE_BLUETOOTH -> btMac
                POSConnect.DEVICE_TYPE_USB -> usbPath
                else -> targetIp
            }

            sdkSession.open(connectType, infoStr, 5000) { success, conn, msg ->
                if (!success || conn == null) {
                    tvSerialResult.text = "Lỗi kết nối: $msg"
                    log("Đọc Serial thất bại: $msg")
                    return@open
                }

                val printer = POSPrinter(conn)
                val hasResponded = AtomicBoolean(false)

                val timeoutRunnable = Runnable {
                    if (hasResponded.compareAndSet(false, true)) {
                        sdkSession.close()
                        activity.runOnUiThread {
                            tvSerialResult.text = "Máy này không trả lời (có thể không hỗ trợ lệnh này)"
                            log("Đọc Serial: Hết thời gian chờ (5s).")
                        }
                    }
                }
                mainHandler.postDelayed(timeoutRunnable, 5000)

                val dataCb = IDataCallback { data ->
                    activity.runOnUiThread {
                        if (hasResponded.compareAndSet(false, true)) {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            sdkSession.close()

                            if (data == null || data.isEmpty()) {
                                tvSerialResult.text = "Máy này không trả lời Serial"
                                log("Serial Number: Rỗng")
                            } else {
                                val str = data.map { it.toInt().toChar() }
                                    .filter { it.code in 32..126 }
                                    .joinToString("")
                                    .trim()

                                val serialStr = if (str.isNotEmpty()) str else "0x" + data.joinToString("") { "%02X".format(it) }
                                tvSerialResult.text = "Serial Number: $serialStr"
                                log("Serial Number: $serialStr")
                            }
                        }
                    }
                }
                lastDataCallback = dataCb
                try {
                    printer.getSerialNumber(dataCb)
                } catch (e: Exception) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    sdkSession.close()
                    tvSerialResult.text = "Lỗi gọi API: ${e.message}"
                    log("Đọc Serial lỗi: ${e.message}")
                }
            }
        }

        val btnStatus = activity.btn("Kiểm tra Trạng thái") {
            tvStatusResult.text = "Đang đọc Trạng thái..."
            log("Gửi lệnh kiểm tra Trạng thái máy in...")

            val sdkSession = SdkSession(activity)
            val infoStr = when (connectType) {
                POSConnect.DEVICE_TYPE_BLUETOOTH -> btMac
                POSConnect.DEVICE_TYPE_USB -> usbPath
                else -> targetIp
            }

            sdkSession.open(connectType, infoStr, 5000) { success, conn, msg ->
                if (!success || conn == null) {
                    tvStatusResult.text = "Lỗi kết nối: $msg"
                    log("Đọc Trạng thái thất bại: $msg")
                    return@open
                }

                val printer = POSPrinter(conn)
                val hasResponded = AtomicBoolean(false)

                val timeoutRunnable = Runnable {
                    if (hasResponded.compareAndSet(false, true)) {
                        sdkSession.close()
                        activity.runOnUiThread {
                            tvStatusResult.text = "Máy không trả lời (hết thời gian)"
                            log("Đọc Trạng thái: Hết thời gian chờ (5s).")
                        }
                    }
                }
                mainHandler.postDelayed(timeoutRunnable, 5000)

                val statusCb = IStatusCallback { status ->
                    activity.runOnUiThread {
                        if (hasResponded.compareAndSet(false, true)) {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            sdkSession.close()

                            val statusText = parseStatusMessage(status)
                            tvStatusResult.text = "Trạng thái: $statusText (mã $status)"
                            log("Trạng thái máy in: $statusText (mã $status)")
                        }
                    }
                }
                lastStatusCallback = statusCb
                try {
                    printer.printerStatus(statusCb)
                } catch (e: Exception) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    sdkSession.close()
                    tvStatusResult.text = "Lỗi gọi API: ${e.message}"
                    log("Đọc Trạng thái lỗi: ${e.message}")
                }
            }
        }

        panelInfo.addView(btnSerial)
        panelInfo.addView(tvSerialResult)
        panelInfo.addView(btnStatus)
        panelInfo.addView(tvStatusResult)

        container.addView(panelInfo)

        // ------------------------------------------------------------- Khối 2: Bluetooth Name & PIN
        val panelBt = activity.column().apply { visibility = View.GONE }
        val etBtName = activity.edit("Tên Bluetooth mới")
        val etBtPin = activity.edit("Mã PIN Bluetooth mới (Ví dụ: 0000 hoặc 1234)", "", InputType.TYPE_CLASS_NUMBER)

        val btnSetBt = activity.btn("Áp dụng Đổi Tên & PIN Bluetooth") {
            val newName = etBtName.text.toString().trim()
            val newPin = etBtPin.text.toString().trim()

            if (newName.isEmpty()) return@btn log("Tên Bluetooth không được để rỗng.")
            if (newPin.isEmpty() || !newPin.all { it.isDigit() }) return@btn log("Mã PIN chỉ được chứa chữ số.")

            AlertDialog.Builder(activity)
                .setTitle("Xác nhận đổi Bluetooth")
                .setMessage("Ghi lại PIN hiện tại ($newPin). Sau khi đổi có thể phải hủy ghép đôi và ghép đôi lại bằng PIN mới trên Cài đặt điện thoại. Tiếp tục?")
                .setPositiveButton("Áp dụng") { _, _ ->
                    log("Gửi lệnh đổi Bluetooth: Tên='$newName', PIN='$newPin'...")
                    val sdkSession = SdkSession(activity)
                    sdkSession.open(POSConnect.DEVICE_TYPE_BLUETOOTH, btMac, 5000) { success, conn, msg ->
                        if (!success || conn == null) {
                            return@open log("Kết nối Bluetooth thất bại: $msg")
                        }
                        try {
                            POSPrinter(conn).setBluetooth(newName, newPin)
                            log("✔ Đã gửi lệnh cài đặt Bluetooth Tên='$newName', PIN='$newPin'. Hãy tắt mở lại máy in để áp dụng.")
                        } catch (e: Exception) {
                            log("Cài đặt Bluetooth lỗi: ${e.message}")
                        } finally {
                            sdkSession.close()
                        }
                    }
                }
                .setNegativeButton("Hủy", null)
                .show()
        }

        panelBt.addView(activity.label("Đổi tên & PIN phát Bluetooth của máy in:", 14f, true))
        panelBt.addView(etBtName)
        panelBt.addView(etBtPin)
        panelBt.addView(btnSetBt)

        container.addView(panelBt)

        // ------------------------------------------------------------- Khối 3: Stress Test In thử liên tục
        val panelStress = activity.column().apply { visibility = View.GONE }
        val etCount = activity.edit("Số lần in thử N (1 đến 1000)", "10", InputType.TYPE_CLASS_NUMBER)
        val etDelay = activity.edit("Khoảng nghỉ giữa các lần (ms, tối thiểu 200)", "500", InputType.TYPE_CLASS_NUMBER)
        val cbCut = CheckBox(activity).apply { text = "Cắt giấy sau mỗi lần in"; isChecked = true }

        val tvProgress = activity.label("Trạng thái: Chưa chạy", 14f, true)
        val btnRunStress = activity.btn("🚀 Bắt đầu In thử liên tục") {}

        btnRunStress.setOnClickListener {
            if (isStressTesting.get()) {
                isStressTesting.set(false)
                btnRunStress.text = "🚀 Bắt đầu In thử liên tục"
                log("Đã yêu cầu dừng phiên in thử liên tục.")
                return@setOnClickListener
            }

            val totalN = etCount.text.toString().trim().toIntOrNull() ?: 10
            if (totalN !in 1..1000) return@setOnClickListener log("Số lần in phải từ 1 đến 1000.")
            val delayMs = (etDelay.text.toString().trim().toLongOrNull() ?: 500L).coerceAtLeast(200L)
            val cutPaper = cbCut.isChecked

            isStressTesting.set(true)
            btnRunStress.text = "⏹ Dừng phiên In thử"
            tvProgress.text = "Đang chạy 0/$totalN..."
            log("Bắt đầu Stress Test: $totalN lần in, nghỉ $delayMs ms/lần, Cắt giấy=${cutPaper}...")

            thread {
                var successCount = 0
                var errorCount = 0
                var firstErrorIdx = -1
                var firstErrorMsg = ""
                val startTime = System.currentTimeMillis()

                var conn: IDeviceConnection? = null

                if (connectType != POSConnect.DEVICE_TYPE_ETHERNET) {
                    try {
                        conn = POSConnect.createDevice(connectType)
                        val infoStr = if (connectType == POSConnect.DEVICE_TYPE_BLUETOOTH) btMac else usbPath
                        val connected = AtomicBoolean(false)
                        conn.connect(infoStr) { code, _, _ ->
                            if (code == POSConnect.CONNECT_SUCCESS) connected.set(true)
                        }
                        Thread.sleep(1500)
                    } catch (_: Exception) {
                    }
                }

                for (i in 1..totalN) {
                    if (!isStressTesting.get()) break

                    val data = getStressTestBytes(i, totalN, cutPaper)
                    var ok = false

                    if (connectType == POSConnect.DEVICE_TYPE_ETHERNET) {
                        try {
                            Socket().use { s ->
                                s.connect(InetSocketAddress(targetIp, targetPort), 2000)
                                s.getOutputStream().apply {
                                    write(data)
                                    flush()
                                }
                            }
                            ok = true
                        } catch (e: Exception) {
                            ok = false
                            if (firstErrorIdx == -1) {
                                firstErrorIdx = i
                                firstErrorMsg = "${e.javaClass.simpleName}: ${e.message}"
                            }
                        }
                    } else {
                        try {
                            if (conn != null) {
                                conn.sendData(data)
                                ok = true
                            }
                        } catch (e: Exception) {
                            ok = false
                            if (firstErrorIdx == -1) {
                                firstErrorIdx = i
                                firstErrorMsg = "${e.javaClass.simpleName}: ${e.message}"
                            }
                        }
                    }

                    if (ok) successCount++ else errorCount++

                    if (i % 2 == 0 || i == totalN) {
                        activity.runOnUiThread {
                            tvProgress.text = "Đang chạy $i/$totalN (Thành công $successCount, Lỗi $errorCount)..."
                        }
                    }

                    try {
                        Thread.sleep(delayMs)
                    } catch (_: Exception) {
                    }
                }

                try {
                    conn?.close()
                } catch (_: Exception) {
                }

                val totalTime = System.currentTimeMillis() - startTime
                val avgTime = if (totalN > 0) totalTime / totalN else 0

                activity.runOnUiThread {
                    isStressTesting.set(false)
                    btnRunStress.text = "🚀 Bắt đầu In thử liên tục"
                    val summary = "KẾT QUẢ IN THỬ LIÊN TỤC:\n" +
                            "- Thành công: $successCount/$totalN\n" +
                            "- Số lỗi: $errorCount\n" +
                            (if (firstErrorIdx != -1) "- Lỗi đầu tiên ở lần: #$firstErrorIdx ($firstErrorMsg)\n" else "") +
                            "- Tổng thời gian: ${totalTime / 1000f} giây\n" +
                            "- Trung bình: $avgTime ms/lần"

                    tvProgress.text = summary
                    log(summary)
                }
            }
        }

        panelStress.addView(activity.label("Cấu hình phiên in thử liên tục:", 14f, true))
        panelStress.addView(activity.row(etCount to 1f, etDelay to 1f))
        panelStress.addView(cbCut)
        panelStress.addView(btnRunStress)
        panelStress.addView(tvProgress)

        container.addView(panelStress)

        // ------------------------------------------------------------- Chuyển đổi khối hiển thị
        rg.setOnCheckedChangeListener { _, id ->
            panelInfo.visibility = if (id == rbInfo.id) View.VISIBLE else View.GONE
            panelBt.visibility = if (id == rbBt.id) View.VISIBLE else View.GONE
            panelStress.visibility = if (id == rbStress.id) View.VISIBLE else View.GONE
        }
        rg.check(rbInfo.id)
    }

    private fun parseStatusMessage(status: Int): String {
        if (status == 0) return "Bình thường"
        if (status == -3) return "Mất kết nối"
        if (status == -4) return "Máy không trả lời (hết thời gian)"
        if (status < 0) return "Không rõ (mã $status)"

        val parts = mutableListOf<String>()
        if ((status and 8) != 0) parts.add("Đang nhấn nút nạp giấy")
        if ((status and 16) != 0) parts.add("Nắp máy đang mở")
        if ((status and 32) != 0) parts.add("Hết giấy")
        if ((status and 64) != 0) parts.add("Máy in báo lỗi")

        return if (parts.isNotEmpty()) parts.joinToString(", ") else "Mã $status"
    }

    private fun getStressTestBytes(index: Int, total: Int, cut: Boolean): ByteArray {
        val b = ByteArrayOutputStream()
        val escInit = byteArrayOf(0x1B, 0x40)
        val cutCmd = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

        b.write(escInit)
        b.write("TEST STRESS #$index/$total [${timeFmt.format(Date())}]\n".toByteArray(Charsets.US_ASCII))
        if (cut) {
            b.write(cutCmd)
        }
        return b.toByteArray()
    }
}
