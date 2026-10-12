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

internal fun MainActivity.pageAbout() = showPage("Thông tin & Bảo mật", false) { b ->
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


