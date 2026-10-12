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

internal fun MainActivity.pagePrintReceipt() = showPage("In Hóa đơn & Mã vạch QR", false) { b ->
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


