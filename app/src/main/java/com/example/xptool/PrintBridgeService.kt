package com.example.xptool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import net.posprinter.IConnectListener
import net.posprinter.IDeviceConnection
import net.posprinter.POSConnect
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class PrintBridgeService : Service() {

    companion object {
        const val ACTION_START = "com.example.xptool.START_BRIDGE"
        const val ACTION_STOP = "com.example.xptool.STOP_BRIDGE"

        const val EXTRA_PORT = "extra_port"
        const val EXTRA_ALLOWED_IPS = "extra_allowed_ips"
        const val EXTRA_CONNECT_TYPE = "extra_connect_type"
        const val EXTRA_BT_MAC = "extra_bt_mac"
        const val EXTRA_USB_PATH = "extra_usb_path"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var processedJobsCount = 0
            private set

        @Volatile
        var lastErrorStr = "Không có"
            private set

        @Volatile
        var serverIpStr = ""
            private set

        @Volatile
        var currentPort = 9100
            private set

        var onStateChangeListener: (() -> Unit)? = null
    }

    private val CHANNEL_ID = "xp_print_bridge_channel"
    private val NOTIF_ID = 9100

    private var serverSocket: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val isServerRunning = AtomicBoolean(false)
    private val jobExecutor = Executors.newSingleThreadExecutor()

    private var connectType = POSConnect.DEVICE_TYPE_BLUETOOTH
    private var btMac = ""
    private var usbPath = ""
    private var allowedIpsList = listOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopBridgeService()
            return START_NOT_STICKY
        }

        if (action == ACTION_START || intent != null) {
            currentPort = intent.getIntExtra(EXTRA_PORT, 9100)
            connectType = intent.getIntExtra(EXTRA_CONNECT_TYPE, POSConnect.DEVICE_TYPE_BLUETOOTH)
            btMac = intent.getStringExtra(EXTRA_BT_MAC) ?: ""
            usbPath = intent.getStringExtra(EXTRA_USB_PATH) ?: ""

            val rawIps = intent.getStringExtra(EXTRA_ALLOWED_IPS) ?: ""
            allowedIpsList = rawIps.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            startBridgeForeground()
            startTcpServer()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cầu nối in qua mạng (Print Bridge)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Dịch vụ nhận dữ liệu in qua mạng 9100 chuyển đến USB/Bluetooth"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun startBridgeForeground() {
        val stopIntent = Intent(this, PrintBridgeService::class.java).apply {
            this.action = ACTION_STOP
        }
        val pStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val pMain = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        @Suppress("DEPRECATION")
        val notifBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        val actionStop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Dừng",
                pStop
            ).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Action(android.R.drawable.ic_menu_close_clear_cancel, "Dừng", pStop)
        }

        notifBuilder
            .setContentTitle("Cầu nối in đang hoạt động")
            .setContentText("Cổng $currentPort ➔ ${if (connectType == POSConnect.DEVICE_TYPE_USB) "USB" else "Bluetooth"}")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pMain)
            .setOngoing(true)
            .addAction(actionStop)

        val notif = notifBuilder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xptool:PrintBridgeWakeLock").apply {
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun startTcpServer() {
        if (isServerRunning.get()) return

        isServerRunning.set(true)
        isRunning = true
        serverIpStr = getLocalIpAddress()

        thread {
            try {
                val ss = ServerSocket(currentPort)
                serverSocket = ss
                notifyStateChanged()

                while (isServerRunning.get() && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        jobExecutor.execute { handleClientConnection(client) }
                    } catch (e: Exception) {
                        if (!isServerRunning.get()) break
                    }
                }
            } catch (e: Exception) {
                lastErrorStr = "Lỗi khởi tạo cổng $currentPort: ${e.message}"
                notifyStateChanged()
            } finally {
                isRunning = false
                isServerRunning.set(false)
                notifyStateChanged()
            }
        }
    }

    private fun handleClientConnection(client: Socket) {
        val clientIp = client.inetAddress?.hostAddress ?: ""
        client.soTimeout = 3000

        // Kiểm tra IP bảo mật
        if (!isIpAllowed(clientIp)) {
            lastErrorStr = "Từ chối IP không được phép: $clientIp"
            notifyStateChanged()
            try { client.close() } catch (_: Exception) {}
            return
        }

        val bos = ByteArrayOutputStream()
        try {
            val input: InputStream = client.getInputStream()
            val buffer = ByteArray(4096)
            var bytesRead: Int
            val maxBytes = 5 * 1024 * 1024 // Giới hạn 5MB / job

            try {
                while (bos.size() < maxBytes) {
                    bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    bos.write(buffer, 0, bytesRead)
                }
            } catch (_: SocketTimeoutException) {
                // Client dừng gửi dữ liệu trong 3 giây -> tính là kết thúc job
            }

            val jobData = bos.toByteArray()
            if (jobData.isNotEmpty()) {
                sendToPrinter(jobData)
            }
        } catch (e: Exception) {
            lastErrorStr = "Lỗi nhận từ client $clientIp: ${e.message}"
            notifyStateChanged()
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun sendToPrinter(data: ByteArray) {
        var success = false
        var attempts = 0

        while (!success && attempts < 2) {
            attempts++
            try {
                val conn = POSConnect.createDevice(connectType)
                val infoStr = if (connectType == POSConnect.DEVICE_TYPE_BLUETOOTH) btMac else usbPath

                val connected = AtomicBoolean(false)
                var errorMsg = ""

                val listener = IConnectListener { code, _, msg ->
                    if (code == POSConnect.CONNECT_SUCCESS) {
                        connected.set(true)
                    } else {
                        errorMsg = msg ?: "Mã $code"
                    }
                }

                conn.connect(infoStr, listener)
                Thread.sleep(1200)

                if (connected.get()) {
                    conn.sendData(data)
                    success = true
                    processedJobsCount++
                    lastErrorStr = "Không có"
                    notifyStateChanged()
                    try { conn.close() } catch (_: Exception) {}
                } else {
                    lastErrorStr = "Kết nối máy in thất bại (Lần $attempts): $errorMsg"
                    notifyStateChanged()
                    try { conn.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                lastErrorStr = "Lỗi gửi máy in (Lần $attempts): ${e.message}"
                notifyStateChanged()
            }
        }
    }

    private fun isIpAllowed(clientIp: String): Boolean {
        if (clientIp == "127.0.0.1" || clientIp == "localhost") return true

        if (allowedIpsList.isNotEmpty()) {
            return allowedIpsList.contains(clientIp)
        }

        // Nếu danh sách rỗng -> chỉ cho phép IP cùng dải mạng địa phương (subnet)
        val phoneIp = serverIpStr
        if (phoneIp.isEmpty() || clientIp.isEmpty()) return true

        val phoneParts = phoneIp.split(".")
        val clientParts = clientIp.split(".")

        if (phoneParts.size == 4 && clientParts.size == 4) {
            return phoneParts[0] == clientParts[0] &&
                    phoneParts[1] == clientParts[1] &&
                    phoneParts[2] == clientParts[2]
        }
        return true
    }

    private fun stopBridgeService() {
        isServerRunning.set(false)
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null

        notifyStateChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopBridgeService()
        super.onDestroy()
    }

    private fun notifyStateChanged() {
        onStateChangeListener?.invoke()
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ""
    }
}
