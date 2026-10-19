package com.example.xptool

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

internal fun MainActivity.pageSelectConnection() = showPage("Chọn kết nối máy in", false) { b ->
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
        val scanHandler = Handler(Looper.getMainLooper())
        var activeBtAdapter: BluetoothAdapter? = null
        var discoveryReceiver: BroadcastReceiver? = null
        var discoveryTimeout: Runnable? = null

        fun stopBtDiscovery() {
            discoveryTimeout?.let(scanHandler::removeCallbacks)
            discoveryTimeout = null
            discoveryReceiver?.let { receiver ->
                runCatching { unregisterReceiver(receiver) }
            }
            discoveryReceiver = null
            runCatching { activeBtAdapter?.cancelDiscovery() }
            activeBtAdapter = null
        }

        fun refreshBt() {
            stopBtDiscovery()
            btAdapter.clear()
            btDevices.clear()
            val needsPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            } else {
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            }
            if (needsPermission) {
                checkBtPermission()
                log("Hãy cấp quyền Bluetooth/Vị trí rồi nhấn quét lại thiết bị.")
                return
            }
            val bta = BluetoothAdapter.getDefaultAdapter()
            if (bta == null || !bta.isEnabled) {
                log("Bluetooth chưa được bật trên điện thoại.")
                return
            }
            activeBtAdapter = bta
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            @Suppress("DEPRECATION")
                            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                            if (btDevices.none { it.address == device.address }) {
                                btDevices.add(device)
                                btAdapter.add("${device.name ?: "Thiết bị Bluetooth"} [${device.address}]")
                                log("Phát hiện Bluetooth: ${device.name ?: "Không rõ tên"} [${device.address}]")
                            }
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            discoveryTimeout?.let(scanHandler::removeCallbacks)
                            discoveryTimeout = null
                            discoveryReceiver?.let { runCatching { unregisterReceiver(it) } }
                            discoveryReceiver = null
                            activeBtAdapter = null
                            log("Quét Bluetooth xong: tìm thấy ${btDevices.size} thiết bị đang phát hiện được.")
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            discoveryReceiver = receiver
            val timeout = Runnable {
                if (discoveryReceiver === receiver) {
                    stopBtDiscovery()
                    log("Đã dừng quét Bluetooth sau thời gian chờ. Tìm thấy ${btDevices.size} thiết bị.")
                }
            }
            discoveryTimeout = timeout
            scanHandler.postDelayed(timeout, 15_000L)
            val started = runCatching { bta.startDiscovery() }.getOrDefault(false)
            if (!started) {
                stopBtDiscovery()
                log("Không khởi động được quét Bluetooth. Hãy tắt/bật Bluetooth rồi thử lại.")
            } else {
                log("Đang quét thiết bị Bluetooth ở gần trong tối đa 15 giây. Máy in cần bật chế độ có thể tìm thấy.")
            }
        }
        btPanel.addView(label("Thiết bị Bluetooth đang phát hiện được ở gần:"))
        btPanel.addView(label("Bật chế độ ghép đôi/có thể tìm thấy trên máy in. Thiết bị Bluetooth đang ẩn sẽ không xuất hiện khi quét.", 12f).apply { setTextColor(colorMuted) })
        btPanel.addView(spBt)
        btPanel.addView(btn("Quét lại thiết bị Bluetooth gần đây") { refreshBt() })

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
                    val saved = SavedPrinter(
                        name = dev.name?.takeIf { it.isNotBlank() } ?: "Máy in Bluetooth ${dev.address.takeLast(5)}",
                        mac = dev.address,
                        lastIp = printerIp,
                        port = printerPort,
                        connType = POSConnect.DEVICE_TYPE_BLUETOOTH,
                        paperWidth = paperWidth,
                        btMac = dev.address
                    )
                    PrinterStore.saveOrUpdate(saved)
                    PrinterStore.setActivePrinter(saved.id)
                    applySavedPrinter(saved)
                    savePrinter()
                    log("Đã lưu và chọn máy in Bluetooth: ${saved.name} [$btMac]")
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


