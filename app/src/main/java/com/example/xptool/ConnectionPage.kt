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


