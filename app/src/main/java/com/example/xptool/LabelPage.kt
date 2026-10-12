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

internal fun MainActivity.pagePrintLabel() = showPage("Máy in Tem nhãn (TSPL)", false) { b ->
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


