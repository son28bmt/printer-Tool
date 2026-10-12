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

internal fun MainActivity.pageWifi() = showPage("Cấu hình WiFi cho máy in", false) { b ->
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


