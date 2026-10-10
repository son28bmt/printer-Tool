package com.example.xptool

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import net.posprinter.POSConnect
import kotlin.concurrent.thread

object ManagePrintersPage {

    fun buildView(
        activity: MainActivity,
        container: LinearLayout,
        sendPrintData: (label: String, data: ByteArray) -> Unit,
        log: (String) -> Unit
    ) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }

        // TiÃªu Ä‘á» & thanh cÃ´ng cá»¥
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }

        val tvTitle = TextView(activity).apply {
            val count = PrinterStore.printers.size
            text = "Danh sÃ¡ch mÃ¡y in ($count)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF212121.toInt())
        }

        val btnRefresh = Button(activity).apply {
            text = "ðŸ”„ LÃ m má»›i"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                log("Äang kiá»ƒm tra láº¡i tráº¡ng thÃ¡i káº¿t ná»‘i cÃ¡c mÃ¡y in...")
                PrinterStore.refreshAllStatuses {
                    activity.runOnUiThread {
                        log("LÃ m má»›i tráº¡ng thÃ¡i hoÃ n táº¥t.")
                        container.removeAllViews()
                        buildView(activity, container, sendPrintData, log)
                    }
                }
            }
        }

        val btnAddManual = Button(activity).apply {
            text = "âž• ThÃªm theo IP"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                showAddManualPrinterDialog(activity) {
                    container.removeAllViews()
                    buildView(activity, container, sendPrintData, log)
                }
            }
        }

        headerRow.addView(tvTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(btnRefresh, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        headerRow.addView(btnAddManual, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(headerRow)

        // HÆ°á»›ng dáº«n giáº£i thÃ­ch dáº£i máº¡ng
        val tvNote = TextView(activity).apply {
            text = "â„¹ï¸ LÆ°u Ã½: MÃ¡y in cÃ³ thá»ƒ lÆ°u theo IP trá»±c tiáº¿p khi á»Ÿ dáº£i máº¡ng khÃ¡c Ä‘Ã£ Ä‘á»‹nh tuyáº¿n. " +
                    "MÃ¡y in cÃ³ Ä‘á»‹a chá»‰ MAC sáº½ tá»± dÃ² láº¡i IP khi bá»‹ Ä‘á»•i; mÃ¡y in thÃªm thá»§ cÃ´ng báº±ng IP sáº½ giá»¯ cá»‘ Ä‘á»‹nh theo IP."
            textSize = 12f
            setTextColor(0xFF555555.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(0xFFF0F4C3.toInt())
        }
        container.addView(tvNote)
        container.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
        })

        val printerList = PrinterStore.loadAll()
        if (printerList.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = "ChÆ°a cÃ³ mÃ¡y in nÃ o trong danh sÃ¡ch.\nBáº¥m nÃºt 'âž• ThÃªm theo IP' á»Ÿ trÃªn hoáº·c vÃ o má»¥c '2. TÃ¬m mÃ¡y in LAN & Äá»•i IP' Ä‘á»ƒ thÃªm."
                textSize = 14f
                setPadding(dp(16), dp(24), dp(16), dp(24))
                gravity = Gravity.CENTER
                setTextColor(0xFF757575.toInt())
            }
            container.addView(tvEmpty)
            return
        }

        val activeKey = PrinterStore.activePrinterKey

        printerList.forEach { p ->
            val isActive = (p.id == activeKey)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(if (isActive) 0xFFE8F5E9.toInt() else 0xFFF5F5F5.toInt())
            }

            // HÃ ng 1: TÃªn mÃ¡y in + Tráº¡ng thÃ¡i
            val row1 = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(activity).apply {
                text = "${p.name} ${if (isActive) "[ÄANG DÃ™NG]" else ""}"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isActive) 0xFF1B5E20.toInt() else 0xFF212121.toInt())
            }

            val statusText: String
            val statusColor: Int
            when (p.lastStatus) {
                "online" -> {
                    statusText = "ðŸŸ¢ Online"
                    statusColor = 0xFF2E7D32.toInt()
                }
                "offline" -> {
                    statusText = "ðŸ”´ Offline"
                    statusColor = 0xFFC62828.toInt()
                }
                else -> {
                    statusText = "ðŸŸ  ChÆ°a kiá»ƒm tra"
                    statusColor = 0xFFF57F17.toInt()
                }
            }

            val tvStatus = TextView(activity).apply {
                text = statusText
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(statusColor)
            }

            row1.addView(tvName, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row1.addView(tvStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            card.addView(row1)

            // DÃ²ng phá»¥: Chi tiáº¿t káº¿t ná»‘i
            val subDetails = when (p.connType) {
                POSConnect.DEVICE_TYPE_ETHERNET -> {
                    val macInfo = if (!p.mac.isNullOrBlank()) "MAC: ${p.mac}" else "Nháº­p tay (KhÃ´ng cÃ³ MAC)"
                    "LAN: ${p.lastIp}:${p.port} | $macInfo | Khá»•: ${p.paperWidth}mm"
                }
                POSConnect.DEVICE_TYPE_BLUETOOTH -> "Bluetooth: ${p.btMac} | Khá»•: ${p.paperWidth}mm"
                POSConnect.DEVICE_TYPE_USB -> "USB OTG: ${p.usbPath} | Khá»•: ${p.paperWidth}mm"
                else -> "Giao tiáº¿p khÃ¡c | Khá»•: ${p.paperWidth}mm"
            }

            val tvSub = TextView(activity).apply {
                text = subDetails
                textSize = 12f
                setTextColor(0xFF616161.toInt())
                setPadding(0, dp(2), 0, dp(6))
            }
            card.addView(tvSub)

            // HÃ ng nÃºt thao tÃ¡c
            val rowBtns = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val btnUse = Button(activity).apply {
                text = if (isActive) "Äang chá»n" else "DÃ¹ng mÃ¡y nÃ y"
                textSize = 11f
                isAllCaps = false
                isEnabled = !isActive
                setOnClickListener {
                    PrinterStore.setActivePrinter(p.id)
                    activity.applySavedPrinter(p)
                    log("ÄÃ£ chá»n mÃ¡y in Ä‘ang dÃ¹ng: ${p.name} (${p.lastIp})")
                    container.removeAllViews()
                    buildView(activity, container, sendPrintData, log)
                }
            }

            val btnPaper = Button(activity).apply {
                text = "Khá»• ${p.paperWidth}mm"
                textSize = 11f
                isAllCaps = false
                setOnClickListener {
                    p.paperWidth = if (p.paperWidth == 58) 80 else 58
                    PrinterStore.saveOrUpdate(p)
                    if (isActive) activity.applySavedPrinter(p)
                    log("ÄÃ£ chuyá»ƒn khá»• giáº¥y mÃ¡y in [${p.name}] sang ${p.paperWidth}mm")
                    container.removeAllViews()
                    buildView(activity, container, sendPrintData, log)
                }
            }

            val btnTest = Button(activity).apply {
                text = "In thá»­"
                textSize = 11f
                isAllCaps = false
                setOnClickListener {
                    PrinterStore.setActivePrinter(p.id)
                    activity.applySavedPrinter(p)
                    activity.sendPrintData("In thá»­ ${p.name}", activity.getReceiptDemoBytes())
                }
            }

            val btnRename = Button(activity).apply {
                text = "Äá»•i tÃªn"
                textSize = 11f
                isAllCaps = false
                setOnClickListener {
                    val etName = EditText(activity).apply { setText(p.name) }
                    AlertDialog.Builder(activity)
                        .setTitle("Äá»•i tÃªn mÃ¡y in")
                        .setMessage("TÃªn cÅ©: ${p.name}\nNháº­p tÃªn má»›i:")
                        .setView(etName)
                        .setPositiveButton("LÆ°u") { _, _ ->
                            val newName = etName.text.toString().trim()
                            if (newName.isNotEmpty()) {
                                PrinterStore.rename(p.mac, p.lastIp, newName)
                                log("ÄÃ£ Ä‘á»•i tÃªn mÃ¡y in: ${p.name} âž” $newName")
                                container.removeAllViews()
                                buildView(activity, container, sendPrintData, log)
                            }
                        }
                        .setNegativeButton("Há»§y", null)
                        .show()
                }
            }

            val btnDelete = Button(activity).apply {
                text = "XÃ³a"
                textSize = 11f
                isAllCaps = false
                setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("XÃ³a mÃ¡y in")
                        .setMessage("Báº¡n cÃ³ cháº¯c cháº¯n muá»‘n xÃ³a '${p.name}' (${p.lastIp}) khá»i danh sÃ¡ch?")
                        .setPositiveButton("XÃ³a") { _, _ ->
                            PrinterStore.delete(p.mac, p.lastIp)
                            log("ÄÃ£ xÃ³a mÃ¡y in: ${p.name}")
                            container.removeAllViews()
                            buildView(activity, container, sendPrintData, log)
                        }
                        .setNegativeButton("Há»§y", null)
                        .show()
                }
            }

            rowBtns.addView(btnUse)
            rowBtns.addView(btnPaper)
            rowBtns.addView(btnTest)
            rowBtns.addView(btnRename)
            rowBtns.addView(btnDelete)

            card.addView(rowBtns)
            container.addView(card)

            container.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
            })
        }
    }

    /**
     * Há»™p thoáº¡i thÃªm mÃ¡y in theo IP trá»±c tiáº¿p (khÃ´ng báº¯t buá»™c pháº£i quÃ©t ra trÆ°á»›c).
     * PhÃ¹ há»£p cho máº¡ng nhiá»u dáº£i IP cÃ³ Ä‘á»‹nh tuyáº¿n.
     */
    fun showAddManualPrinterDialog(activity: MainActivity, onSaved: () -> Unit) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val dialogLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }

        val tvGuide = TextView(activity).apply {
            text = "Nháº­p thÃ´ng tin mÃ¡y in LAN/WiFi cáº§n thÃªm (ká»ƒ cáº£ mÃ¡y á»Ÿ dáº£i máº¡ng khÃ¡c Ä‘Ã£ Ä‘á»‹nh tuyáº¿n):"
            textSize = 13f
            setTextColor(0xFF424242.toInt())
            setPadding(0, 0, 0, dp(8))
        }

        val etName = EditText(activity).apply {
            hint = "TÃªn gá»£i nhá»› (vd: Quáº§y 1, Báº¿p 2)"
        }

        val etIp = EditText(activity).apply {
            hint = "Äá»‹a chá»‰ IP (vd: 192.168.1.237)"
            inputType = InputType.TYPE_CLASS_PHONE
        }

        val etPort = EditText(activity).apply {
            hint = "Cá»•ng in (máº·c Ä‘á»‹nh 9100)"
            setText("9100")
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val spPaper = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Khá»• giáº¥y 58mm (XP-58, XP-Q90EC)", "Khá»• giáº¥y 80mm (XP-80, XP-N160M, C300H)")
            )
        }

        dialogLayout.addView(tvGuide)
        dialogLayout.addView(activity.label("TÃªn mÃ¡y in:"))
        dialogLayout.addView(etName)
        dialogLayout.addView(activity.label("Äá»‹a chá»‰ IP:"))
        dialogLayout.addView(etIp)
        dialogLayout.addView(activity.label("Cá»•ng káº¿t ná»‘i:"))
        dialogLayout.addView(etPort)
        dialogLayout.addView(activity.label("Khá»• giáº¥y máº·c Ä‘á»‹nh:"))
        dialogLayout.addView(spPaper)

        AlertDialog.Builder(activity)
            .setTitle("ThÃªm mÃ¡y in theo IP")
            .setView(dialogLayout)
            .setPositiveButton("Kiá»ƒm tra & LÆ°u") { _, _ ->
                val ipStr = etIp.text.toString().trim()
                val portStr = etPort.text.toString().trim()
                val port = portStr.toIntOrNull() ?: 9100
                val customName = etName.text.toString().trim().ifEmpty { "MÃ¡y in $ipStr" }
                val paperWidth = if (spPaper.selectedItemPosition == 1) 80 else 58

                if (ipStr.isEmpty()) {
                    activity.log("Lá»—i: ChÆ°a nháº­p Ä‘á»‹a chá»‰ IP mÃ¡y in.")
                    return@setPositiveButton
                }

                activity.log("Äang kiá»ƒm tra káº¿t ná»‘i TCP tá»›i $ipStr:$port...")
                thread {
                    val isConnected = NetworkUtil.tcpConnectWithTimeout(ipStr, port, DEFAULT_TCP_TIMEOUT_MS)
                    activity.runOnUiThread {
                        if (isConnected) {
                            val newPrinter = SavedPrinter(
                                name = customName,
                                mac = null, // MÃ¡y in nháº­p tay khÃ´ng cÃ³ MAC
                                lastIp = ipStr,
                                port = port,
                                connType = POSConnect.DEVICE_TYPE_ETHERNET,
                                lastSeenEpochMs = System.currentTimeMillis(),
                                lastStatus = "online",
                                paperWidth = paperWidth
                            )
                            PrinterStore.saveOrUpdate(newPrinter)
                            PrinterStore.setActivePrinter(newPrinter.id)
                            activity.applySavedPrinter(newPrinter)

                            AlertDialog.Builder(activity)
                                .setTitle("âœ… Káº¿t ná»‘i & LÆ°u thÃ nh cÃ´ng")
                                .setMessage(
                                    "ÄÃ£ lÆ°u mÃ¡y in '$customName' ($ipStr:$port) vÃ o danh sÃ¡ch.\n\n" +
                                    "âš ï¸ LÆ°u Ã½ quan trá»ng: MÃ¡y in nÃ y Ä‘Æ°á»£c thÃªm theo IP (khÃ´ng cÃ³ MAC), " +
                                    "nÃªn sáº½ khÃ´ng tá»± dÃ² láº¡i Ä‘Æ°á»£c náº¿u IP mÃ¡y in bá»‹ thay Ä‘á»•i. " +
                                    "HÃ£y cáº­p nháº­t IP báº±ng tay náº¿u cáº§n."
                                )
                                .setPositiveButton("Äá»“ng Ã½") { _, _ -> onSaved() }
                                .show()
                            activity.log("ÄÃ£ thÃªm mÃ¡y in thá»§ cÃ´ng thÃ nh cÃ´ng: $customName ($ipStr:$port)")
                        } else {
                            AlertDialog.Builder(activity)
                                .setTitle("âš ï¸ KhÃ´ng thá»ƒ káº¿t ná»‘i tá»›i $ipStr:$port")
                                .setMessage(
                                    "KhÃ´ng nháº­n Ä‘Æ°á»£c pháº£n há»“i TCP sau ${DEFAULT_TCP_TIMEOUT_MS}ms.\n\n" +
                                    "NguyÃªn nhÃ¢n cÃ³ thá»ƒ do:\n" +
                                    "1. MÃ¡y in Ä‘ang táº¯t nguá»“n hoáº·c rÃºt cÃ¡p máº¡ng.\n" +
                                    "2. Router chÆ°a Ä‘á»‹nh tuyáº¿n giá»¯a hai dáº£i máº¡ng.\n" +
                                    "3. Sai IP hoáº·c cá»•ng.\n\n" +
                                    "Báº¡n cÃ³ váº«n muá»‘n lÆ°u mÃ¡y in nÃ y vÃ o danh sÃ¡ch khÃ´ng?"
                                )
                                .setPositiveButton("Váº«n lÆ°u") { _, _ ->
                                    val newPrinter = SavedPrinter(
                                        name = customName,
                                        mac = null,
                                        lastIp = ipStr,
                                        port = port,
                                        connType = POSConnect.DEVICE_TYPE_ETHERNET,
                                        lastSeenEpochMs = System.currentTimeMillis(),
                                        lastStatus = "offline",
                                        paperWidth = paperWidth
                                    )
                                    PrinterStore.saveOrUpdate(newPrinter)
                                    PrinterStore.setActivePrinter(newPrinter.id)
                                    activity.applySavedPrinter(newPrinter)
                                    activity.log("ÄÃ£ lÆ°u mÃ¡y in [Offline]: $customName ($ipStr:$port)")
                                    onSaved()
                                }
                                .setNegativeButton("Há»§y", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("Há»§y", null)
            .show()
    }
}