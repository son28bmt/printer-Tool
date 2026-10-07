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
import net.posprinter.esc.PosUdpNet
import net.posprinter.model.UdpDevice
import net.posprinter.posprinterface.UdpCallback
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    // ----------------------------------------------------- trạng thái chung

    private var printerIp = "192.168.4.2"
    private var printerPort = 9100
    private var etIpCur: EditText? = null
    private var etPortCur: EditText? = null
    private var currentPageIsHome = true

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logBuffer = StringBuilder()
    private var logView: TextView? = null
    private var logScroll: ScrollView? = null

    private val udp = PosUdpNet()
    private val found = mutableListOf<UdpDevice>()
    private var multicastLock: WifiManager.MulticastLock? = null

    // SDK giữ callback bằng WeakReference nên phải giữ tham chiếu mạnh ở đây
    private val udpCallback = UdpCallback { d -> onFound(d) }
    private var connListener: IConnectListener? = null
    private var onFoundUi: ((UdpDevice) -> Unit)? = null

    private val IPTYPE = InputType.TYPE_CLASS_PHONE

    private val encTypes = listOf(
        "WPA2_AES_PSK" to POSConst.ENCRYPT_WPA2_AES_PSK,
        "WPA_TKIP_PSK" to POSConst.ENCRYPT_WPA_TKIP_PSK,
        "WEP64_ASCII" to POSConst.ENCRYPT_WEP64_ASCII,
        "WEP128_ASCII" to POSConst.ENCRYPT_WEP128_ASCII,
        "Không mã hóa" to POSConst.ENCRYPT_NULL
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        POSConnect.init(applicationContext)

        val prefs = getSharedPreferences("xp", MODE_PRIVATE)
        printerIp = prefs.getString("ip", printerIp) ?: printerIp
        printerPort = prefs.getInt("port", printerPort)

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("xptool").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {
        }

        log("Sẵn sàng. Điện thoại phải cùng mạng WiFi/router với máy in.")
        showHome()
    }

    override fun onDestroy() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!currentPageIsHome) showHome() else super.onBackPressed()
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

    // máy in đang dùng: IP/cổng dùng chung giữa các trang
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
            .putString("ip", printerIp).putInt("port", printerPort).apply()
    }

    private fun targetIp() = etIpCur?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: printerIp
    private fun targetPort() = etPortCur?.text?.toString()?.trim()?.toIntOrNull() ?: printerPort

    // ------------------------------------------------------------- TRANG CHỦ

    private fun showHome() = showPage("XP Tool", true) { b ->
        b.addView(label("Máy in đang dùng: $printerIp:$printerPort"))
        b.addView(menuBtn("Tìm máy in & đổi IP", "Tìm trong mạng, đổi IP theo MAC, bật DHCP") { pageNetwork() })
        b.addView(menuBtn("Cấu hình WiFi cho máy in", "Gửi tên WiFi, mật khẩu cho máy in") { pageWifi() })
        b.addView(menuBtn("In thử & lệnh máy in", "Test kết nối, in thử, cắt giấy, mở két") { pageCommands() })
        b.addView(menuBtn("Công cụ nâng cao", "Gửi gói thô (hex), nghe UDP để dò giao thức") { pageAdvanced() })
    }

    // ------------------------------------------- TRANG 1: TÌM MÁY IN & ĐỔI IP

    private fun foundLabel(d: UdpDevice) =
        "${d.ipStr}  [${d.macStr}]" + if (d.isDhcp) "  DHCP" else ""

    private fun pageNetwork() = showPage("Tìm máy in & đổi IP", false) { b ->
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
            val d = found.getOrNull(spFound.selectedItemPosition) ?: return
            val pn = phoneNet()
            tvWarn.text =
                if (pn != null && !sameSubnet(d.ipAddress, pn.ip, pn.mask))
                    "⚠ Máy in (${d.ipStr}) khác dải điện thoại (${ipStr(pn.ip)}). Nên đổi IP máy in về cùng dải."
                else "Máy in cùng dải với điện thoại."
        }

        spFound.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshWarn()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // ---- nút tìm
        b.addView(btn("Tìm máy in trong mạng") {
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
                        "Không thấy máy in nào. Kiểm tra: cùng mạng/router, máy in đã bật và cắm LAN, " +
                            "thử tắt dữ liệu di động."
                    )
                }
            }, 4000)
        })

        // ---- vùng kết quả (chỉ hiện sau khi tìm thấy)
        result.addView(label("Máy in tìm thấy:", 15f, true))
        result.addView(spFound)
        result.addView(tvWarn)
        result.addView(btn("Dùng máy in này (IP hiện tại) cho các chức năng khác") {
            val d = found.getOrNull(spFound.selectedItemPosition) ?: return@btn
            printerIp = d.ipStr
            savePrinter()
            log("Đã chọn máy in đang dùng: ${d.ipStr}")
        })
        result.addView(label("Đổi IP bằng cách nào?", 15f, true))

        // ---- chọn cách đổi, mỗi cách chỉ hiện phần của nó
        val rg = RadioGroup(this)
        val rbAuto = radio("Tự động: về cùng dải điện thoại")
        val rbManual = radio("Nhập tay IP / mask / gateway")
        val rbDhcp = radio("DHCP: router tự cấp IP")
        rg.addView(rbAuto)
        rg.addView(rbManual)
        rg.addView(rbDhcp)

        val autoInfo = label(
            if (pn0 != null)
                "Máy in sẽ nhận IP trống (ước đoán bằng ping) cùng dải ${ipStr(pn0.ip)}, " +
                    "mask ${ipStr(pn0.mask)}, gateway ${ipStr(pn0.gw)}."
            else "Cần WiFi để tự động."
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

        // ---- cập nhật khi tìm thấy máy in mới
        onFoundUi = { d ->
            foundAdapter.add(foundLabel(d))
            result.visibility = View.VISIBLE
            refreshWarn()
        }
        // nếu đã tìm từ trước thì hiện lại
        if (found.isNotEmpty()) {
            found.forEach { foundAdapter.add(foundLabel(it)) }
            result.visibility = View.VISIBLE
        }
    }

    private fun onFound(d: UdpDevice) {
        // callback chạy trên main thread
        if (found.any { it.macStr == d.macStr }) return
        found.add(d)
        log("Tìm thấy: IP ${d.ipStr}, mask ${d.maskStr}, gw ${d.gatewayStr}, MAC ${d.macStr}, DHCP=${d.isDhcp}")
        onFoundUi?.invoke(d)
    }

    private fun confirmChange(dev: UdpDevice, ip: ByteArray, mask: ByteArray, gw: ByteArray, dhcp: Boolean) {
        val msg = if (dhcp) {
            "Máy in ${dev.macStr} sẽ chuyển sang DHCP (router cấp IP)."
        } else {
            "MAC ${dev.macStr}\n${dev.ipStr} → ${ipStr(ip)}\nMask ${ipStr(mask)}\nGateway ${ipStr(gw)}"
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

    // ------------------------------------------------------ TRANG 2: WIFI

    private fun pageWifi() = showPage("WiFi cho máy in", false) { b ->
        b.addView(label("Bước 1: máy in phải kết nối được qua mạng (TCP) thì mới gửi được cấu hình WiFi."))
        b.addView(printerBar())
        val status = label("")
        val form = column().apply { visibility = View.GONE }

        b.addView(btn("Kiểm tra kết nối") {
            val ip = targetIp()
            val port = targetPort() ?: return@btn log("Cổng không hợp lệ")
            thread {
                val ok = tcpCheck(ip, port)
                runOnUiThread {
                    status.text = if (ok) "Kết nối OK, nhập thông tin WiFi bên dưới." else "Không kết nối được $ip:$port"
                    form.visibility = if (ok) View.VISIBLE else View.GONE
                }
            }
        })
        b.addView(status)

        // ---- form (chỉ hiện sau khi kết nối OK)
        form.addView(label("Bước 2: thông tin WiFi", 15f, true))
        val etSsid = edit("Tên WiFi (SSID)")
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
        form.addView(etSsid)
        form.addView(etPass)
        form.addView(spEnc)
        form.addView(cbOwn)
        form.addView(ownPanel)
        form.addView(btn("Gửi cấu hình WiFi") {
            val ssid = etSsid.text.toString()
            if (ssid.isEmpty()) return@btn log("SSID không được trống")
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
            sendWifi(targetIp(), ssid, etPass.text.toString(), encTypes[spEnc.selectedItemPosition].second, ip, mask, gw)
        })
    }

    private fun sendWifi(
        target: String, ssid: String, pass: String, enc: Byte,
        ip: ByteArray, mask: ByteArray, gw: ByteArray
    ) {
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
        connListener = listener
        conn.connect(target, listener)
    }

    // ------------------------------------------------ TRANG 3: LỆNH MÁY IN

    private fun pageCommands() = showPage("In thử & lệnh máy in", false) { b ->
        b.addView(printerBar())
        b.addView(btn("Test kết nối") {
            val port = targetPort() ?: return@btn log("Cổng không hợp lệ")
            val ip = targetIp()
            thread { tcpCheck(ip, port) }
        })
        b.addView(btn("In thử") { escPos("In thử", TEST_PRINT) })
        b.addView(btn("Cắt giấy") { escPos("Cắt giấy", CUT) })
        b.addView(btn("Mở két tiền") { escPos("Mở két", DRAWER) })
        b.addView(btn("Test print (GS ( A, tùy máy)") { escPos("GS ( A", SELF_TEST) })
    }

    // ---------------------------------------------- TRANG 4: CÔNG CỤ NÂNG CAO

    private fun pageAdvanced() = showPage("Công cụ nâng cao", false) { b ->
        b.addView(label("Dùng để dò giao thức. Gửi sai lệnh có thể làm máy in cấu hình lung tung."))
        val rg = RadioGroup(this)
        val rbRaw = radio("Gửi gói thô (hex)")
        val rbListen = radio("Nghe UDP")
        rg.addView(rbRaw)
        rg.addView(rbListen)
        b.addView(rg)

        // ---- gói thô
        val rawPanel = column().apply { visibility = View.GONE }
        rawPanel.addView(printerBar())
        val protoAdapter = ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item, listOf("TCP", "UDP")
        )
        val spProto = Spinner(this)
        spProto.adapter = protoAdapter
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
        rawPanel.addView(btn("Gửi") {
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
        listenPanel.addView(label("Nghe 20 giây, ghi lại mọi gói UDP gửi tới điện thoại. Đừng bấm 'Tìm máy in' lúc đang nghe cổng 9000."))
        listenPanel.addView(etListen)
        listenPanel.addView(btn("Nghe 20 giây") {
            val port = etListen.text.toString().trim().toIntOrNull()
                ?: return@btn log("Nhập cổng UDP để nghe")
            listenUdp(port)
        })
        b.addView(listenPanel)

        rg.setOnCheckedChangeListener { _, id ->
            rawPanel.visibility = if (id == rbRaw.id) View.VISIBLE else View.GONE
            listenPanel.visibility = if (id == rbListen.id) View.VISIBLE else View.GONE
        }
    }

    // ------------------------------------------------------------ helpers

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

    private fun sameSubnet(a: ByteArray, b: ByteArray, mask: ByteArray): Boolean {
        for (i in 0..3) {
            if ((a[i].toInt() and mask[i].toInt()) != (b[i].toInt() and mask[i].toInt())) return false
        }
        return true
    }

    // Tìm 1 IP chưa ai dùng trong dải của điện thoại (kiểm tra bằng ping, chỉ là ước đoán)
    private fun findFreeIp(pn: PhoneNet): String? {
        if (ipStr(pn.mask) != "255.255.255.0") {
            log("Mask ${ipStr(pn.mask)} không phải /24, hãy nhập IP mới thủ công.")
            return null
        }
        val base = "${pn.ip[0].toInt() and 0xFF}.${pn.ip[1].toInt() and 0xFF}.${pn.ip[2].toInt() and 0xFF}."
        val taken = setOf(ipStr(pn.ip), ipStr(pn.gw)) + found.map { it.ipStr }
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

    // ------------------------------------------------------ mạng / gửi lệnh

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

    private fun escPos(label: String, payload: ByteArray) {
        val port = targetPort() ?: return log("Cổng không hợp lệ")
        sendTcp(targetIp(), port, payload, label)
    }

    private fun sendTcp(ip: String, port: Int, data: ByteArray, label: String) {
        thread {
            try {
                log("Gửi TCP $label tới $ip:$port ...")
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), 3000)
                    s.getOutputStream().write(data)
                    s.getOutputStream().flush()
                }
                log("Gửi OK (${data.size} byte)")
            } catch (e: Exception) {
                log("Gửi TCP lỗi: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun sendUdp(ip: String, port: Int, data: ByteArray, broadcast: Boolean) {
        thread {
            try {
                log("Gửi UDP tới $ip:$port (${data.size} byte)...")
                DatagramSocket().use { s ->
                    if (broadcast) s.broadcast = true
                    val p = DatagramPacket(data, data.size, InetSocketAddress(ip, port))
                    s.send(p)
                }
                log("Gửi UDP OK")
            } catch (e: Exception) {
                log("Gửi UDP lỗi: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun listenUdp(port: Int) {
        thread {
            try {
                DatagramSocket(null).use { s ->
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(port))
                    s.soTimeout = 1000
                    log("Đang nghe UDP cổng $port trong 20 giây...")
                    val end = System.currentTimeMillis() + 20000
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
