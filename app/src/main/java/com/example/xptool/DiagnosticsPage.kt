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

internal fun MainActivity.pageDiagnostics() {
    val activity = this
    showPage("Chẩn đoán kết nối 1 chạm", false) { b ->
        val active = PrinterManager.getActivePrinter()
        val targetName = active?.name ?: "Chưa chọn máy in"
        val targetInfo = active?.let { "${it.ip}:${it.port}" } ?: ""

        b.addView(label("Đang chẩn đoán máy in: $targetName ($targetInfo)", 14f, true))
        b.addView(label("Hệ thống sẽ lần lượt kiểm tra 6 bước theo thứ tự ưu tiên:", 12f))

        val stepsCol = column()
        val stepViews = mutableMapOf<Int, TextView>()

        for (i in 1..6) {
            val tvStep = TextView(this).apply {
                text = "⏳ Bước $i: Chờ kiểm tra..."
                textSize = 13f
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundColor(0xFFEEEEEE.toInt())
            }
            stepViews[i] = tvStep
            stepsCol.addView(tvStep)
            stepsCol.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
            })
        }
        b.addView(stepsCol)

        val resultCard = column().apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        val tvConclusionTitle = label("KẾT LUẬN CHẨN ĐOÁN:", 15f, true)
        val tvConclusion = label("", 14f)
        val btnAction = Button(this).apply {
            styleButton(this, primary = false)
            visibility = View.GONE
            isAllCaps = false
        }

        resultCard.addView(tvConclusionTitle)
        resultCard.addView(tvConclusion)
        resultCard.addView(btnAction)
        b.addView(resultCard)

        val btnStart = btn("🚀 Bắt đầu Chẩn đoán 1 chạm") {
            resultCard.visibility = View.GONE
            for (i in 1..6) {
                stepViews[i]?.text = "⏳ Bước $i: Đang kiểm tra..."
                stepViews[i]?.setBackgroundColor(0xFFFFF9C4.toInt())
            }

            DiagnosticHelper.runDiagnostics(
                context = activity,
                udp = udp,
                targetPrinter = active,
                onStepUpdate = { step ->
                    runOnUiThread {
                        val tv = stepViews[step.stepIndex]
                        if (tv != null) {
                            if (step.isOk) {
                                tv.text = "✔ ${step.title}\n   ${step.message}"
                                tv.setBackgroundColor(0xFFE8F5E9.toInt())
                                tv.setTextColor(0xFF1B5E20.toInt())
                            } else {
                                tv.text = "✘ ${step.title}\n   ${step.message}"
                                tv.setBackgroundColor(0xFFFFEBEE.toInt())
                                tv.setTextColor(0xFFB71C1C.toInt())
                            }
                        }
                    }
                },
                onFinished = { failure ->
                    runOnUiThread {
                        resultCard.visibility = View.VISIBLE
                        if (failure == null) {
                            resultCard.setBackgroundColor(0xFFE8F5E9.toInt())
                            tvConclusion.text = "🎉 Mọi thứ đều ổn! Máy in và mạng WiFi kết nối hoàn hảo."
                            btnAction.visibility = View.VISIBLE
                            btnAction.text = "In thử ngay"
                            btnAction.setOnClickListener {
                                sendPrintData("In thử sau chẩn đoán", getReceiptDemoBytes())
                            }
                        } else {
                            resultCard.setBackgroundColor(0xFFFFEBEE.toInt())
                            tvConclusion.text = "⚠ Phát hiện sự cố tại ${failure.title}:\n\n${failure.message}"

                            if (failure.actionType == DiagnosticActionType.OPEN_WIFI_SETTINGS) {
                                btnAction.visibility = View.VISIBLE
                                btnAction.text = "Mở Cài đặt WiFi"
                                btnAction.setOnClickListener {
                                    try {
                                        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                    } catch (_: Exception) {
                                    }
                                }
                            } else if (failure.actionType == DiagnosticActionType.CHANGE_PRINTER_IP) {
                                btnAction.visibility = View.VISIBLE
                                btnAction.text = "Đổi IP máy in về cùng dải WiFi"
                                btnAction.setOnClickListener {
                                    pageNetwork()
                                }
                            } else {
                                btnAction.visibility = View.GONE
                            }
                        }
                    }
                }
            )
        }
        b.addView(btnStart)
    }
}


