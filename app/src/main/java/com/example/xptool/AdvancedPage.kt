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

internal fun MainActivity.pageAdvanced() = showPage("Công cụ nâng cao & Reset", false) { b ->
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
                val active = if (connectType == POSConnect.DEVICE_TYPE_ETHERNET) PrinterStore.getActivePrinter() else null
                if (active != null) {
                    PrinterStore.resolvePrinter(
                        active,
                        onResolved = { resolvedIp, _ ->
                            runOnUiThread { printerIp = resolvedIp }
                            sendTcp(resolvedIp, port, data, "raw")
                        },
                        onFailed = { reason ->
                            log("Gửi gói thô thất bại: $reason")
                        }
                    )
                } else {
                    sendTcp(targetIp(), port, data, "raw")
                }
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


