package com.example.xptool

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import net.posprinter.IConnectListener
import net.posprinter.POSConnect
import net.posprinter.POSConst
import net.posprinter.POSPrinter
import net.posprinter.esc.PosUdpNet
import net.posprinter.model.UdpDevice
import net.posprinter.posprinterface.UdpCallback
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    // máy in
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText

    // mạng (UDP của SDK)
    private lateinit var spFound: Spinner
    private lateinit var foundAdapter: ArrayAdapter<String>
    private lateinit var etNewIp: EditText
    private lateinit var etNewMask: EditText
    private lateinit var etNewGw: EditText
    private lateinit var cbDhcp: CheckBox

    // wifi
    private lateinit var etSsid: EditText
    private lateinit var etWifiPass: EditText
    private lateinit var spEnc: Spinner

    // gói thô
    private lateinit var etHex: EditText
    private lateinit var etListen: EditText
    private lateinit var cbBroadcast: CheckBox
    private lateinit var spProto: Spinner

    // log
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val udp = PosUdpNet()
    private val found = mutableListOf<UdpDevice>()
    private var multicastLock: WifiManager.MulticastLock? = null

    // SDK giữ callback bằng WeakReference, nên PHẢI giữ tham chiếu mạnh ở đây,
    // nếu không callback bị GC và không bao giờ được gọi.
    private val udpCallback = UdpCallback { d -> onFound(d) }
    private var connListener: IConnectListener? = null

    private val encTypes = listOf(
        "WPA2_AES_PSK" to POSConst.ENCRYPT_WPA2_AES_PSK,
        "WPA2_TKIP_AES_PSK" to POSConst.ENCRYPT_WPA2_TKIP_AES_PSK,
        "WPA2_TKIP" to POSConst.ENCRYPT_WPA2_TKIP,
        "WPA_WPA2_MixedMode" to POSConst.ENCRYPT_WPA_WPA2_MixedMode,
        "WPA_AES_PSK" to POSConst.ENCRYPT_WPA_AES_PSK,
        "WPA_TKIP_PSK" to POSConst.ENCRYPT_WPA_TKIP_PSK,
        "WPA_TKIP_AES_PSK" to POSConst.ENCRYPT_WPA_TKIP_AES_PSK,
        "WEP64" to POSConst.ENCRYPT_WEP64,
        "WEP128" to POSConst.ENCRYPT_WEP128,
        "Không mã hóa" to POSConst.ENCRYPT_NULL
    )

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        POSConnect.init(applicationContext)

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("xptool").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply { addView(controls) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // 1. Máy in
        controls.addView(title("1. Máy in (để in thử / gửi lệnh)"))
        etIp = EditText(this).apply {
            hint = "IP máy in"
            setText("192.168.4.2")
            inputType = InputType.TYPE_CLASS_PHONE
        }
        etPort = EditText(this).apply {
            hint = "Cổng"
            setText("9100")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        controls.addView(row(etIp to 3f, etPort to 1.2f))
        controls.addView(
            row(
                btn("IP điện thoại") { showPhoneIps() } to 1f,
                btn("Test kết nối") { testConnect() } to 1f
            )
        )

        // 2. Tìm + đổi IP (UDP broadcast, giống nút Refresh / Set New IP trên PC)
        controls.addView(title("2. Tìm máy in và đổi IP (UDP, theo MAC)"))
        controls.addView(btn("Tìm máy in trong mạng") { searchPrinters() })
        foundAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mutableListOf<String>())
        spFound = Spinner(this).apply { adapter = foundAdapter }
        controls.addView(spFound)
        etNewIp = EditText(this).apply {
            hint = "IP mới, vd 192.168.1.100"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        etNewMask = EditText(this).apply {
            hint = "Netmask"
            setText("255.255.255.0")
            inputType = InputType.TYPE_CLASS_PHONE
        }
        etNewGw = EditText(this).apply {
            hint = "Gateway, vd 192.168.1.1"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        cbDhcp = CheckBox(this).apply { text = "Bật DHCP (máy in tự xin IP từ router)" }
        controls.addView(etNewIp)
        controls.addView(row(etNewMask to 1f, etNewGw to 1f))
        controls.addView(cbDhcp)
        controls.addView(btn("Đổi IP máy in đã chọn") { applyIp() })

        // 3. WiFi
        controls.addView(title("3. Cấu hình WiFi cho máy in (cần kết nối được tới IP ở mục 1)"))
        etSsid = EditText(this).apply { hint = "Tên WiFi (SSID)" }
        etWifiPass = EditText(this).apply { hint = "Mật khẩu WiFi" }
        spEnc = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                encTypes.map { it.first }
            )
        }
        controls.addView(etSsid)
        controls.addView(etWifiPass)
        controls.addView(spEnc)
        controls.addView(btn("Gửi cấu hình WiFi (dùng IP/mask/gateway ở mục 2)") { sendWifi() })

        // 4. ESC/POS
        controls.addView(title("4. Lệnh ESC/POS (TCP)"))
        controls.addView(
            row(
                btn("In thử") { escPos("In thử", TEST_PRINT) } to 1f,
                btn("Cắt giấy") { escPos("Cắt giấy", CUT) } to 1f
            )
        )
        controls.addView(
            row(
                btn("Mở két") { escPos("Mở két", DRAWER) } to 1f,
                btn("Test print (GS ( A)") { escPos("GS ( A", SELF_TEST) } to 1f
            )
        )

        // 5. Gói thô
        controls.addView(title("5. Gửi gói thô (hex)"))
        spProto = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("TCP", "UDP")
            )
        }
        cbBroadcast = CheckBox(this).apply { text = "Broadcast 255.255.255.255 (UDP)" }
        etHex = EditText(this).apply {
            hint = "vd: 1B 40 hoặc 0x1B,0x40"
            typeface = Typeface.MONOSPACE
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        controls.addView(row(spProto to 1f, cbBroadcast to 2f))
        controls.addView(etHex)
        controls.addView(btn("Gửi") { sendRaw() })

        // 6. Nghe UDP
        controls.addView(title("6. Nghe UDP"))
        etListen = EditText(this).apply {
            hint = "Cổng UDP để nghe"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        controls.addView(row(etListen to 1f, btn("Nghe 20 giây") { listenUdp() } to 1f))
        controls.addView(btn("Xóa log") { tvLog.text = "" })

        // Log
        tvLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(tvLog) }
        root.addView(
            logScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220))
        )

        setContentView(root)
        log("Sẵn sàng. Điện thoại phải cùng mạng WiFi/router với máy in.")
    }

    override fun onDestroy() {
        try {
            udp.closeNetSocket()
            multicastLock?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg items: Pair<View, Float>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((v, w) in items) {
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w))
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            tvLog.append("[${timeFmt.format(Date())}] $msg\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun hex(b: ByteArray, n: Int = b.size) =
        (0 until n).joinToString(" ") { "%02X".format(b[it]) }

    private fun ascii(b: ByteArray, n: Int = b.size) =
        (0 until n).map { b[it].toInt().toChar() }
            .joinToString("") { if (it.code in 32..126) it.toString() else "." }

    private fun parseHex(s: String): ByteArray? {
        val clean = s.replace("0x", "", ignoreCase = true).filter {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ip4(s: String): ByteArray? {
        val p = s.trim().split(".")
        if (p.size != 4) return null
        val out = ByteArray(4)
        for (i in 0..3) {
            val v = p[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }

    private fun ipStr(b: ByteArray) = b.joinToString(".") { (it.toInt() and 0xFF).toString() }

    private fun targetIp() = etIp.text.toString().trim()
    private fun targetPort() = etPort.text.toString().trim().toIntOrNull()

    // ------------------------------------------------- tìm + đổi IP (SDK)

    private fun onFound(d: UdpDevice) {
        // callback chạy trên main thread
        if (found.any { it.macStr == d.macStr }) return
        found.add(d)
        val label = "${d.ipStr}  [${d.macStr}]" + if (d.isDhcp) "  DHCP" else ""
        foundAdapter.add(label)
        log("Tìm thấy: IP ${d.ipStr}, mask ${d.maskStr}, gw ${d.gatewayStr}, MAC ${d.macStr}, DHCP=${d.isDhcp}")
        if (found.size == 1) {
            etIp.setText(d.ipStr)
            if (etNewGw.text.isNullOrBlank()) etNewGw.setText(d.gatewayStr)
        }
    }

    private fun searchPrinters() {
        found.clear()
        foundAdapter.clear()
        log("Gửi broadcast UDP tìm máy in (cổng 9000)...")
        thread {
            try {
                udp.searchNetDevice(udpCallback)
            } catch (e: Exception) {
                log("Tìm lỗi: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        mainHandler.postDelayed({
            if (found.isEmpty()) {
                log(
                    "Không thấy máy in nào. Kiểm tra: điện thoại cùng mạng/router với máy in, " +
                        "máy in đã cắm dây LAN và bật, thử tắt dữ liệu di động."
                )
            }
        }, 4000)
    }

    private fun applyIp() {
        val dev = found.getOrNull(spFound.selectedItemPosition)
            ?: return log("Chưa chọn máy in. Bấm 'Tìm máy in' trước.")
        val ip = ip4(etNewIp.text.toString()) ?: return log("IP mới không hợp lệ")
        val mask = ip4(etNewMask.text.toString()) ?: return log("Netmask không hợp lệ")
        val gw = ip4(etNewGw.text.toString()) ?: return log("Gateway không hợp lệ")
        val dhcp = cbDhcp.isChecked

        AlertDialog.Builder(this)
            .setTitle("Đổi IP máy in?")
            .setMessage(
                "MAC ${dev.macStr}\n" +
                    "${dev.ipStr} → ${ipStr(ip)}\n" +
                    "Mask ${ipStr(mask)}\nGateway ${ipStr(gw)}\nDHCP: $dhcp"
            )
            .setPositiveButton("Đổi") { _, _ ->
                thread {
                    try {
                        udp.udpNetConfig(dev.macAddress, ip, mask, gw, dhcp)
                        log(
                            "Đã gửi lệnh đổi IP cho ${dev.macStr}. Đợi vài giây (nếu chưa đổi thì tắt " +
                                "mở lại máy in), rồi bấm 'Tìm máy in' để kiểm tra."
                        )
                    } catch (e: Exception) {
                        log("Đổi IP lỗi: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // --------------------------------------------------------------- WiFi

    private fun sendWifi() {
        val ssid = etSsid.text.toString()
        if (ssid.isEmpty()) return log("SSID không được trống")
        val pass = etWifiPass.text.toString()
        val ip = ip4(etNewIp.text.toString()) ?: return log("Điền IP (mục 2) cho máy in khi dùng WiFi")
        val mask = ip4(etNewMask.text.toString()) ?: return log("Netmask không hợp lệ")
        val gw = ip4(etNewGw.text.toString()) ?: return log("Gateway không hợp lệ")
        val enc = encTypes[spEnc.selectedItemPosition].second
        val target = targetIp()

        log("Kết nối SDK tới $target ...")
        val conn = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
        val listener = IConnectListener { code, _, msg ->
            when (code) {
                POSConnect.CONNECT_SUCCESS -> {
                    log("Đã kết nối. Gửi cấu hình WiFi SSID='$ssid'")
                    POSPrinter(conn).wifiConfig(ip, mask, gw, ssid, pass, enc)
                    log("Đã gửi. Tắt mở lại máy in để áp dụng, rồi bấm 'Tìm máy in'.")
                    mainHandler.postDelayed({ conn.close() }, 2000)
                }
                POSConnect.CONNECT_FAIL -> log("Kết nối thất bại: $msg")
                else -> log("Trạng thái kết nối: code=$code $msg")
            }
        }
        connListener = listener // giữ tham chiếu mạnh
        conn.connect(target, listener)
    }

    // ------------------------------------------------------------ actions

    private fun showPhoneIps() {
        val ips = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { ni -> ni.inetAddresses.toList().map { ni.name to it } }
                .filter { (_, a) -> a is Inet4Address && !a.isLoopbackAddress }
                .map { (n, a) -> "$n ${a.hostAddress}" }
        } catch (e: Exception) {
            listOf("lỗi: ${e.message}")
        }
        log("IP điện thoại: ${ips.joinToString(" | ").ifEmpty { "không có" }}")
    }

    private fun testConnect() {
        val ip = targetIp()
        val port = targetPort() ?: return log("Cổng không hợp lệ")
        thread {
            val t0 = System.currentTimeMillis()
            try {
                Socket().use { it.connect(InetSocketAddress(ip, port), 3000) }
                log("Kết nối TCP $ip:$port OK (${System.currentTimeMillis() - t0} ms)")
            } catch (e: Exception) {
                log("Kết nối TCP $ip:$port THẤT BẠI: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun escPos(label: String, payload: ByteArray) {
        val port = targetPort() ?: return log("Cổng không hợp lệ")
        sendTcp(targetIp(), port, payload, label)
    }

    private fun sendRaw() {
        val data = parseHex(etHex.text.toString())
            ?: return log("Hex không hợp lệ (cần số ký tự chẵn, 0-9 A-F)")
        val port = targetPort() ?: return log("Cổng không hợp lệ")
        if (spProto.selectedItem == "TCP") {
            sendTcp(targetIp(), port, data, "raw")
        } else {
            val ip = if (cbBroadcast.isChecked) "255.255.255.255" else targetIp()
            sendUdp(ip, port, data, cbBroadcast.isChecked)
        }
    }

    private fun sendTcp(ip: String, port: Int, data: ByteArray, label: String) {
        thread {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), 3000)
                    s.getOutputStream().apply {
                        write(data)
                        flush()
                    }
                    log("TCP → $ip:$port [$label] ${data.size} byte: ${hex(data)}")
                    s.soTimeout = 1500
                    try {
                        val buf = ByteArray(1024)
                        val n = s.getInputStream().read(buf)
                        if (n > 0) log("TCP ← ${hex(buf, n)}  |${ascii(buf, n)}|")
                    } catch (_: SocketTimeoutException) {
                    }
                }
            } catch (e: Exception) {
                log("TCP lỗi $ip:$port: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun sendUdp(ip: String, port: Int, data: ByteArray, broadcast: Boolean) {
        thread {
            try {
                DatagramSocket().use { s ->
                    s.broadcast = broadcast
                    s.soTimeout = 1000
                    s.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), port))
                    log("UDP → $ip:$port ${data.size} byte (cổng nguồn ${s.localPort}): ${hex(data)}")
                    val end = System.currentTimeMillis() + 3000
                    var got = 0
                    while (System.currentTimeMillis() < end) {
                        try {
                            val buf = ByteArray(2048)
                            val p = DatagramPacket(buf, buf.size)
                            s.receive(p)
                            got++
                            log(
                                "UDP ← ${p.address.hostAddress}:${p.port} ${p.length} byte: " +
                                    "${hex(buf, p.length)}  |${ascii(buf, p.length)}|"
                            )
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                    if (got == 0) log("Không có phản hồi UDP sau 3 giây")
                }
            } catch (e: Exception) {
                log("UDP lỗi: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun listenUdp() {
        val port = etListen.text.toString().trim().toIntOrNull()
            ?: return log("Nhập cổng UDP để nghe")
        thread {
            try {
                DatagramSocket(null).use { s ->
                    s.reuseAddress = true
                    s.broadcast = true
                    s.bind(InetSocketAddress(port))
                    s.soTimeout = 1000
                    log("Đang nghe UDP cổng $port trong 20 giây...")
                    val end = System.currentTimeMillis() + 20_000
                    var got = 0
                    while (System.currentTimeMillis() < end) {
                        try {
                            val buf = ByteArray(2048)
                            val p = DatagramPacket(buf, buf.size)
                            s.receive(p)
                            got++
                            log(
                                "UDP ← ${p.address.hostAddress}:${p.port} ${p.length} byte: " +
                                    "${hex(buf, p.length)}  |${ascii(buf, p.length)}|"
                            )
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                    log("Hết thời gian nghe, nhận $got gói.")
                }
            } catch (e: Exception) {
                log("Nghe UDP lỗi: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ ESC/POS

    private val ESC_INIT = bytes(0x1B, 0x40)
    private val CUT = bytes(0x1D, 0x56, 0x42, 0x00)
    private val DRAWER = bytes(0x1B, 0x70, 0x00, 0x19, 0xFA)
    private val SELF_TEST = bytes(0x1D, 0x28, 0x41, 0x02, 0x00, 0x00, 0x02)
    private val TEST_PRINT: ByteArray
        get() = ESC_INIT +
            "XP TOOL TEST\n------------\nHello printer!\n\n\n".toByteArray(Charsets.US_ASCII) +
            CUT
}
