package com.example.xptool

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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

    internal var connectType = POSConnect.DEVICE_TYPE_ETHERNET // 1: ETHERNET, 2: BLUETOOTH, 3: USB
    internal var printerIp = "192.168.4.2"
    internal var printerPort = 9100
    internal var btMac = ""
    internal var usbPath = ""
    internal var paperWidth = 58

    internal var etIpCur: EditText? = null
    internal var etPortCur: EditText? = null
    private var currentPageIsHome = true

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    internal val mainHandler = Handler(Looper.getMainLooper())

    private val logBuffer = StringBuilder()
    private var logView: TextView? = null
    private var logScroll: ScrollView? = null

    internal val udp = PosUdpNet()
    internal val found = Collections.synchronizedList(mutableListOf<UdpDevice>())
    private var multicastLock: WifiManager.MulticastLock? = null

    internal val udpCallback = UdpCallback { d -> onFound(d) }
    internal var connListener: IConnectListener? = null
    internal var onFoundUi: ((UdpDevice) -> Unit)? = null

    internal val IPTYPE = InputType.TYPE_CLASS_PHONE

    internal val encTypes = listOf(
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
        PrinterStore.init(applicationContext)
        PrinterManager.init(applicationContext)

        val active = PrinterManager.getActivePrinter()
        if (active != null) {
            printerIp = active.ip
            printerPort = active.port
            connectType = active.connectType
            btMac = active.btMac
            usbPath = active.usbPath
            paperWidth = active.paperWidth
        } else {
            val prefs = getSharedPreferences("xp", MODE_PRIVATE)
            printerIp = prefs.getString("ip", printerIp) ?: printerIp
            printerPort = prefs.getInt("port", printerPort)
            btMac = prefs.getString("btMac", "") ?: ""
            usbPath = prefs.getString("usbPath", "") ?: ""
            connectType = prefs.getInt("connType", POSConnect.DEVICE_TYPE_ETHERNET)
        }

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

    override fun onResume() {
        super.onResume()
        if (currentPageIsHome && PrinterManager.savedPrinters.isNotEmpty()) {
            showHome()
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(autoRefreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoRefreshRunnable)
        InfoTestPage.stopStressTest()
        try {
            udp.closeNetSocket()
            multicastLock?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        InfoTestPage.stopStressTest()
        if (!currentPageIsHome) showHome() else super.onBackPressed()
    }

    internal fun checkBtPermission() {
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

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val colorBackground = Color.rgb(246, 249, 246)
    private val colorSurface = Color.WHITE
    private val colorText = Color.rgb(20, 27, 22)
    private val colorMuted = Color.rgb(99, 112, 103)
    private val colorAccent = Color.rgb(31, 123, 74)
    private val colorAccentSoft = Color.rgb(232, 244, 235)
    private val colorBorder = Color.rgb(222, 231, 223)

    private fun roundedBackground(color: Int, radius: Int = 14, strokeColor: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun rippleForeground(radius: Int = 12) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(28, 31, 123, 74)),
        null,
        roundedBackground(Color.WHITE, radius)
    )

    fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        dividerDrawable = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setSize(1, dp(6))
        }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }

    fun label(t: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(colorText)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, dp(6))
        setLineSpacing(dp(1).toFloat(), 1f)
    }

    fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text.uppercase(Locale.getDefault())
        textSize = 11f
        letterSpacing = 0.1f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorAccent)
        setPadding(0, dp(10), 0, dp(4))
    }

    fun edit(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = type
            textSize = 15f
            setTextColor(colorText)
            setHintTextColor(colorMuted)
            background = roundedBackground(colorSurface, 12, colorBorder)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(48)
        }

    fun styleButton(button: Button, primary: Boolean = true, danger: Boolean = false): Button = button.apply {
        isAllCaps = false
        textSize = 13f
        minHeight = dp(42)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        backgroundTintList = null
        val fill = when {
            danger -> Color.rgb(255, 242, 240)
            primary -> colorAccent
            else -> colorSurface
        }
        val stroke = when {
            danger -> Color.rgb(232, 192, 187)
            primary -> null
            else -> colorBorder
        }
        background = roundedBackground(fill, 12, stroke)
        foreground = rippleForeground()
        setTextColor(when {
            danger -> Color.rgb(166, 48, 39)
            primary -> Color.WHITE
            else -> colorText
        })
    }

    fun <T : View> styleCard(view: T, selected: Boolean = false): T = view.apply {
        background = roundedBackground(
            if (selected) colorAccentSoft else colorSurface,
            16,
            if (selected) Color.rgb(177, 211, 185) else colorBorder
        )
        elevation = dp(1).toFloat()
    }

    fun btn(text: String, onClick: () -> Unit) = styleButton(Button(this)).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    fun menuBtn(title: String, sub: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = roundedBackground(colorSurface, strokeColor = colorBorder)
        elevation = dp(1).toFloat()
        foreground = rippleForeground(14)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }

        val textColumn = column()
        textColumn.addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorText)
        })
        textColumn.addView(TextView(this@MainActivity).apply {
            text = sub
            textSize = 12f
            setTextColor(colorMuted)
            setPadding(0, dp(3), 0, 0)
        })
        addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = "→"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
        })
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    fun row(vararg items: Pair<View, Float>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((v, w) in items) {
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w).apply {
                marginEnd = dp(6)
            })
        }
    }

    fun radio(text: String) = RadioButton(this).apply {
        this.text = text
        id = View.generateViewId()
        buttonTintList = ColorStateList.valueOf(colorAccent)
        setTextColor(colorText)
    }

    internal fun showPage(title: String, isHome: Boolean, build: (LinearLayout) -> Unit) {
        syncPrinterFields()
        onFoundUi = null
        currentPageIsHome = isHome
        if (!isHome) {
            mainHandler.removeCallbacks(autoRefreshRunnable)
        }
        window.statusBarColor = colorBackground
        window.navigationBarColor = colorBackground
        var systemUiFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemUiFlags = systemUiFlags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = systemUiFlags

        val root = column().apply {
            setPadding(dp(20), dp(12), dp(20), dp(14))
            setBackgroundColor(colorBackground)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        if (isHome) {
            val brandMark = TextView(this).apply {
                text = "XP"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedBackground(colorText, 12)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
            }
            header.addView(brandMark)
            val brandText = column()
            brandText.addView(TextView(this).apply {
                text = "XP TOOL"
                textSize = 16f
                letterSpacing = 0.08f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorText)
            })
            brandText.addView(TextView(this).apply {
                text = "PRINT MANAGEMENT"
                textSize = 10f
                letterSpacing = 0.12f
                setTextColor(colorMuted)
            })
            header.addView(brandText)
        } else {
            header.addView(styleButton(Button(this), primary = false).apply {
                text = "‹"
                textSize = 28f
                setTextColor(colorAccent)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) }
                setOnClickListener { showHome() }
            })
            header.addView(label(title, 18f, true))
        }
        root.addView(header)

        val body = column()
        body.setPadding(0, dp(4), 0, dp(10))
        build(body)
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(body)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(
            logBox(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(122))
        )
        setContentView(root)
    }

    private fun logBox(): LinearLayout {
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            text = logBuffer.toString()
            setTextIsSelectable(true)
            setTextColor(colorMuted)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val sv = ScrollView(this).apply {
            addView(tv)
        }
        val box = column().apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(colorSurface, 14, colorBorder)
        }
        box.addView(TextView(this).apply {
            text = "NHẬT KÝ HOẠT ĐỘNG"
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
        })
        box.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        logView = tv
        logScroll = sv
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        return box
    }

    fun log(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg\n"
        runOnUiThread {
            logBuffer.append(line)
            logView?.append(line)
            logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    internal fun printerBar(): View {
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

    internal fun savePrinter() {
        getSharedPreferences("xp", MODE_PRIVATE).edit()
            .putString("ip", printerIp)
            .putInt("port", printerPort)
            .putString("btMac", btMac)
            .putString("usbPath", usbPath)
            .putInt("connType", connectType)
            .apply()
    }

    internal fun targetIp() = etIpCur?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: printerIp
    internal fun targetPort() = etPortCur?.text?.toString()?.trim()?.toIntOrNull() ?: printerPort

    // ------------------------------------------------------------- TRANG CHỦ

    internal fun connTypeName() = when (connectType) {
        POSConnect.DEVICE_TYPE_BLUETOOTH -> "Bluetooth ($btMac)"
        POSConnect.DEVICE_TYPE_USB -> "USB ($usbPath)"
        else -> "LAN/WiFi ($printerIp:$printerPort)"
    }

    private var isRefreshingHomeStatus = false

    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (currentPageIsHome && PrinterManager.savedPrinters.isNotEmpty()) {
                triggerAutoRefreshStatus()
            }
        }
    }

    private fun triggerAutoRefreshStatus() {
        if (isRefreshingHomeStatus) return
        isRefreshingHomeStatus = true
        PrinterManager.refreshAllStatuses(this@MainActivity, udp) {
            runOnUiThread {
                isRefreshingHomeStatus = false
                if (currentPageIsHome) {
                    showHome(skipAutoRefreshTrigger = true)
                }
            }
        }
    }

    fun applySavedPrinter(p: SavedPrinter) {
        printerIp = p.lastIp
        printerPort = p.port
        connectType = p.connType
        btMac = p.btMac
        usbPath = p.usbPath
        paperWidth = p.paperWidth
        savePrinter()
    }

    private fun pageManagePrinters() = showPage("Máy in của tôi", false) { b ->
        ManagePrintersPage.buildView(this, b, { label, data -> sendPrintData(label, data) }, { msg -> log(msg) })
    }

    private fun showHome(skipAutoRefreshTrigger: Boolean = false) {
        showPage("XP Tool by QuangSonAIBAT", true) { b ->
        val active = PrinterManager.getActivePrinter()
        if (active != null) {
            printerIp = active.ip
            printerPort = active.port
            connectType = active.connectType
            btMac = active.btMac
            usbPath = active.usbPath
            paperWidth = active.paperWidth
        }

        // --- DASHBOARD MÁY IN CỦA TÔI
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val titleColumn = column()
        titleColumn.addView(TextView(this).apply {
            text = "MÁY IN CỦA TÔI"
            textSize = 11f
            letterSpacing = 0.1f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
        })
        titleColumn.addView(TextView(this).apply {
            text = "${PrinterManager.savedPrinters.size} thiết bị đã lưu"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorText)
        })
        val btnRefresh = styleButton(Button(this), primary = false).apply {
            text = "Làm mới"
            textSize = 12f
            setOnClickListener {
                triggerAutoRefreshStatus()
            }
        }
        headerRow.addView(titleColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(btnRefresh, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        b.addView(headerRow)

        if (PrinterManager.savedPrinters.isEmpty()) {
            val emptyCard = column().apply {
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = roundedBackground(colorSurface, 16, colorBorder)
            }
            emptyCard.addView(label("Chưa có máy in nào", 17f, true))
            emptyCard.addView(label("Tìm máy in trong mạng rồi lưu thiết bị để bắt đầu.", 13f).apply {
                setTextColor(colorMuted)
            })
            emptyCard.addView(btn("Tìm máy in") { pageNetwork() })
            b.addView(emptyCard)
        } else {
            val listCol = column()
            PrinterManager.savedPrinters.forEach { p ->
                val card = styleCard(column(), selected = p.id == PrinterManager.activePrinterId).apply {
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                val dotStr = when (p.status) {
                    PrinterStatus.ONLINE -> "● Online"
                    PrinterStatus.OFFLINE -> "○ Offline"
                    PrinterStatus.CHECKING -> "◌ Đang kiểm tra..."
                }
                val dotColor = when (p.status) {
                    PrinterStatus.ONLINE -> 0xFF2E7D32.toInt()
                    PrinterStatus.OFFLINE -> 0xFFC62828.toInt()
                    PrinterStatus.CHECKING -> 0xFFF57F17.toInt()
                }

                val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val tvName = TextView(this).apply {
                    text = "${p.name} ${if (p.id == PrinterManager.activePrinterId) "[ĐANG DÙNG]" else ""}"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (p.id == PrinterManager.activePrinterId) colorAccent else colorText)
                }
                val tvStatus = TextView(this).apply {
                    text = dotStr
                    textSize = 13f
                    setTextColor(dotColor)
                    typeface = Typeface.DEFAULT_BOLD
                }
                row1.addView(tvName, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row1.addView(tvStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                val subText = if (p.connectType == POSConnect.DEVICE_TYPE_ETHERNET) {
                    "IP: ${p.ip}:${p.port}" + (if (!p.mac.isNullOrEmpty()) " | MAC: ${p.mac}" else " | Cổng 9100") + " | Khổ giấy: ${p.paperWidth}mm"
                } else if (p.connectType == POSConnect.DEVICE_TYPE_BLUETOOTH) {
                    "Bluetooth MAC: ${p.btMac}"
                } else {
                    "USB OTG: ${p.usbPath}"
                }
                val tvSub = TextView(this).apply {
                    text = subText
                    textSize = 12f
                    setTextColor(colorMuted)
                }

                val actionRows = column()
                val primaryActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val secondaryActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val btnUse = styleButton(Button(this)).apply {
                    text = "Dùng máy này"
                    textSize = 12f
                    setOnClickListener {
                        PrinterManager.setActivePrinter(this@MainActivity, p.id)
                        printerIp = p.ip
                        printerPort = p.port
                        paperWidth = p.paperWidth
                        connectType = p.connectType
                        btMac = p.btMac
                        usbPath = p.usbPath
                        savePrinter()
                        log("Đã chọn máy in đang dùng: ${p.name} (${p.ip})")
                        showHome()
                    }
                }
                val btnTest = styleButton(Button(this), primary = false).apply {
                    text = "In thử"
                    textSize = 12f
                    setOnClickListener {
                        PrinterManager.setActivePrinter(this@MainActivity, p.id)
                        sendPrintData("In thử ${p.name}", getReceiptDemoBytes())
                    }
                }
                val btnPaper = styleButton(Button(this), primary = false).apply {
                    text = "Khổ ${p.paperWidth}mm"
                    textSize = 12f
                    setOnClickListener {
                        p.paperWidth = if (p.paperWidth == 58) 80 else 58
                        if (p.id == PrinterManager.activePrinterId) {
                            paperWidth = p.paperWidth
                        }
                        PrinterManager.saveAll(this@MainActivity)
                        savePrinter()
                        log("Đã chuyển khổ giấy máy in ${p.name} sang ${p.paperWidth}mm")
                        showHome()
                    }
                }
                val btnRename = styleButton(Button(this), primary = false).apply {
                    text = "Đổi tên"
                    textSize = 12f
                    setOnClickListener {
                        val etName = EditText(this@MainActivity).apply { setText(p.name) }
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Đổi tên máy in")
                            .setView(etName)
                            .setPositiveButton("Lưu") { _, _ ->
                                val newName = etName.text.toString().trim()
                                if (newName.isNotEmpty()) {
                                    p.name = newName
                                    PrinterManager.saveAll(this@MainActivity)
                                    showHome()
                                }
                            }
                            .setNegativeButton("Hủy", null)
                            .show()
                    }
                }
                val btnDel = styleButton(Button(this), primary = false, danger = true).apply {
                    text = "Xóa"
                    textSize = 12f
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Xóa máy in")
                            .setMessage("Xóa ${p.name} khỏi danh sách?")
                            .setPositiveButton("Xóa") { _, _ ->
                                PrinterManager.removePrinter(this@MainActivity, p.id)
                                showHome()
                            }
                            .setNegativeButton("Hủy", null)
                            .show()
                    }
                }

                fun addAction(row: LinearLayout, button: Button) {
                    row.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        marginEnd = dp(6)
                    })
                }
                addAction(primaryActions, btnUse)
                addAction(primaryActions, btnTest)
                addAction(primaryActions, btnPaper)
                addAction(secondaryActions, btnRename)
                addAction(secondaryActions, btnDel)
                actionRows.addView(primaryActions)
                actionRows.addView(secondaryActions)

                card.addView(row1)
                card.addView(tvSub)
                card.addView(actionRows)
                listCol.addView(card)

                listCol.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
                })
            }
            b.addView(listCol)
        }

        b.addView(sectionTitle("Kết nối máy in"))
        b.addView(menuBtn("🔍 Chẩn đoán kết nối 1 chạm", "Tự động kiểm tra WiFi, Router, IP máy in, nắp máy & nạp giấy") { pageDiagnostics() })
        b.addView(menuBtn("0. Quản lý Máy in của tôi", "Xem danh sách, thêm theo IP, đổi tên, xóa, đổi khổ giấy") { pageManagePrinters() })
        b.addView(menuBtn("1. Chọn giao tiếp kết nối", "Chuyển giữa Mạng LAN/WiFi, Bluetooth và USB OTG") { pageSelectConnection() })
        b.addView(menuBtn("2. Tìm máy in LAN & Đổi IP", "Dò UDP broadcast, quét dải IP 9100, lưu máy in") { pageNetwork() })

        b.addView(sectionTitle("Thiết lập và in"))
        b.addView(menuBtn("3. Cấu hình WiFi cho máy in", "Gửi Tên WiFi & Mật khẩu vào máy in") { pageWifi() })
        b.addView(menuBtn("4. In mẫu Hóa đơn & Mã vạch QR", "In Receipt Demo, mã vạch 1D, QR Code thanh toán") { pagePrintReceipt() })
        b.addView(menuBtn("5. Máy in Tem nhãn (TSPL)", "Dành cho máy in tem XP-350B, 365B, 420B...") { pagePrintLabel() })
        b.addView(menuBtn("9. Cài đặt máy in (Hãng Xprinter)", "Density, Width, Beep, Alarm, Replay, Restore factory") { pagePrinterSettings() })

        b.addView(sectionTitle("Chia sẻ và tự động hóa"))
        b.addView(menuBtn("10. Cầu nối in qua mạng (Print Bridge)", "Chia sẻ máy in USB/Bluetooth qua cổng 9100") { pagePrintBridge() })
        b.addView(menuBtn("11. Tự động in từ thông báo (Giai đoạn 5)", "Tự động in đơn Grab, ShopeeFood, Bank... khi có thông báo") { pageNotificationPrint() })

        b.addView(sectionTitle("Tiện ích"))
        b.addView(menuBtn("6. Công cụ nâng cao", "Gửi gói Hex thô, nghe UDP") { pageAdvanced() })
        b.addView(menuBtn("8. Thông tin & thử máy", "Đọc serial, kiểm tra nắp/giấy, đổi PIN BT, in thử stress test") { pageInfoTest() })
        b.addView(menuBtn("7. Thông tin ứng dụng & Bảo mật", "Chính sách bảo mật Privacy Policy, tác giả QuangSonAIBAT") { pageAbout() })
    }

        mainHandler.removeCallbacks(autoRefreshRunnable)
        if (PrinterManager.savedPrinters.isNotEmpty()) {
            if (!skipAutoRefreshTrigger) {
                triggerAutoRefreshStatus()
            }
            mainHandler.postDelayed(autoRefreshRunnable, 15000)
        }
    }

    private fun pageInfoTest() {
        showPage("8. Thông tin & thử máy", false) { body ->
            InfoTestPage.buildView(this, body) { msg -> log(msg) }
        }
    }

    private fun pagePrinterSettings() {
        showPage("9. Cài đặt máy in", false) { body ->
            PrinterSettingsPage.buildView(this, body, { label, data -> sendPrintData(label, data) }, { msg -> log(msg) })
        }
    }

    private fun pagePrintBridge() {
        showPage("10. Cầu nối in (Print Bridge)", false) { body ->
            PrintBridgePage.buildView(this, body) { msg -> log(msg) }
        }
    }

    private fun pageNotificationPrint() {
        showPage("11. Tự động in từ thông báo", false) { body ->
            NotificationPrintPage.buildView(this, body) { msg -> log(msg) }
        }
    }

    // ---------------------------------------- TRANG KẾT NỐI: LAN / BT / USB

    // ------------------------------------------- TRANG 1: TÌM MÁY IN & ĐỔI IP

    internal fun safeIpStr(d: UdpDevice?): String {
        if (d == null) return ""
        return try {
            val ip = d.ipAddress
            if (ip != null && ip.size >= 4) ipStr(ip)
            else d.ipStr ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    internal fun safeMacStr(d: UdpDevice?): String {
        if (d == null) return ""
        return try {
            val mac = d.macStr ?: ""
            if (mac == "00:00:00:00:00:00") "" else mac
        } catch (_: Exception) {
            ""
        }
    }

    internal fun createDummyUdpDevice(ip: String, ipB: ByteArray): UdpDevice {
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

    internal fun foundLabel(d: UdpDevice): String {
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

    internal fun onFound(d: UdpDevice) {
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

    internal fun confirmChange(dev: UdpDevice, ip: ByteArray, mask: ByteArray, gw: ByteArray, dhcp: Boolean) {
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

    internal fun scanLanPort9100(onComplete: (List<String>) -> Unit) {
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
    internal fun getConnectedSsid(): String? {
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
    internal fun getNearbyWifiSsids(): List<String> {
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

    internal fun sendWifiConfig(
        ssid: String, pass: String, enc: Byte,
        ip: ByteArray, mask: ByteArray, gw: ByteArray
    ) {
        val active = if (connectType == POSConnect.DEVICE_TYPE_ETHERNET) PrinterStore.getActivePrinter() else null
        if (active != null) {
            log("Đang xác thực kết nối máy in [${active.name}] (${active.lastIp}) trước khi gửi cấu hình WiFi...")
            PrinterStore.resolvePrinter(
                active,
                onResolved = { resolvedIp, viaBroadcastUpdate ->
                    runOnUiThread {
                        printerIp = resolvedIp
                        if (viaBroadcastUpdate) {
                            log("IP máy in đã tự đổi: ${active.lastIp} ➔ $resolvedIp")
                        }
                        doConnectAndSendWifiConfig(ssid, pass, enc, ip, mask, gw)
                    }
                },
                onFailed = { reason ->
                    log("Cấu hình WiFi thất bại: $reason")
                }
            )
        } else {
            doConnectAndSendWifiConfig(ssid, pass, enc, ip, mask, gw)
        }
    }

    internal fun doConnectAndSendWifiConfig(
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

    // ------------------------------------------ TRANG 5: IN TEM NHÃN TSPL

    // ---------------------------------------------- TRANG 6: CÔNG CỤ NÂNG CAO

    // ------------------------------------------------ CONNECTION HELPERS

    internal fun checkActiveConnection(): Boolean {
        return when (connectType) {
            POSConnect.DEVICE_TYPE_ETHERNET -> tcpCheck(targetIp(), targetPort())
            POSConnect.DEVICE_TYPE_BLUETOOTH -> btMac.isNotEmpty()
            POSConnect.DEVICE_TYPE_USB -> usbPath.isNotEmpty()
            else -> false
        }
    }

    internal fun createSdkConnect() = POSConnect.createDevice(connectType)

    internal fun connectSdkDevice(conn: net.posprinter.IDeviceConnection, listener: IConnectListener) {
        when (connectType) {
            POSConnect.DEVICE_TYPE_BLUETOOTH -> conn.connect(btMac, listener)
            POSConnect.DEVICE_TYPE_USB -> conn.connect(usbPath, listener)
            else -> conn.connect(targetIp(), listener)
        }
    }

    fun sendPrintData(label: String, data: ByteArray) {
        if (connectType == POSConnect.DEVICE_TYPE_ETHERNET) {
            val active = PrinterStore.getActivePrinter()
            val printerKey = if (active != null) active.id else "manual:${targetIp()}:${targetPort()}"
            val displayName = active?.name ?: targetIp()

            log("Đưa lệnh in [$label] vào hàng đợi cho $displayName (Khóa: $printerKey)...")

            PrintQueue.enqueue(
                printerKey = printerKey,
                job = {
                    val targetIpToSend: String = if (active != null) {
                        val resolved = PrinterStore.resolvePrinterSync(active) { logMsg ->
                            log(logMsg)
                        } ?: return@enqueue false
                        runOnUiThread { printerIp = resolved }
                        resolved
                    } else {
                        targetIp()
                    }

                    val targetPortToSend = targetPort()
                    sendTcpSync(targetIpToSend, targetPortToSend, data, label)
                },
                maxRetry = DEFAULT_MAX_RETRY,
                retryDelaysMs = DEFAULT_RETRY_DELAYS_MS,
                onResult = { success, attempts ->
                    runOnUiThread {
                        if (success) {
                            if (attempts > 1) {
                                log("✅ In [$label] thành công ở lần thử thứ $attempts (sau khi tự động thử lại).")
                            } else {
                                log("✅ In [$label] thành công.")
                            }
                        } else {
                            log("❌ Gửi lệnh in [$label] tới $displayName thất bại sau $attempts lần thử.")
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("In thất bại ($attempts lần thử)")
                                .setMessage(
                                    "Không thể kết nối hoặc gửi dữ liệu tới $displayName sau $attempts lần thử (đã thử lại với độ trễ 1s, 3s).\n\n" +
                                    "Vui lòng kiểm tra cáp mạng, nguồn điện máy in hoặc dải mạng."
                                )
                                .setPositiveButton("Chẩn đoán ngay") { _, _ -> pageDiagnostics() }
                                .setNegativeButton("Đóng", null)
                                .show()
                        }
                    }
                }
            )
            return
        }

        // Bluetooth / USB OTG qua hàng đợi thiết bị phần cứng
        val hardwareKey = if (connectType == POSConnect.DEVICE_TYPE_BLUETOOTH) "bt:$btMac" else "usb:$usbPath"
        log("Đưa lệnh in [$label] vào hàng đợi thiết bị (${connTypeName()})...")

        PrintQueue.enqueue(
            printerKey = hardwareKey,
            job = {
                val sentSuccess = java.util.concurrent.atomic.AtomicBoolean(false)
                val latch = java.util.concurrent.CountDownLatch(1)
                val conn = createSdkConnect()
                val listener = IConnectListener { code, _, msg ->
                    when (code) {
                        POSConnect.CONNECT_SUCCESS -> {
                            log("Đã kết nối SDK. Gửi dữ liệu [$label] (${data.size} byte)...")
                            conn.sendData(data)
                            sentSuccess.set(true)
                            mainHandler.postDelayed({ conn.close() }, 1000)
                            latch.countDown()
                        }
                        POSConnect.CONNECT_FAIL -> {
                            log("Kết nối SDK thất bại: $msg")
                            try { conn.close() } catch (_: Exception) {}
                            latch.countDown()
                        }
                        else -> {
                            log("Trạng thái SDK: code=$code $msg")
                        }
                    }
                }
                connListener = listener
                connectSdkDevice(conn, listener)
                try {
                    latch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                }
                sentSuccess.get()
            },
            maxRetry = 1,
            retryDelaysMs = listOf(1500L),
            onResult = { success, attempts ->
                runOnUiThread {
                    if (success) {
                        log("✅ Gửi [$label] qua ${connTypeName()} thành công.")
                    } else {
                        log("❌ Gửi [$label] qua ${connTypeName()} thất bại sau $attempts lần thử.")
                    }
                }
            }
        )
    }
    // ------------------------------------------------ SAMPLE GENERATORS

    internal fun removeAccents(src: String): String {
        val temp = java.text.Normalizer.normalize(src, java.text.Normalizer.Form.NFD)
        val pattern = java.util.regex.Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
        return pattern.matcher(temp).replaceAll("")
            .replace('Đ', 'D')
            .replace('đ', 'd')
    }

    fun getReceiptDemoBytes(): ByteArray {
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

    internal fun getBarcodeBytes(content: String, isQr: Boolean): ByteArray {
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

    internal fun getTsplLabelBytes(title: String, price: String, code: String): ByteArray {
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

    internal class PhoneNet(val ip: ByteArray, val mask: ByteArray, val gw: ByteArray)

    @Suppress("DEPRECATION")
    internal fun phoneNet(): PhoneNet? {
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

    internal fun sameSubnet(a: ByteArray?, b: ByteArray?, mask: ByteArray?): Boolean {
        if (a == null || b == null || mask == null) return false
        if (a.size < 4 || b.size < 4 || mask.size < 4) return false
        for (i in 0..3) {
            if ((a[i].toInt() and mask[i].toInt()) != (b[i].toInt() and mask[i].toInt())) return false
        }
        return true
    }

    internal fun findFreeIp(pn: PhoneNet): String? {
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

    internal fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun hex(b: ByteArray, n: Int = b.size) =
        (0 until n).joinToString(" ") { "%02X".format(b[it]) }

    private fun ascii(b: ByteArray, n: Int = b.size) =
        (0 until n).map { b[it].toInt().toChar() }
            .joinToString("") { if (it.code in 32..126) it.toString() else "." }

    internal fun parseHex(s: String): ByteArray? {
        val clean = s.replace("0x", "", ignoreCase = true).filter {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    internal fun ip4(s: String): ByteArray? {
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

    internal fun ipStr(b: ByteArray) = b.joinToString(".") { (it.toInt() and 0xFF).toString() }

    internal fun tcpCheck(ip: String, port: Int): Boolean {
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

    internal fun sendTcpSync(ip: String, port: Int, data: ByteArray, label: String): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), 3000)
                s.getOutputStream().apply {
                    write(data)
                    flush()
                }
                log("tcp ➔ $ip:$port [$label] ${data.size} byte: ${hex(data)}")
                s.soTimeout = 1500
                try {
                    val buf = ByteArray(1024)
                    val n = s.getInputStream().read(buf)
                    if (n > 0) log("tcp  ${hex(buf, n)}  |${ascii(buf, n)}|")
                } catch (_: SocketTimeoutException) {
                }
            }
            true
        } catch (e: Exception) {
            log("TCP lỗi $ip:$port [$label]: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    internal fun sendTcp(ip: String, port: Int, data: ByteArray, label: String) {
        thread {
            sendTcpSync(ip, port, data, label)
        }
    }
    internal fun sendUdp(ip: String, port: Int, data: ByteArray, broadcast: Boolean) {
        thread {
            try {
                DatagramSocket().use { s ->
                    s.broadcast = broadcast
                    s.soTimeout = 1000
                    s.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), port))
                    log("udp → $ip:$port ${data.size} byte (cổng nguồn ${s.localPort}): ${hex(data)}")
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

    internal fun listenUdp(port: Int) {
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

    internal val ESC_INIT = bytes(0x1B, 0x40)
    internal val CUT = bytes(0x1D, 0x56, 0x42, 0x00)
    internal val DRAWER = bytes(0x1B, 0x70, 0x00, 0x19, 0xFA)
}
