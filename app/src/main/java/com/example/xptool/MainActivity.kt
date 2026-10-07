package com.example.xptool

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import net.posprinter.IConnectListener
import net.posprinter.POSConnect
import net.posprinter.POSConst
import net.posprinter.POSPrinter
import net.posprinter.TSPLPrinter
import net.posprinter.esc.PosUdpNet
import net.posprinter.model.UdpDevice
import net.posprinter.posprinterface.UdpCallback
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MainActivity : Activity() {

    // ----------------------------------------------------- trạng thái chung

    private var connectType = POSConnect.DEVICE_TYPE_ETHERNET // 1: ETHERNET, 2: BLUETOOTH, 3: USB
    private var printerIp = "192.168.4.2"
    private var printerPort = 9100
    private var btMac = ""
    private var usbPath = ""

    private var etIpCur: EditText? = null
    private var etPortCur: EditText? = null
    private var currentPageIsHome = true

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val logBuffer = StringBuilder()
    private var logView: TextView? = null
    private var logScroll: ScrollView? = null

    private val udp = PosUdpNet()
    private val found = Collections.synchronizedList(mutableListOf<UdpDevice>())
    private var multicastLock: WifiManager.MulticastLock? = null

    private val udpCallback = UdpCallback { d -> onFound(d) }
    private var connListener: IConnectListener? = null
    private var onFoundUi: ((UdpDevice) -> Unit)? = null

    private val IPTYPE = InputType.TYPE_CLASS_PHONE

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        POSConnect.init(applicationContext)

        val prefs = getSharedPreferences("xp", MODE_PRIVATE)
        printerIp = prefs.getString("ip", printerIp) ?: printerIp
        printerPort = prefs.getInt("port", printerPort)
        btMac = prefs.getString("btMac", "") ?: ""
        usbPath = prefs.getString("usbPath", "") ?: ""
        connectType = prefs.getInt("connType", POSConnect.DEVICE_TYPE_ETHERNET)

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("xptool").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }

        checkBtPermission()
        log("XP Tool sẵn sàng. Hỗ trợ LAN/WiFi, Bluetooth và USB OTG.")
        showHome()
    }

    override fun onDestroy() {
        try {
            udp.closeNetSocket()
            multicastLock?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!currentPageIsHome) showHome() else super.onBackPressed()
    }

    private fun checkBtPermission() {
        val perms = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") != PackageManager.PERMISSION_GRANTED) {
                perms.add("android.permission.NEARBY_WIFI_DEVICES")
            }
        }
        if (perms.isNotEmpty()) {
            requestPermissions(perms.toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!granted) {
                log("Một số quyền chưa được cấp. Các tính năng quét WiFi/Bluetooth có thể bị hạn chế.")
            }
        }
    }

    // ------------------------------------------------------------ khung UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun label(t: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun edit(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = type
        }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun menuBtn(title: String, sub: String, onClick: () -> Unit) = Button(this).apply {
        text = "$title\n$sub"
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { onClick() }
    }

    private fun row(vararg items: Pair<View, Float>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((v, w) in items) {
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w))
        }
    }

    private fun radio(text: String) = RadioButton(this).apply {
        this.text = text
        id = View.generateViewId()
    }

    private fun showPage(title: String, isHome: Boolean, build: (LinearLayout) -> Unit) {
        syncPrinterFields()
        onFoundUi = null
        currentPageIsHome = isHome

        val root = column().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (!isHome) header.addView(btn("←") { showHome() })
        header.addView(label(title, 20f, true))
        root.addView(header)

        val body = column()
        build(body)
        root.addView(
            ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(
            logBox(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170))
        )
        setContentView(root)
    }

    private fun logBox(): ScrollView {
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            text = logBuffer.toString()
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        logView = tv
        logScroll = sv
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        return sv
    }

    private fun log(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg\n"
        runOnUiThread {
            logBuffer.append(line)
            logView?.append(line)
            logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun printerBar(): View {
        val ip = edit("IP máy in", printerIp, IPTYPE)
        val port = edit("Cổng", printerPort.toString(), InputType.TYPE_CLASS_NUMBER)
        etIpCur = ip
        etPortCur = port
        return row(ip to 3f, port to 1.2f)
    }

    private fun syncPrinterFields() {
        etIpCur?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { printerIp = it }
        etPortCur?.text?.toString()?.trim()?.toIntOrNull()?.let { printerPort = it }
        savePrinter()
        etIpCur = null
        etPortCur = null
    }

    private fun savePrinter() {
        getSharedPreferences("xp", MODE_PRIVATE).edit()
            .putString("ip", printerIp)
            .putInt("port", printerPort)
            .putString("btMac", btMac)
            .putString("usbPath", usbPath)
            .putInt("connType", connectType)
            .apply()
    }

    private fun targetIp() = etIpCur?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: printerIp
    private fun targetPort() = etPortCur?.text?.toString()?.trim()?.toIntOrNull() ?: printerPort

    // ------------------------------------------------------------- TRANG CHỦ

    private fun connTypeName() = when (connectType) {
        POSConnect.DEVICE_TYPE_BLUETOOTH -> "Bluetooth ($btMac)"
        POSConnect.DEVICE_TYPE_USB -> "USB ($usbPath)"
        else -> "LAN/WiFi ($printerIp:$printerPort)"
    }

    private fun showHome() = showPage("XP Tool by QuangSonAIBAT", true) { b ->
        b.addView(label("Đang chọn kết nối: ${connTypeName()}", 14f, true))
        b.addView(menuBtn("1. Chọn giao tiếp kết nối", "Chuyển giữa Mạng LAN/WiFi, Bluetooth và USB OTG") { pageSelectConnection() })
        b.addView(menuBtn("2. Tìm máy in LAN & Đổi IP", "Dò UDP broadcast, đổi IP 1 chạm, bật DHCP") { pageNetwork() })
        b.addView(menuBtn("3. Cấu hình WiFi cho máy in", "Gửi Tên WiFi & Mật khẩu vào máy in") { pageWifi() })
        b.addView(menuBtn("4. In mẫu Hóa đơn & Mã vạch QR", "In Receipt Demo, mã vạch 1D, QR Code thanh toán") { pagePrintReceipt() })
        b.addView(menuBtn("5. Máy in Tem nhãn (TSPL)", "Dành cho máy in tem XP-350B, 365B, 420B...") { pagePrintLabel() })
        b.addView(menuBtn("6. Công cụ nâng cao & Reset", "Gửi gói Hex thô, nghe UDP, Khôi phục cài đặt gốc") { pageAdvanced() })
        b.addView(menuBtn("7. Thông tin ứng dụng & Bảo mật", "Chính sách bảo mật Privacy Policy, tác giả QuangSonAIBAT") { pageAbout() })
    }

    private fun pageAbout() = showPage("Thông tin & Bảo mật", false) { b ->
        b.addView(label("XP Tool by QuangSonAIBAT", 18f, true))
        b.addView(label("Phiên bản: 0.2.0 (Play Store Ready)"))
        b.addView(label("Tác giả / Nhà phát triển: QuangSonAIBAT"))
        b.addView(label("Email hỗ trợ: quangsonnguyen2807@gmail.com"))
        b.addView(label("GitHub Repo: https://github.com/son28bmt/printer-Tool"))
        b.addView(label("Chính sách bảo mật (Privacy Policy):", 15f, true))
        b.addView(label(
            "Ứng dụng KHÔNG thu thập, lưu trữ hay chia sẻ thông tin cá nhân của người dùng. " +
            "Mọi quyền truy cập WiFi, Bluetooth, USB, Vị trí strictly được sử dụng để kết nối và điều khiển máy in cục bộ."
        ))
        b.addView(btn("Xem Chính sách bảo mật (Privacy Policy)") {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://raw.githubusercontent.com/son28bmt/printer-Tool/main/PRIVACY_POLICY.md"))
                startActivity(intent)
            } catch (e: Exception) {
                log("Lỗi mở trình duyệt: ${e.message}")
            }
        })
    }

    // ---------------------------------------- TRANG KẾT NỐI: LAN / BT / USB

    private fun pageSelectConnection() = showPage("Chọn kết nối máy in", false) { b ->
        val rg = RadioGroup(this)
        val rbLan = radio("Mạng LAN / WiFi (Ethernet)")
        val rbBt = radio("Bluetooth (Classic)")
        val rbUsb = radio("Cổng USB OTG")
        rg.addView(rbLan)
        rg.addView(rbBt)
        rg.addView(rbUsb)
        b.addView(rg)

        val lanPanel = column().apply { visibility = View.GONE }
        lanPanel.addView(label("Nhập IP và Cổng TCP máy in (Mặc định: 9100):"))
        lanPanel.addView(printerBar())

        val btPanel = column().apply { visibility = View.GONE }
        val btAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, mutableListOf())
        val spBt = Spinner(this).apply { adapter = btAdapter }
        val btDevices = mutableListOf<BluetoothDevice>()

        fun refreshBt() {
            btAdapter.clear()
            btDevices.clear()
            checkBtPermission()
            val bta = BluetoothAdapter.getDefaultAdapter()
            if (bta == null || !bta.isEnabled) {
                log("Bluetooth chưa được bật trên điện thoại.")
                return
            }
            val bonded = try { bta.bondedDevices } catch (e: Exception) { null }
            if (bonded.isNullOrEmpty()) {
                log("Không thấy thiết bị Bluetooth nào đã ghép đôi. Hãy ghép đôi trong Cài đặt Bluetooth.")
            } else {
                bonded.forEach { dev ->
                    btDevices.add(dev)
                    btAdapter.add("${dev.name ?: "Unknown"} [${dev.address}]")
                }
                log("Đã tìm thấy ${btDevices.size} thiết bị Bluetooth đã ghép đôi.")
            }
        }
        btPanel.addView(label("Chọn máy in Bluetooth đã ghép đôi:"))
        btPanel.addView(spBt)
        btPanel.addView(btn("Làm mới danh sách Bluetooth") { refreshBt() })

        val usbPanel = column().apply { visibility = View.GONE }
        val usbAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, mutableListOf())
        val spUsb = Spinner(this).apply { adapter = usbAdapter }
        val usbNames = mutableListOf<String>()

        fun refreshUsb() {
            usbAdapter.clear()
            usbNames.clear()
            val names: List<String>? = POSConnect.getUsbDevices(applicationContext)
            if (names.isNullOrEmpty()) {
                log("Không tìm thấy thiết bị máy in USB cắm qua cáp OTG.")
            } else {
                for (name in names) {
                    usbNames.add(name)
                    usbAdapter.add(name)
                }
                log("Tìm thấy ${usbNames.size} thiết bị USB.")
            }
        }
        usbPanel.addView(label("Chọn thiết bị USB kết nối:"))
        usbPanel.addView(spUsb)
        usbPanel.addView(btn("Làm mới danh sách USB") { refreshUsb() })

        b.addView(lanPanel)
        b.addView(btPanel)
        b.addView(usbPanel)

        rg.setOnCheckedChangeListener { _, id ->
            lanPanel.visibility = if (id == rbLan.id) View.VISIBLE else View.GONE
            btPanel.visibility = if (id == rbBt.id) View.VISIBLE else View.GONE
            usbPanel.visibility = if (id == rbUsb.id) View.VISIBLE else View.GONE
            if (id == rbBt.id) refreshBt()
            if (id == rbUsb.id) refreshUsb()
        }

        when (connectType) {
            POSConnect.DEVICE_TYPE_BLUETOOTH -> rg.check(rbBt.id)
            POSConnect.DEVICE_TYPE_USB -> rg.check(rbUsb.id)
            else -> rg.check(rbLan.id)
        }

        b.addView(btn("Lưu & Áp dụng phương thức kết nối") {
            when (rg.checkedRadioButtonId) {
                rbLan.id -> {
                    connectType = POSConnect.DEVICE_TYPE_ETHERNET
                    savePrinter()
                    log("Đã chọn kết nối LAN/WiFi: $printerIp:$printerPort")
                }
                rbBt.id -> {
                    val dev = btDevices.getOrNull(spBt.selectedItemPosition)
                        ?: return@btn log("Chưa chọn thiết bị Bluetooth")
                    connectType = POSConnect.DEVICE_TYPE_BLUETOOTH
                    btMac = dev.address
                    savePrinter()
                    log("Đã chọn máy in Bluetooth: ${dev.name} [$btMac]")
                }
                rbUsb.id -> {
                    val path = usbNames.getOrNull(spUsb.selectedItemPosition)
                        ?: return@btn log("Chưa chọn thiết bị USB")
                    connectType = POSConnect.DEVICE_TYPE_USB
                    usbPath = path
                    savePrinter()
                    log("Đã chọn máy in USB: $usbPath")
                }
            }
        })
    }

    // ------------------------------------------- TRANG 1: TÌM MÁY IN & ĐỔI IP

    private fun safeIpStr(d: UdpDevice?): String {
        if (d == null) return ""
        return try {
            val ip = d.ipAddress
            if (ip != null && ip.size >= 4) ipStr(ip)
            else d.ipStr ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun safeMacStr(d: UdpDevice?): String {
        if (d == null) return ""
        return try {
            val mac = d.macStr ?: ""
            if (mac == "00:00:00:00:00:00") "" else mac
        } catch (_: Exception) {
            ""
        }
    }

    private fun createDummyUdpDevice(ip: String, ipB: ByteArray): UdpDevice {
        val buf = ByteArray(64)
        if (ipB.size == 4) {
            buf[0] = ipB[0]
            buf[1] = ipB[1]
            buf[2] = ipB[2]
            buf[3] = ipB[3]
        }
        return try {
            UdpDevice(buf).apply {
                setIpAddress(ipB)
                setMask(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 0))
                setGateway(byteArrayOf(ipB[0], ipB[1], ipB[2], 1))
                setMacAddress(byteArrayOf(0, 0, 0, 0, 0, 0))
                setDhcp(false)
            }
        } catch (_: Exception) {
            UdpDevice(ByteArray(64))
        }
    }

    private fun foundLabel(d: UdpDevice): String {
        return try {
            val ip = safeIpStr(d)
            val mac = safeMacStr(d)
            val isDhcp = try { d.isDhcp } catch (_: Exception) { false }
            if (mac.isNotEmpty()) {
                "$ip  [$mac]" + if (isDhcp) "  DHCP" else ""
            } else {
                "$ip  [LAN Port 9100]"
            }
        } catch (_: Exception) {
            "Máy in LAN"
        }
    }

    private fun pageNetwork() = showPage("Tìm máy in LAN & Đổi IP", false) { b ->
        val pn0 = phoneNet()
        b.addView(
            label(
                if (pn0 != null) "Điện thoại: ${ipStr(pn0.ip)}, gateway ${ipStr(pn0.gw)}"
                else "Không đọc được WiFi điện thoại, hãy bật WiFi."
            )
        )

        val foundAdapter = ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item, mutableListOf<String>()
        )
        val spFound = Spinner(this)
        spFound.adapter = foundAdapter
        val tvWarn = label("")
        val panel = column()
        val result = column().apply { visibility = View.GONE }

        fun refreshWarn() {
            try {
                val d = found.getOrNull(spFound.selectedItemPosition) ?: return
                val pn = phoneNet()
                val dIp = safeIpStr(d)
                val dIpBytes = d.ipAddress
                tvWarn.text =
                    if (pn != null && dIpBytes != null && dIpBytes.size >= 4 && !sameSubnet(dIpBytes, pn.ip, pn.mask))
                        "⚠ Máy in ($dIp) khác dải điện thoại (${ipStr(pn.ip)}). Nên đổi IP máy in về cùng dải."
                    else "Máy in cùng dải với điện thoại."
            } catch (_: Exception) {
                tvWarn.text = ""
            }
        }

        spFound.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshWarn()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        b.addView(btn("Tìm máy in trong mạng (UDP Broadcast - Xprinter)") {
            found.clear()
            foundAdapter.clear()
            result.visibility = View.GONE
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
                        "Không thấy máy in Xprinter UDP. Bạn có thể dùng nút 'Quét dải IP LAN' bên dưới để quét Zywell/Epson."
                    )
                }
            }, 4000)
        })

        b.addView(btn("Quét dải IP mạng LAN cổng 9100 (Zywell, Epson, Xprinter...)") {
            scanLanPort9100 { ips ->
                try {
                    if (ips.isNotEmpty()) {
                        for (ip in ips) {
                            if (found.none { safeIpStr(it) == ip }) {
                                val ipB = ip4(ip) ?: byteArrayOf(0, 0, 0, 0)
                                val dev = createDummyUdpDevice(ip, ipB)
                                found.add(dev)
                                foundAdapter.add("$ip  [LAN Port 9100]")
                            }
                        }
                        result.visibility = View.VISIBLE
                        refreshWarn()
                        log("Đã quét thấy ${ips.size} máy in mở cổng 9100: ${ips.joinToString(", ")}. Chọn máy in trong danh sách bên dưới.")
                    } else {
                        log("Không tìm thấy địa chỉ IP nào mở cổng 9100 trong dải WiFi hiện tại.")
                    }
                } catch (e: Exception) {
                    log("Lỗi xử lý kết quả quét LAN: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        })

        result.addView(label("Máy in tìm thấy (chọn để sử dụng):", 15f, true))
        result.addView(spFound)
        result.addView(tvWarn)
        result.addView(btn("Dùng máy in này cho kết nối LAN") {
            val d = found.getOrNull(spFound.selectedItemPosition)
            val selectedIp = safeIpStr(d).ifEmpty {
                spFound.selectedItem?.toString()?.split(" ")?.firstOrNull() ?: ""
            }
            if (selectedIp.isEmpty()) return@btn log("Chưa chọn máy in.")
            printerIp = selectedIp
            connectType = POSConnect.DEVICE_TYPE_ETHERNET
            savePrinter()
            log("Đã chọn máy in LAN đang dùng: $printerIp")
        })
        result.addView(label("Tùy chọn đổi IP máy in:", 15f, true))

        val rg = RadioGroup(this)
        val rbAuto = radio("Tự động: về cùng dải điện thoại")
        val rbManual = radio("Nhập tay IP / mask / gateway")
        val rbDhcp = radio("DHCP: router tự cấp IP")
        rg.addView(rbAuto)
        rg.addView(rbManual)
        rg.addView(rbDhcp)

        val autoInfo = label(
            if (pn0 != null)
                "Máy in sẽ nhận IP trống cùng dải ${ipStr(pn0.ip)}, mask ${ipStr(pn0.mask)}, gateway ${ipStr(pn0.gw)}."
            else "Cần kết nối WiFi điện thoại để tự động."
        )
        val manualPanel = column().apply { visibility = View.GONE }
        val etNewIp = edit("IP mới, vd 192.168.1.100", "", IPTYPE)
        val etNewMask = edit("Netmask", "255.255.255.0", IPTYPE)
        val etNewGw = edit("Gateway, vd 192.168.1.1", pn0?.let { ipStr(it.gw) } ?: "", IPTYPE)
        manualPanel.addView(etNewIp)
        manualPanel.addView(row(etNewMask to 1f, etNewGw to 1f))
        val dhcpInfo = label("Máy in tự xin IP từ router. Sau đó bấm 'Tìm máy in' để xem IP mới.")
        dhcpInfo.visibility = View.GONE

        rg.setOnCheckedChangeListener { _, id ->
            autoInfo.visibility = if (id == rbAuto.id) View.VISIBLE else View.GONE
            manualPanel.visibility = if (id == rbManual.id) View.VISIBLE else View.GONE
            dhcpInfo.visibility = if (id == rbDhcp.id) View.VISIBLE else View.GONE
        }
        rg.check(rbAuto.id)

        panel.addView(rg)
        panel.addView(autoInfo)
        panel.addView(manualPanel)
        panel.addView(dhcpInfo)
        panel.addView(btn("Áp dụng đổi IP") {
            val dev = found.getOrNull(spFound.selectedItemPosition)
                ?: return@btn log("Chưa chọn máy in.")
            when (rg.checkedRadioButtonId) {
                rbAuto.id -> {
                    val pn = phoneNet()
                        ?: return@btn log("Không đọc được mạng WiFi điện thoại.")
                    thread {
                        val free = findFreeIp(pn)
                        runOnUiThread {
                            val ipB = free?.let { ip4(it) }
                            if (ipB == null) {
                                log("Không tìm được IP trống, hãy chọn 'Nhập tay'.")
                            } else {
                                confirmChange(dev, ipB, pn.mask, pn.gw, false)
                            }
                        }
                    }
                }
                rbManual.id -> {
                    val ip = ip4(etNewIp.text.toString()) ?: return@btn log("IP mới không hợp lệ")
                    val mask = ip4(etNewMask.text.toString()) ?: return@btn log("Netmask không hợp lệ")
                    val gw = ip4(etNewGw.text.toString()) ?: return@btn log("Gateway không hợp lệ")
                    confirmChange(dev, ip, mask, gw, false)
                }
                rbDhcp.id -> confirmChange(dev, dev.ipAddress, dev.mask, dev.gateway, true)
                else -> log("Hãy chọn một cách đổi IP.")
            }
        })
        result.addView(panel)
        b.addView(result)

        onFoundUi = { d ->
            foundAdapter.add(foundLabel(d))
            result.visibility = View.VISIBLE
            refreshWarn()
        }
        if (found.isNotEmpty()) {
            found.forEach { foundAdapter.add(foundLabel(it)) }
            result.visibility = View.VISIBLE
        }
    }

    private fun onFound(d: UdpDevice) {
        try {
            val dMac = safeMacStr(d)
            val dIp = safeIpStr(d)
            if (dMac.isNotEmpty() && found.any { safeMacStr(it) == dMac }) return
            if (dIp.isNotEmpty() && found.any { safeIpStr(it) == dIp }) return
            found.add(d)
            log("Tìm thấy: IP $dIp" + (if (dMac.isNotEmpty()) ", MAC $dMac" else ""))
            onFoundUi?.invoke(d)
        } catch (e: Exception) {
            log("Lỗi xử lý thiết bị tìm thấy: ${e.message}")
        }
    }

    private fun confirmChange(dev: UdpDevice, ip: ByteArray, mask: ByteArray, gw: ByteArray, dhcp: Boolean) {
        val devMac = safeMacStr(dev)
        val devIp = safeIpStr(dev)
        val msg = if (dhcp) {
            "Máy in ${devMac.ifEmpty { devIp }} sẽ chuyển sang DHCP (router cấp IP)."
        } else {
            (if (devMac.isNotEmpty()) "MAC $devMac\n" else "") +
                    "$devIp → ${ipStr(ip)}\nMask ${ipStr(mask)}\nGateway ${ipStr(gw)}"
        }
        AlertDialog.Builder(this)
            .setTitle("Đổi IP máy in?")
            .setMessage(msg)
            .setPositiveButton("Đổi") { _, _ ->
                thread {
                    try {
                        udp.udpNetConfig(dev.macAddress, ip, mask, gw, dhcp)
                        runOnUiThread {
                            if (!dhcp) {
                                printerIp = ipStr(ip)
                                savePrinter()
                            }
                        }
                        log(
                            "Đã gửi lệnh đổi IP cho ${devMac.ifEmpty { devIp }}. Đợi vài giây (nếu chưa đổi thì tắt " +
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

    // --------------------------------------------- QUÉT DẢI IP MẠNG LAN

    private fun scanLanPort9100(onComplete: (List<String>) -> Unit) {
        val pn = phoneNet()
        if (pn == null) {
            log("Không đọc được dải IP điện thoại. Hãy bật WiFi.")
            onComplete(emptyList())
            return
        }
        val prefix = "${pn.ip[0].toInt() and 0xFF}.${pn.ip[1].toInt() and 0xFF}.${pn.ip[2].toInt() and 0xFF}."
        log("Đang quét siêu tốc 254 địa chỉ IP (${prefix}1 ~ ${prefix}254) cổng 9100...")

        val foundIps = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(30)
        val count = AtomicInteger(254)

        for (i in 1..254) {
            val ip = "$prefix$i"
            executor.execute {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, 9100), 400)
                        foundIps.add(ip)
                        log("✔ Phát hiện máy in mở cổng 9100 tại: $ip")
                    }
                } catch (_: Exception) {
                } finally {
                    if (count.decrementAndGet() == 0) {
                        executor.shutdown()
                        runOnUiThread {
                            onComplete(foundIps.sorted())
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getConnectedSsid(): String? {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo ?: return null
            var ssid = info.ssid ?: return null
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length - 1)
            }
            if (ssid == "<unknown ssid>" || ssid.isEmpty()) null else ssid
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun getNearbyWifiSsids(): List<String> {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.startScan()
            val results = wm.scanResults ?: return emptyList()
            results.map { it.SSID }
                .filter { !it.isNullOrBlank() && it != "<unknown ssid>" }
                .distinct()
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------ TRANG 2: WIFI

    private fun pageWifi() = showPage("Cấu hình WiFi cho máy in", false) { b ->
        b.addView(label("Gửi tên WiFi & Mật khẩu vào máy in qua kết nối: ${connTypeName()}"))
        val status = label("")
        val form = column().apply { visibility = View.GONE }

        b.addView(btn("Kiểm tra kết nối máy in") {
            thread {
                val ok = checkActiveConnection()
                runOnUiThread {
                    status.text = if (ok) "Kết nối OK! Nhập thông tin WiFi bên dưới." else "Không kết nối được tới máy in (${connTypeName()})."
                    form.visibility = if (ok) View.VISIBLE else View.GONE
                }
            }
        })
        b.addView(status)
        b.addView(form)

        form.addView(label("Thông tin WiFi mới:", 15f, true))

        val wifiAdapter = ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item, mutableListOf<String>()
        )
        val spWifi = Spinner(this).apply { adapter = wifiAdapter }
        val etSsidManual = edit("Hoặc gõ tay tên WiFi (SSID)")
        etSsidManual.visibility = View.GONE
        val cbManualSsid = CheckBox(this).apply { text = "Tự gõ tay tên WiFi (nếu WiFi bị ẩn)" }
        cbManualSsid.setOnCheckedChangeListener { _, checked ->
            etSsidManual.visibility = if (checked) View.VISIBLE else View.GONE
            spWifi.visibility = if (checked) View.GONE else View.VISIBLE
        }

        fun refreshWifiList() {
            wifiAdapter.clear()
            val list = mutableListOf<String>()
            val active = getConnectedSsid()
            if (active != null) {
                list.add(active)
                log("Tự động chọn WiFi điện thoại đang kết nối: $active")
            }
            val nearby = getNearbyWifiSsids()
            nearby.forEach { if (!list.contains(it)) list.add(it) }
            if (list.isEmpty()) {
                list.add("Không tìm thấy WiFi - Hãy bấm Quét")
            }
            wifiAdapter.addAll(list)
            if (active != null) etSsidManual.setText(active)
        }
        refreshWifiList()

        form.addView(label("Chọn tên WiFi (SSID) xung quanh hoặc của điện thoại:"))
        form.addView(spWifi)
        form.addView(row(
            btn("Lấy WiFi điện thoại đang nối") {
                val active = getConnectedSsid()
                if (active != null) {
                    val pos = (0 until wifiAdapter.count).firstOrNull { wifiAdapter.getItem(it) == active }
                    if (pos != null) spWifi.setSelection(pos)
                    etSsidManual.setText(active)
                    log("Đã chọn WiFi điện thoại: $active")
                } else {
                    log("Không đọc được WiFi điện thoại. Hãy chắc chắn đã bật WiFi & Vị trí.")
                }
            } to 1f,
            btn("Quét danh sách WiFi") {
                refreshWifiList()
            } to 1f
        ))
        form.addView(cbManualSsid)
        form.addView(etSsidManual)

        val etPass = edit("Mật khẩu WiFi")
        val encAdapter = ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item, encTypes.map { it.first }
        )
        val spEnc = Spinner(this)
        spEnc.adapter = encAdapter

        val cbOwn = CheckBox(this).apply { text = "Nhập IP tĩnh riêng cho WiFi (mặc định giữ IP máy in)" }
        val ownPanel = column().apply { visibility = View.GONE }
        val etIp = edit("IP máy in trên WiFi", "", IPTYPE)
        val etMask = edit("Netmask", "255.255.255.0", IPTYPE)
        val etGw = edit("Gateway", "", IPTYPE)
        ownPanel.addView(etIp)
        ownPanel.addView(row(etMask to 1f, etGw to 1f))
        cbOwn.setOnCheckedChangeListener { _, checked ->
            ownPanel.visibility = if (checked) View.VISIBLE else View.GONE
        }

        form.addView(etPass)
        form.addView(spEnc)
        form.addView(cbOwn)
        form.addView(ownPanel)

        form.addView(btn("Gửi cấu hình WiFi sang máy in") {
            val ssid = if (cbManualSsid.isChecked) {
                etSsidManual.text.toString()
            } else {
                spWifi.selectedItem?.toString() ?: etSsidManual.text.toString()
            }
            if (ssid.isEmpty() || ssid.startsWith("Không tìm thấy")) return@btn log("Tên WiFi (SSID) không hợp lệ")
            val ip: ByteArray
            val mask: ByteArray
            val gw: ByteArray
            if (cbOwn.isChecked) {
                ip = ip4(etIp.text.toString()) ?: return@btn log("IP không hợp lệ")
                mask = ip4(etMask.text.toString()) ?: return@btn log("Netmask không hợp lệ")
                gw = ip4(etGw.text.toString()) ?: return@btn log("Gateway không hợp lệ")
            } else {
                ip = ip4(targetIp()) ?: return@btn log("IP máy in không hợp lệ")
                val pn = phoneNet()
                mask = pn?.mask ?: byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 0)
                gw = pn?.gw ?: byteArrayOf(ip[0], ip[1], ip[2], 1)
            }
            sendWifiConfig(ssid, etPass.text.toString(), encTypes[spEnc.selectedItemPosition].second, ip, mask, gw)
        })
    }

    private fun sendWifiConfig(
        ssid: String, pass: String, enc: Byte,
        ip: ByteArray, mask: ByteArray, gw: ByteArray
    ) {
        log("Đang mở kết nối SDK tới ${connTypeName()} ...")
        val conn = createSdkConnect()
        val listener = IConnectListener { code, _, msg ->
            when (code) {
                POSConnect.CONNECT_SUCCESS -> {
                    log("Đã kết nối SDK. Gửi cấu hình WiFi SSID='$ssid' ...")
                    POSPrinter(conn).wifiConfig(ip, mask, gw, ssid, pass, enc)
                    log("Đã gửi xong! Tắt mở lại máy in để máy in nối vào WiFi mới.")
                    mainHandler.postDelayed({ conn.close() }, 2000)
                }
                POSConnect.CONNECT_FAIL -> log("Kết nối SDK thất bại: $msg")
                else -> log("Trạng thái SDK: code=$code $msg")
            }
        }
        connListener = listener
        connectSdkDevice(conn, listener)
    }

    // ------------------------------------------ TRANG 4: IN MẪU HÓA ĐƠN & QR

    private fun pagePrintReceipt() = showPage("In Hóa đơn & Mã vạch QR", false) { b ->
        b.addView(label("Kết nối hiện tại: ${connTypeName()}", 14f, true))

        b.addView(label("1. In mẫu Hóa đơn bán hàng:", 15f, true))
        b.addView(btn("In mẫu Hóa đơn (Receipt Demo)") {
            sendPrintData("In mẫu hóa đơn", getReceiptDemoBytes())
        })

        b.addView(label("2. In Mã vạch 1D & QR Code:", 15f, true))
        val etBarcode = edit("Nội dung mã vạch / QR Code", "https://xprinter.vn")
        val rgCode = RadioGroup(this)
        val rbQr = radio("QR Code thanh toán")
        val rb1D = radio("Mã vạch 1D (CODE128)")
        rgCode.addView(rbQr)
        rgCode.addView(rb1D)
        rgCode.check(rbQr.id)

        b.addView(etBarcode)
        b.addView(rgCode)
        b.addView(btn("In Mã vạch / QR Code") {
            val content = etBarcode.text.toString()
            if (content.isEmpty()) return@btn log("Nội dung mã vạch không được trống")
            val isQr = rgCode.checkedRadioButtonId == rbQr.id
            sendPrintData(if (isQr) "In QR Code" else "In Barcode 1D", getBarcodeBytes(content, isQr))
        })

        b.addView(label("3. Lệnh máy in ESC/POS:", 15f, true))
        b.addView(row(
            btn("Cắt giấy") { sendPrintData("Cắt giấy", CUT) } to 1f,
            btn("Mở két tiền") { sendPrintData("Mở két", DRAWER) } to 1f
        ))
    }

    // ------------------------------------------ TRANG 5: IN TEM NHÃN TSPL

    private fun pagePrintLabel() = showPage("Máy in Tem nhãn (TSPL)", false) { b ->
        b.addView(label("Dành cho dòng máy in nhãn nhiệt (XP-350B, XP-365B, XP-420B...)", 13f))
        b.addView(label("Kết nối hiện tại: ${connTypeName()}", 14f, true))

        val etTitle = edit("Tên sản phẩm", "TRÀ SỮA TRANH CHÂU 70%")
        val etPrice = edit("Giá tiền / Ghi chú", "Giá: 35.000đ - Size L")
        val etCode = edit("Mã sản phẩm / QR", "SP-888999")

        b.addView(etTitle)
        b.addView(etPrice)
        b.addView(etCode)

        b.addView(btn("In tem nhãn mẫu (50x30mm)") {
            val t = etTitle.text.toString()
            val p = etPrice.text.toString()
            val c = etCode.text.toString()
            sendPrintData("In tem nhãn TSPL", getTsplLabelBytes(t, p, c))
        })
        b.addView(label("Cấu hình chế độ máy in:", 14f, true))
        b.addView(btn("Chuyển máy in sang Chế độ In Tem Nhãn (TSPL)") {
            val switchTspl = bytes(0x1F, 0x1B, 0x1F, 0x54, 0x53, 0x50, 0x4C)
            sendPrintData("Chuyển TSPL Mode", switchTspl)
            log("Đã gửi lệnh chuyển chế độ In Tem (TSPL). Hãy tắt mở lại máy in để áp dụng.")
        })
    }

    // ---------------------------------------------- TRANG 6: CÔNG CỤ NÂNG CAO

    private fun pageAdvanced() = showPage("Công cụ nâng cao & Reset", false) { b ->
        val rg = RadioGroup(this)
        val rbRaw = radio("Gửi gói thô (hex)")
        val rbListen = radio("Nghe UDP")
        val rbMode = radio("Chuyển chế độ máy in (In Tem TSPL ↔ In Bill ESC/POS)")
        val rbReset = radio("Khôi phục cài đặt gốc (Reset Factory)")
        rg.addView(rbRaw)
        rg.addView(rbListen)
        rg.addView(rbMode)
        rg.addView(rbReset)
        b.addView(rg)

        // ---- gói thô
        val rawPanel = column().apply { visibility = View.GONE }
        rawPanel.addView(printerBar())
        val protoAdapter = ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item, listOf("TCP", "UDP")
        )
        val spProto = Spinner(this).apply { adapter = protoAdapter }
        val cbBroadcast = CheckBox(this).apply { text = "Broadcast 255.255.255.255" }
        cbBroadcast.visibility = View.GONE
        spProto.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                cbBroadcast.visibility = if (pos == 1) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val etHex = edit("vd: 1B 40 hoặc 0x1B,0x40")
        etHex.typeface = Typeface.MONOSPACE
        etHex.minLines = 2
        etHex.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        rawPanel.addView(spProto)
        rawPanel.addView(cbBroadcast)
        rawPanel.addView(etHex)
        rawPanel.addView(btn("Gửi gói thô") {
            val data = parseHex(etHex.text.toString())
                ?: return@btn log("Hex không hợp lệ (cần số ký tự chẵn, 0-9 A-F)")
            val port = targetPort() ?: return@btn log("Cổng không hợp lệ")
            if (spProto.selectedItemPosition == 0) {
                sendTcp(targetIp(), port, data, "raw")
            } else {
                val ip = if (cbBroadcast.isChecked) "255.255.255.255" else targetIp()
                sendUdp(ip, port, data, cbBroadcast.isChecked)
            }
        })
        b.addView(rawPanel)

        // ---- nghe UDP
        val listenPanel = column().apply { visibility = View.GONE }
        val etListen = edit("Cổng UDP để nghe, vd 9000", "", InputType.TYPE_CLASS_NUMBER)
        listenPanel.addView(label("Nghe 20 giây, ghi lại mọi gói UDP gửi tới điện thoại."))
        listenPanel.addView(etListen)
        listenPanel.addView(btn("Nghe 20 giây") {
            val port = etListen.text.toString().trim().toIntOrNull()
                ?: return@btn log("Nhập cổng UDP để nghe")
            listenUdp(port)
        })
        b.addView(listenPanel)

        // ---- chuyen che do
        val modePanel = column().apply { visibility = View.GONE }
        modePanel.addView(label("Chuyển chế độ TSPL / ESC/POS (Máy in 2 chế độ):", 13f, true))
        modePanel.addView(label(
            "Đối với máy in 2 chế độ (XP-365B, XP-350B...), cách an toàn và chính xác nhất là chuyển đổi bằng nút bấm cứng trên máy in (giữ nút Feed khi bật nguồn) hoặc sử dụng công cụ Diagnostic Tool chính thức của hãng trên máy tính."
        ))
        b.addView(modePanel)

        // ---- reset factory
        val resetPanel = column().apply { visibility = View.GONE }
        resetPanel.addView(label("Khôi phục cài đặt gốc (Reset Factory):", 13f, true))
        resetPanel.addView(label(
            "Để khôi phục IP và cài đặt mặc định của máy in về nhà sản xuất, vui lòng tắt máy in, giữ nút PAUSE/FEED và bật nguồn lại (theo hướng dẫn của từng model) hoặc dùng công cụ Diagnostic Tool trên PC qua cáp USB."
        ))
        b.addView(resetPanel)

        rg.setOnCheckedChangeListener { _, id ->
            rawPanel.visibility = if (id == rbRaw.id) View.VISIBLE else View.GONE
            listenPanel.visibility = if (id == rbListen.id) View.VISIBLE else View.GONE
            modePanel.visibility = if (id == rbMode.id) View.VISIBLE else View.GONE
            resetPanel.visibility = if (id == rbReset.id) View.VISIBLE else View.GONE
        }
        rg.check(rbRaw.id)
    }

    // ------------------------------------------------ CONNECTION HELPERS

    private fun checkActiveConnection(): Boolean {
        return when (connectType) {
            POSConnect.DEVICE_TYPE_ETHERNET -> tcpCheck(targetIp(), targetPort())
            POSConnect.DEVICE_TYPE_BLUETOOTH -> btMac.isNotEmpty()
            POSConnect.DEVICE_TYPE_USB -> usbPath.isNotEmpty()
            else -> false
        }
    }

    private fun createSdkConnect() = POSConnect.createDevice(connectType)

    private fun connectSdkDevice(conn: net.posprinter.IDeviceConnection, listener: IConnectListener) {
        when (connectType) {
            POSConnect.DEVICE_TYPE_BLUETOOTH -> conn.connect(btMac, listener)
            POSConnect.DEVICE_TYPE_USB -> conn.connect(usbPath, listener)
            else -> conn.connect(targetIp(), listener)
        }
    }

    private fun sendPrintData(label: String, data: ByteArray) {
        if (connectType == POSConnect.DEVICE_TYPE_ETHERNET) {
            sendTcp(targetIp(), targetPort(), data, label)
            return
        }
        log("Kết nối tới máy in ${connTypeName()} để gửi [$label] ...")
        val conn = createSdkConnect()
        val listener = IConnectListener { code, _, msg ->
            when (code) {
                POSConnect.CONNECT_SUCCESS -> {
                    log("Đã kết nối. Gửi dữ liệu [$label] (${data.size} byte)...")
                    conn.sendData(data)
                    log("Gửi OK!")
                    mainHandler.postDelayed({ conn.close() }, 1000)
                }
                POSConnect.CONNECT_FAIL -> log("Kết nối thất bại: $msg")
                else -> log("Trạng thái kết nối: code=$code $msg")
            }
        }
        connListener = listener
        connectSdkDevice(conn, listener)
    }

    // ------------------------------------------------ SAMPLE GENERATORS

    private fun removeAccents(src: String): String {
        val temp = java.text.Normalizer.normalize(src, java.text.Normalizer.Form.NFD)
        val pattern = java.util.regex.Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
        return pattern.matcher(temp).replaceAll("")
            .replace('Đ', 'D')
            .replace('đ', 'd')
    }

    private fun getReceiptDemoBytes(): ByteArray {
        val b = ByteArrayOutputStream()
        fun w(str: String) {
            b.write(removeAccents(str).toByteArray(Charsets.US_ASCII))
        }

        b.write(bytes(0x1B, 0x40)) // ESC @ Init
        b.write(bytes(0x1B, 0x61, 0x01)) // Center
        b.write(bytes(0x1D, 0x21, 0x11)) // Double size
        w("CỬA HÀNG XPTOOL\n")
        b.write(bytes(0x1D, 0x21, 0x00)) // Normal size
        w("ĐC: 123 Đường ABC, Hà Nội\n")
        w("SĐT: 0987.654.321\n")
        w("--------------------------------\n")
        b.write(bytes(0x1B, 0x61, 0x00)) // Left
        w("HÓA ĐƠN BÁN HÀNG #001\n")
        w("Ngày: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}\n")
        w("--------------------------------\n")
        w("Sản phẩm             SL   Thành tiền\n")
        w("1. Máy in Xprinter   1    1.250.000đ\n")
        w("2. Giấy in K80x45    10     150.000đ\n")
        w("3. Tem nhãn 50x30    5      200.000đ\n")
        w("--------------------------------\n")
        b.write(bytes(0x1B, 0x61, 0x02)) // Right
        b.write(bytes(0x1D, 0x21, 0x01)) // Height double
        w("TỔNG TIỀN: 1.600.000đ\n")
        b.write(bytes(0x1D, 0x21, 0x00))
        w("--------------------------------\n")
        b.write(bytes(0x1B, 0x61, 0x01)) // Center
        w("Cảm ơn & Hẹn gặp lại quý khách!\n\n\n")
        b.write(CUT)
        return b.toByteArray()
    }

    private fun getBarcodeBytes(content: String, isQr: Boolean): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(bytes(0x1B, 0x40))
        b.write(bytes(0x1B, 0x61, 0x01)) // Center
        b.write("IN MA VACH THU NGHIEM\n\n".toByteArray(Charsets.US_ASCII))
        if (isQr) {
            val data = content.toByteArray(Charsets.US_ASCII)
            val len = data.size + 3
            val pL = (len and 0xFF).toByte()
            val pH = ((len shr 8) and 0xFF).toByte()
            b.write(byteArrayOf(0x1D, 0x28, 0x6B, pL, pH, 0x31, 0x50, 0x30))
            b.write(data)
            b.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30)) // Print QR
        } else {
            b.write(byteArrayOf(0x1D, 0x68, 0x50)) // Height 80
            b.write(byteArrayOf(0x1D, 0x77, 0x02)) // Width 2
            b.write(byteArrayOf(0x1D, 0x48, 0x02)) // HRI below
            val data = removeAccents(content).toByteArray(Charsets.US_ASCII)
            b.write(byteArrayOf(0x1D, 0x6B, 0x49, data.size.toByte()))
            b.write(data)
        }
        b.write("\n\n\n".toByteArray(Charsets.US_ASCII))
        b.write(CUT)
        return b.toByteArray()
    }

    private fun getTsplLabelBytes(title: String, price: String, code: String): ByteArray {
        val t = removeAccents(title)
        val p = removeAccents(price)
        val c = removeAccents(code)
        val cmd = """
SIZE 50 mm, 30 mm
GAP 2 mm, 0 mm
CLS
TEXT 50,30,"3",0,1,1,"$t"
TEXT 50,70,"2",0,1,1,"$p"
BARCODE 50,110,"128",60,1,0,2,2,"$c"
PRINT 1,1

""".trimIndent()
        return cmd.toByteArray(Charsets.US_ASCII)
    }

    // ------------------------------------------------------------ HELPERS

    private class PhoneNet(val ip: ByteArray, val mask: ByteArray, val gw: ByteArray)

    @Suppress("DEPRECATION")
    private fun phoneNet(): PhoneNet? {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val d = wm.dhcpInfo ?: return null
            fun le(v: Int) = byteArrayOf(
                (v and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v shr 24) and 0xFF).toByte()
            )
            val ip = le(d.ipAddress)
            if (ip.all { it == 0.toByte() }) return null
            var mask = le(d.netmask)
            if (mask.all { it == 0.toByte() }) {
                mask = byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 0)
            }
            PhoneNet(ip, mask, le(d.gateway))
        } catch (e: Exception) {
            null
        }
    }

    private fun sameSubnet(a: ByteArray?, b: ByteArray?, mask: ByteArray?): Boolean {
        if (a == null || b == null || mask == null) return false
        if (a.size < 4 || b.size < 4 || mask.size < 4) return false
        for (i in 0..3) {
            if ((a[i].toInt() and mask[i].toInt()) != (b[i].toInt() and mask[i].toInt())) return false
        }
        return true
    }

    private fun findFreeIp(pn: PhoneNet): String? {
        if (ipStr(pn.mask) != "255.255.255.0") {
            log("Mask ${ipStr(pn.mask)} không phải /24, hãy nhập IP mới thủ công.")
            return null
        }
        val base = "${pn.ip[0].toInt() and 0xFF}.${pn.ip[1].toInt() and 0xFF}.${pn.ip[2].toInt() and 0xFF}."
        val taken = setOf(ipStr(pn.ip), ipStr(pn.gw)) + found.map { safeIpStr(it) }
        for (h in 230 downTo 200) {
            val c = base + h
            if (c in taken) continue
            try {
                if (!InetAddress.getByName(c).isReachable(300)) return c
            } catch (_: Exception) {
            }
        }
        return null
    }

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

    private fun tcpCheck(ip: String, port: Int): Boolean {
        val t0 = System.currentTimeMillis()
        return try {
            Socket().use { it.connect(InetSocketAddress(ip, port), 3000) }
            log("Kết nối TCP $ip:$port OK (${System.currentTimeMillis() - t0} ms)")
            true
        } catch (e: Exception) {
            log("Kết nối TCP $ip:$port THẤT BẠI: ${e.javaClass.simpleName}: ${e.message}")
            false
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

    private fun listenUdp(port: Int) {
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

    private val ESC_INIT = bytes(0x1B, 0x40)
    private val CUT = bytes(0x1D, 0x56, 0x42, 0x00)
    private val DRAWER = bytes(0x1B, 0x70, 0x00, 0x19, 0xFA)
}