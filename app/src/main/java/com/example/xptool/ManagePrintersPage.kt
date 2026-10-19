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

        // Tiêu đề & thanh công cụ
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }

        val tvTitle = TextView(activity).apply {
            val count = PrinterStore.printers.size
            text = "Danh sách máy in ($count)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF212121.toInt())
        }

        val btnRefresh = activity.styleButton(Button(activity), primary = false).apply {
            text = "🔄 Làm mới"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                log("Đang kiểm tra lại trạng thái kết nối các máy in...")
                PrinterStore.refreshAllStatuses {
                    activity.runOnUiThread {
                        log("Làm mới trạng thái hoàn tất.")
                        container.removeAllViews()
                        buildView(activity, container, sendPrintData, log)
                    }
                }
            }
        }

        val btnAddManual = activity.styleButton(Button(activity)).apply {
            text = "➕ Thêm theo IP"
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

        // Hướng dẫn giải thích dải mạng
        val tvNote = TextView(activity).apply {
            text = "ℹ️ Lưu ý: Máy in có thể lưu theo IP trực tiếp khi ở dải mạng khác đã định tuyến. " +
                    "Máy in có địa chỉ MAC sẽ tự dò lại IP khi bị đổi; máy in thêm thủ công bằng IP sẽ giữ cố định theo IP."
            textSize = 12f
            setTextColor(0xFF555555.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(0xFFEAF4ED.toInt())
        }
        container.addView(tvNote)
        container.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
        })

        val printerList = PrinterStore.loadAll()
        if (printerList.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = "Chưa có máy in nào trong danh sách.\nBấm nút '➕ Thêm theo IP' ở trên hoặc vào mục '2. Tìm máy in LAN & Đổi IP' để thêm."
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
            val card = activity.styleCard(LinearLayout(activity), selected = isActive).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }

            // Hàng 1: Tên máy in + Trạng thái
            val row1 = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(activity).apply {
                text = "${p.name} ${if (isActive) "[ĐANG DÙNG]" else ""}"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isActive) 0xFF1B5E20.toInt() else 0xFF212121.toInt())
            }

            val statusText: String
            val statusColor: Int
            when (p.lastStatus) {
                "online" -> {
                    statusText = "🟢 Online"
                    statusColor = 0xFF2E7D32.toInt()
                }
                "offline" -> {
                    statusText = "🔴 Offline"
                    statusColor = 0xFFC62828.toInt()
                }
                else -> {
                    statusText = "🟠 Chưa kiểm tra"
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

            // Dòng phụ: Chi tiết kết nối
            val connectionDetails = when (p.connType) {
                POSConnect.DEVICE_TYPE_ETHERNET -> {
                    val macInfo = if (!p.mac.isNullOrBlank()) "MAC: ${p.mac}" else "Nhập tay (Không có MAC)"
                    "LAN: ${p.lastIp}:${p.port} | $macInfo"
                }
                POSConnect.DEVICE_TYPE_BLUETOOTH -> "Bluetooth: ${p.btMac}"
                POSConnect.DEVICE_TYPE_USB -> "USB OTG: ${p.usbPath}"
                else -> "Giao tiếp khác"
            }
            val labelSizes = listOf(40 to 30, 30 to 40, 50 to 30, 60 to 40, 100 to 150)
            val assignedPrint = when (p.printKind) {
                1 -> "Mặc định: Tem ${labelSizes[p.labelSize.coerceIn(0, 4)].first} × ${labelSizes[p.labelSize.coerceIn(0, 4)].second} mm${if (p.labelType == 1) " · đôi" else ""}"
                0 -> "Mặc định: Hóa đơn K${p.paperWidth}"
                else -> "Chưa gán loại in · Hóa đơn K${p.paperWidth}"
            }
            val subDetails = "$connectionDetails\n$assignedPrint"

            val tvSub = TextView(activity).apply {
                text = subDetails
                textSize = 12f
                setTextColor(0xFF616161.toInt())
                setPadding(0, dp(2), 0, dp(6))
            }
            card.addView(tvSub)

            // Hàng nút thao tác
            val actionRows = activity.column()
            val primaryActions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val secondaryActions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val btnUse = activity.styleButton(Button(activity), primary = !isActive).apply {
                text = if (isActive) "Đang chọn" else "Dùng máy này"
                textSize = 11f
                isAllCaps = false
                isEnabled = !isActive
                setOnClickListener {
                    PrinterStore.setActivePrinter(p.id)
                    activity.applySavedPrinter(p)
                    log("Đã chọn máy in đang dùng: ${p.name} (${p.lastIp})")
                    container.removeAllViews()
                    buildView(activity, container, sendPrintData, log)
                }
            }

            val btnPaper = activity.styleButton(Button(activity), primary = false).apply {
                text = "Khổ ${p.paperWidth}mm"
                textSize = 11f
                isAllCaps = false
                visibility = if (p.printKind == 1) View.GONE else View.VISIBLE
                setOnClickListener {
                    p.paperWidth = if (p.paperWidth == 58) 80 else 58
                    PrinterStore.saveOrUpdate(p)
                    if (isActive) activity.applySavedPrinter(p)
                    log("Đã chuyển khổ giấy máy in [${p.name}] sang ${p.paperWidth}mm")
                    container.removeAllViews()
                    buildView(activity, container, sendPrintData, log)
                }
            }

            val btnTest = activity.styleButton(Button(activity), primary = false).apply {
                text = "In thử"
                textSize = 11f
                isAllCaps = false
                setOnClickListener {
                    PrinterStore.setActivePrinter(p.id)
                    activity.applySavedPrinter(p)
                    activity.sendPrintData("In thử ${p.name}", activity.getReceiptDemoBytes())
                }
            }

            val btnAssignPrint = activity.styleButton(Button(activity), primary = false).apply {
                text = "Gán loại / khổ in"
                textSize = 11f
                isAllCaps = false
                setOnClickListener {
                    val dialogContent = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(8), dp(20), 0)
                    }
                    fun addCaption(text: String) {
                        dialogContent.addView(TextView(activity).apply {
                            this.text = text
                            textSize = 13f
                            setTextColor(0xFF616161.toInt())
                            setPadding(0, dp(8), 0, dp(2))
                        })
                    }
                    val kindSpinner = Spinner(activity).apply {
                        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("Hóa đơn", "Tem nhãn"))
                        setSelection(if (p.printKind == 1) 1 else 0)
                    }
                    val sizeSpinner = Spinner(activity)
                    val labelTypeSpinner = Spinner(activity).apply {
                        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("Tem đơn", "Tem đôi"))
                        setSelection(p.labelType.coerceIn(0, 1))
                    }
                    addCaption("Loại in mặc định cho máy này")
                    dialogContent.addView(kindSpinner)
                    addCaption("Khổ giấy")
                    dialogContent.addView(sizeSpinner)
                    val labelTypeCaption = TextView(activity).apply {
                        text = "Kiểu tem"
                        textSize = 13f
                        setTextColor(0xFF616161.toInt())
                        setPadding(0, dp(8), 0, dp(2))
                    }
                    dialogContent.addView(labelTypeCaption)
                    dialogContent.addView(labelTypeSpinner)

                    fun updateSizeOptions() {
                        val labelMode = kindSpinner.selectedItemPosition == 1
                        val options = if (labelMode) {
                            labelSizes.map { "${it.first} × ${it.second} mm" }
                        } else listOf("K58 · 58 mm", "K80 · 80 mm")
                        sizeSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, options)
                        sizeSpinner.setSelection(if (labelMode) p.labelSize.coerceIn(0, 4) else if (p.paperWidth >= 80) 1 else 0)
                        labelTypeCaption.visibility = if (labelMode) View.VISIBLE else View.GONE
                        labelTypeSpinner.visibility = if (labelMode) View.VISIBLE else View.GONE
                    }
                    kindSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = updateSizeOptions()
                    }
                    updateSizeOptions()

                    AlertDialog.Builder(activity)
                        .setTitle("Gán loại in · ${p.name}")
                        .setView(dialogContent)
                        .setNegativeButton("Hủy", null)
                        .setPositiveButton("Lưu") { _, _ ->
                            p.printKind = kindSpinner.selectedItemPosition
                            if (p.printKind == 0) {
                                p.paperWidth = if (sizeSpinner.selectedItemPosition == 1) 80 else 58
                            } else {
                                p.labelSize = sizeSpinner.selectedItemPosition.coerceIn(0, 4)
                                p.labelType = labelTypeSpinner.selectedItemPosition.coerceIn(0, 1)
                            }
                            PrinterStore.saveOrUpdate(p)
                            log("Đã gán ${if (p.printKind == 0) "hóa đơn K${p.paperWidth}" else "tem ${labelSizes[p.labelSize].first} × ${labelSizes[p.labelSize].second} mm"} cho ${p.name}.")
                            container.removeAllViews()
                            buildView(activity, container, sendPrintData, log)
                        }
                        .show()
                }
            }

            val btnRename = activity.styleButton(Button(activity), primary = false).apply {
                text = "Đổi tên"
                textSize = 11f
                isAllCaps = false
                setOnClickListener {
                    val etName = EditText(activity).apply { setText(p.name) }
                    AlertDialog.Builder(activity)
                        .setTitle("Đổi tên máy in")
                        .setMessage("Tên cũ: ${p.name}\nNhập tên mới:")
                        .setView(etName)
                        .setPositiveButton("Lưu") { _, _ ->
                            val newName = etName.text.toString().trim()
                            if (newName.isNotEmpty()) {
                                PrinterStore.rename(p.mac, p.lastIp, newName)
                                log("Đã đổi tên máy in: ${p.name} ➔ $newName")
                                container.removeAllViews()
                                buildView(activity, container, sendPrintData, log)
                            }
                        }
                        .setNegativeButton("Hủy", null)
                        .show()
                }
            }

            val btnDelete = activity.styleButton(Button(activity), primary = false, danger = true).apply {
                text = "Xóa"
                textSize = 11f
                isAllCaps = false
                setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("Xóa máy in")
                        .setMessage("Bạn có chắc chắn muốn xóa '${p.name}' (${p.lastIp}) khỏi danh sách?")
                        .setPositiveButton("Xóa") { _, _ ->
                            PrinterStore.delete(p.mac, p.lastIp)
                            log("Đã xóa máy in: ${p.name}")
                            container.removeAllViews()
                            buildView(activity, container, sendPrintData, log)
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
            addAction(secondaryActions, btnAssignPrint)
            addAction(secondaryActions, btnRename)
            addAction(secondaryActions, btnDelete)
            actionRows.addView(primaryActions)
            actionRows.addView(secondaryActions)

            card.addView(actionRows)
            container.addView(card)

            container.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
            })
        }
    }

    /**
     * Hộp thoại thêm máy in theo IP trực tiếp (không bắt buộc phải quét ra trước).
     * Phù hợp cho mạng nhiều dải IP có định tuyến.
     */
    fun showAddManualPrinterDialog(activity: MainActivity, onSaved: () -> Unit) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val dialogLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }

        val tvGuide = TextView(activity).apply {
            text = "Nhập thông tin máy in LAN/WiFi cần thêm (kể cả máy ở dải mạng khác đã định tuyến):"
            textSize = 13f
            setTextColor(0xFF424242.toInt())
            setPadding(0, 0, 0, dp(8))
        }

        val etName = EditText(activity).apply {
            hint = "Tên gợi nhớ (vd: Quầy 1, Bếp 2)"
        }

        val etIp = EditText(activity).apply {
            hint = "Địa chỉ IP (vd: 192.168.1.237)"
            inputType = InputType.TYPE_CLASS_PHONE
        }

        val etPort = EditText(activity).apply {
            hint = "Cổng in (mặc định 9100)"
            setText("9100")
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val spPaper = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Khổ giấy 58mm (XP-58, XP-Q90EC)", "Khổ giấy 80mm (XP-80, XP-N160M, C300H)")
            )
        }

        dialogLayout.addView(tvGuide)
        dialogLayout.addView(activity.label("Tên máy in:"))
        dialogLayout.addView(etName)
        dialogLayout.addView(activity.label("Địa chỉ IP:"))
        dialogLayout.addView(etIp)
        dialogLayout.addView(activity.label("Cổng kết nối:"))
        dialogLayout.addView(etPort)
        dialogLayout.addView(activity.label("Khổ giấy mặc định:"))
        dialogLayout.addView(spPaper)

        AlertDialog.Builder(activity)
            .setTitle("Thêm máy in theo IP")
            .setView(dialogLayout)
            .setPositiveButton("Kiểm tra & Lưu") { _, _ ->
                val ipStr = etIp.text.toString().trim()
                val portStr = etPort.text.toString().trim()
                val port = portStr.toIntOrNull() ?: 9100
                val customName = etName.text.toString().trim().ifEmpty { "Máy in $ipStr" }
                val paperWidth = if (spPaper.selectedItemPosition == 1) 80 else 58

                if (ipStr.isEmpty()) {
                    activity.log("Lỗi: Chưa nhập địa chỉ IP máy in.")
                    return@setPositiveButton
                }

                activity.log("Đang kiểm tra kết nối TCP tới $ipStr:$port...")
                thread {
                    val isConnected = NetworkUtil.tcpConnectWithTimeout(ipStr, port, DEFAULT_TCP_TIMEOUT_MS)
                    activity.runOnUiThread {
                        if (isConnected) {
                            val newPrinter = SavedPrinter(
                                name = customName,
                                mac = null, // Máy in nhập tay không có MAC
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
                                .setTitle("✅ Kết nối & Lưu thành công")
                                .setMessage(
                                    "Đã lưu máy in '$customName' ($ipStr:$port) vào danh sách.\n\n" +
                                    "⚠️ Lưu ý quan trọng: Máy in này được thêm theo IP (không có MAC), " +
                                    "nên sẽ không tự dò lại được nếu IP máy in bị thay đổi. " +
                                    "Hãy cập nhật IP bằng tay nếu cần."
                                )
                                .setPositiveButton("Đồng ý") { _, _ -> onSaved() }
                                .show()
                            activity.log("Đã thêm máy in thủ công thành công: $customName ($ipStr:$port)")
                        } else {
                            AlertDialog.Builder(activity)
                                .setTitle("⚠️ Không thể kết nối tới $ipStr:$port")
                                .setMessage(
                                    "Không nhận được phản hồi TCP sau ${DEFAULT_TCP_TIMEOUT_MS}ms.\n\n" +
                                    "Nguyên nhân có thể do:\n" +
                                    "1. Máy in đang tắt nguồn hoặc rút cáp mạng.\n" +
                                    "2. Router chưa định tuyến giữa hai dải mạng.\n" +
                                    "3. Sai IP hoặc cổng.\n\n" +
                                    "Bạn có vẫn muốn lưu máy in này vào danh sách không?"
                                )
                                .setPositiveButton("Vẫn lưu") { _, _ ->
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
                                    activity.log("Đã lưu máy in [Offline]: $customName ($ipStr:$port)")
                                    onSaved()
                                }
                                .setNegativeButton("Hủy", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }
}
