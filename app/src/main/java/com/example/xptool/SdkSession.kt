package com.example.xptool

import android.content.Context
import android.os.Handler
import android.os.Looper
import net.posprinter.IConnectListener
import net.posprinter.IDeviceConnection
import net.posprinter.POSConnect
import java.util.concurrent.atomic.AtomicBoolean

class SdkSession(private val context: Context) {
    private var connection: IDeviceConnection? = null
    private var currentListener: IConnectListener? = null
    private val isConnecting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun open(
        connectType: Int,
        targetInfo: String,
        timeoutMs: Long = 5000,
        onResult: (success: Boolean, conn: IDeviceConnection?, message: String) -> Unit
    ) {
        if (isConnecting.get()) {
            onResult(false, null, "Đang có tiến trình kết nối khác đang chạy...")
            return
        }

        if (targetInfo.isEmpty() && connectType != POSConnect.DEVICE_TYPE_ETHERNET) {
            onResult(false, null, "Thông tin kết nối không hợp lệ.")
            return
        }

        isConnecting.set(true)
        val conn = try {
            POSConnect.createDevice(connectType)
        } catch (e: Exception) {
            isConnecting.set(false)
            onResult(false, null, "Không thể khởi tạo thiết bị: ${e.message}")
            return
        }

        connection = conn
        val hasResponded = AtomicBoolean(false)

        val timeoutRunnable = Runnable {
            if (hasResponded.compareAndSet(false, true)) {
                isConnecting.set(false)
                try {
                    conn.close()
                } catch (_: Exception) {
                }
                onResult(false, null, "Kết nối hết thời gian chờ ($timeoutMs ms).")
            }
        }

        mainHandler.postDelayed(timeoutRunnable, timeoutMs)

        // Giữ tham chiếu mạnh tới IConnectListener
        val listener = IConnectListener { code, _, msg ->
            mainHandler.post {
                if (hasResponded.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    isConnecting.set(false)
                    if (code == POSConnect.CONNECT_SUCCESS) {
                        onResult(true, conn, "Kết nối thành công.")
                    } else {
                        try {
                            conn.close()
                        } catch (_: Exception) {
                        }
                        onResult(false, null, "Kết nối thất bại: ${msg ?: "Mã $code"}")
                    }
                }
            }
        }
        currentListener = listener

        try {
            conn.connect(targetInfo, listener)
        } catch (e: Exception) {
            mainHandler.removeCallbacks(timeoutRunnable)
            isConnecting.set(false)
            try {
                conn.close()
            } catch (_: Exception) {
            }
            onResult(false, null, "Lỗi kết nối: ${e.message}")
        }
    }

    fun close() {
        mainHandler.post {
            isConnecting.set(false)
            try {
                connection?.close()
            } catch (_: Exception) {
            }
            connection = null
            currentListener = null
        }
    }
}
