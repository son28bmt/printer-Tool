package com.example.xptool

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import net.posprinter.POSConnect
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun MainActivity.pagePrintSetup() = showPage("Thiết lập & in thử", false) { body ->
    val printers = PrinterManager.savedPrinters.toList()
    if (printers.isEmpty()) {
        body.addView(label("Chưa có máy in trong danh sách. Hãy dò mạng và lưu máy in trước."))
        body.addView(btn("Tìm máy in") { pageNetwork() })
        return@showPage
    }

    val savedTemplates = getSharedPreferences("print_setup_templates", MODE_PRIVATE)
    val names = printers.map { "${it.name} · ${if (it.connectType == POSConnect.DEVICE_TYPE_ETHERNET) "${it.lastIp}:${it.port}" else connTypeFor(it)}" }
    val printerPicker = Spinner(this).apply {
        adapter = ArrayAdapter(this@pagePrintSetup, android.R.layout.simple_spinner_dropdown_item, names)
        setSelection(printers.indexOfFirst { it.id == PrinterManager.activePrinterId }.coerceAtLeast(0))
    }
    val kindPicker = spinner(listOf("Hóa đơn", "Tem nhãn"))
    val billWidthPicker = spinner(listOf("K58 · 58 mm", "K80 · 80 mm"))
    val labelSizePicker = spinner(listOf("40 × 30 mm", "30 × 40 mm", "50 × 30 mm", "60 × 40 mm", "100 × 150 mm"))
    val labelTypePicker = spinner(listOf("Tem đơn", "Tem đôi"))
    val billChoiceRow = row(label("Khổ hóa đơn") to 0.8f, billWidthPicker to 1.2f)
    val labelSizeRow = row(label("Kích thước tem") to 0.8f, labelSizePicker to 1.2f)
    val labelTypeRow = row(label("Kiểu tem") to 0.8f, labelTypePicker to 1.2f)

    val selectionPanel = column()
    selectionPanel.addView(label("1. Chọn máy in", 15f, true))
    selectionPanel.addView(printerPicker)
    selectionPanel.addView(label("2. Chọn loại in", 15f, true))
    selectionPanel.addView(label("Khi in thử, máy đã chọn sẽ trở thành máy in đang dùng trong app.", 12f).apply { setTextColor(colorMuted) })
    selectionPanel.addView(kindPicker)
    selectionPanel.addView(billChoiceRow)
    selectionPanel.addView(labelSizeRow)
    selectionPanel.addView(labelTypeRow)
    body.addView(selectionPanel)

    fun updateTypeChoices() {
        val isBill = kindPicker.selectedItemPosition == 0
        billChoiceRow.visibility = if (isBill) View.VISIBLE else View.GONE
        labelSizeRow.visibility = if (isBill) View.GONE else View.VISIBLE
        labelTypeRow.visibility = if (isBill) View.GONE else View.VISIBLE
    }
    kindPicker.setSelection(0)
    kindPicker.onItemSelectedListener = spinnerListener { updateTypeChoices() }
    printerPicker.onItemSelectedListener = spinnerListener {
        val printer = printers.getOrNull(printerPicker.selectedItemPosition) ?: return@spinnerListener
        val config = runCatching { JSONObject(savedTemplates.getString(printer.id, "{}") ?: "{}") }.getOrDefault(JSONObject())
        billWidthPicker.setSelection(if (config.optInt("paperWidth", printer.paperWidth) >= 80) 1 else 0)
        kindPicker.setSelection(config.optInt("kind", 0).coerceIn(0, 1))
        labelSizePicker.setSelection(config.optInt("labelSize", 0).coerceIn(0, 4))
        labelTypePicker.setSelection(config.optInt("labelType", 0).coerceIn(0, 1))
        updateTypeChoices()
    }
    printerPicker.post {
        val printer = printers.getOrNull(printerPicker.selectedItemPosition)
        if (printer != null) {
            val config = runCatching { JSONObject(savedTemplates.getString(printer.id, "{}") ?: "{}") }.getOrDefault(JSONObject())
            billWidthPicker.setSelection(if (config.optInt("paperWidth", printer.paperWidth) >= 80) 1 else 0)
            kindPicker.setSelection(config.optInt("kind", 0).coerceIn(0, 1))
            labelSizePicker.setSelection(config.optInt("labelSize", 0).coerceIn(0, 4))
            labelTypePicker.setSelection(config.optInt("labelType", 0).coerceIn(0, 1))
        }
        updateTypeChoices()
    }

    val setupPanel = column().apply { visibility = View.GONE }
    body.addView(setupPanel)

    val designConfig = column()
    designConfig.addView(label("3. Thiết lập nội dung", 15f, true))
    designConfig.addView(label("Thông tin được lưu riêng theo máy in đã chọn.", 12f).apply { setTextColor(colorMuted) })
    val shopName = edit("Tên cửa hàng", "CỬA HÀNG")
    val shopPhone = edit("Số điện thoại", "0900 000 000", InputType.TYPE_CLASS_PHONE)
    val shopAddress = edit("Địa chỉ", "Địa chỉ cửa hàng")
    val documentTitle = edit("Tiêu đề hóa đơn", "HÓA ĐƠN BÁN HÀNG")
    val footer = edit("Lời nhắn cuối hóa đơn", "Cảm ơn quý khách!")
    listOf(shopName, shopPhone, shopAddress, documentTitle, footer).forEach { designConfig.addView(it) }

    val labelTitle = edit("Tên sản phẩm / nội dung tem", "SẢN PHẨM MẪU")
    val labelNote = edit("Giá hoặc ghi chú", "Giá: 35.000đ")
    val labelCode = edit("Mã sản phẩm / barcode", "SP-001")
    val labelFields = column().apply { visibility = View.GONE }
    labelFields.addView(labelTitle)
    labelFields.addView(labelNote)
    labelFields.addView(labelCode)
    designConfig.addView(labelFields)

    val preview = TextView(this).apply {
        textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
        setTextColor(colorText)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        gravity = Gravity.START
        styleCard(this)
    }
    designConfig.addView(label("Xem trước", 15f, true))
    designConfig.addView(preview)

    fun currentPreview(): String {
        if (kindPicker.selectedItemPosition == 1) {
            val (w, h) = labelDimensions(labelSizePicker.selectedItemPosition)
            val copies = if (labelTypePicker.selectedItemPosition == 1) "Tem đôi · $w × $h mm" else "Tem đơn · $w × $h mm"
            return "$copies\n${labelTitle.text}\n${labelNote.text}\n${labelCode.text}"
        }
        val width = if (billWidthPicker.selectedItemPosition == 1) 80 else 58
        val lines = mutableListOf<String>()
        lines += shopName.text.toString().ifBlank { "CỬA HÀNG" }
        if (shopPhone.text.isNotBlank()) lines += "ĐT: ${shopPhone.text}"
        if (shopAddress.text.isNotBlank()) lines += shopAddress.text.toString()
        lines += "=".repeat(if (width == 58) 32 else 48)
        lines += documentTitle.text.toString().ifBlank { "HÓA ĐƠN BÁN HÀNG" }
        lines += SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        lines += "Sản phẩm mẫu        1     35.000đ"
        lines += "=".repeat(if (width == 58) 32 else 48)
        lines += "TỔNG TIỀN: 35.000đ"
        if (footer.text.isNotBlank()) lines += footer.text.toString()
        return "K$width\n" + lines.joinToString("\n")
    }

    fun refreshPreview() {
        preview.text = currentPreview()
    }
    val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshPreview()
        override fun afterTextChanged(s: Editable?) = Unit
    }
    listOf(shopName, shopPhone, shopAddress, documentTitle, footer, labelTitle, labelNote, labelCode).forEach { it.addTextChangedListener(watcher) }
    listOf(billWidthPicker, labelSizePicker, labelTypePicker).forEach { it.onItemSelectedListener = spinnerListener { refreshPreview() } }
    kindPicker.onItemSelectedListener = spinnerListener {
        updateTypeChoices()
        labelFields.visibility = if (kindPicker.selectedItemPosition == 1) View.VISIBLE else View.GONE
        listOf(shopName, shopPhone, shopAddress, documentTitle, footer).forEach { it.visibility = if (kindPicker.selectedItemPosition == 0) View.VISIBLE else View.GONE }
        refreshPreview()
    }

    fun loadConfig(printer: SavedPrinter) {
        val config = runCatching { JSONObject(savedTemplates.getString(printer.id, "{}") ?: "{}") }.getOrDefault(JSONObject())
        shopName.setText(config.optString("shopName", "CỬA HÀNG"))
        shopPhone.setText(config.optString("shopPhone", "0900 000 000"))
        shopAddress.setText(config.optString("shopAddress", "Địa chỉ cửa hàng"))
        documentTitle.setText(config.optString("documentTitle", "HÓA ĐƠN BÁN HÀNG"))
        footer.setText(config.optString("footer", "Cảm ơn quý khách!"))
        labelTitle.setText(config.optString("labelTitle", "SẢN PHẨM MẪU"))
        labelNote.setText(config.optString("labelNote", "Giá: 35.000đ"))
        labelCode.setText(config.optString("labelCode", "SP-001"))
        kindPicker.setSelection(config.optInt("kind", 0).coerceIn(0, 1))
        billWidthPicker.setSelection(if (config.optInt("paperWidth", printer.paperWidth) >= 80) 1 else 0)
        labelSizePicker.setSelection(config.optInt("labelSize", 0).coerceIn(0, 4))
        labelTypePicker.setSelection(config.optInt("labelType", 0).coerceIn(0, 1))
        labelFields.visibility = if (kindPicker.selectedItemPosition == 1) View.VISIBLE else View.GONE
        listOf(shopName, shopPhone, shopAddress, documentTitle, footer).forEach { it.visibility = if (kindPicker.selectedItemPosition == 0) View.VISIBLE else View.GONE }
        refreshPreview()
    }

    fun saveConfig(printer: SavedPrinter) {
        val config = JSONObject()
            .put("shopName", shopName.text.toString())
            .put("shopPhone", shopPhone.text.toString())
            .put("shopAddress", shopAddress.text.toString())
            .put("documentTitle", documentTitle.text.toString())
            .put("footer", footer.text.toString())
            .put("labelTitle", labelTitle.text.toString())
            .put("labelNote", labelNote.text.toString())
            .put("labelCode", labelCode.text.toString())
            .put("kind", kindPicker.selectedItemPosition)
            .put("paperWidth", if (billWidthPicker.selectedItemPosition == 1) 80 else 58)
            .put("labelSize", labelSizePicker.selectedItemPosition)
            .put("labelType", labelTypePicker.selectedItemPosition)
        savedTemplates.edit().putString(printer.id, config.toString()).apply()
        if (kindPicker.selectedItemPosition == 0) {
            printer.paperWidth = if (billWidthPicker.selectedItemPosition == 1) 80 else 58
            PrinterStore.saveOrUpdate(printer)
        }
        log("Đã lưu thiết lập in cho ${printer.name}.")
    }

    val saveButton = btn("Lưu thiết kế") {
        val printer = printers.getOrNull(printerPicker.selectedItemPosition) ?: return@btn
        saveConfig(printer)
    }
    val printButton = btn("In thử") {
        val printer = printers.getOrNull(printerPicker.selectedItemPosition) ?: return@btn
        saveConfig(printer)
        PrinterManager.setActivePrinter(this@pagePrintSetup, printer.id)
        applySavedPrinter(printer)
        val data = if (kindPicker.selectedItemPosition == 0) {
            PrintSetupTemplates.bill(
                width = if (billWidthPicker.selectedItemPosition == 1) 80 else 58,
                shopName = shopName.text.toString(),
                phone = shopPhone.text.toString(),
                address = shopAddress.text.toString(),
                title = documentTitle.text.toString(),
                footer = footer.text.toString(),
                removeAccents = ::removeAccents,
                commandBytes = { values -> bytes(*values) }
            )
        } else {
            val (width, height) = labelDimensions(labelSizePicker.selectedItemPosition)
            PrintSetupTemplates.label(
                width = width,
                height = height,
                double = labelTypePicker.selectedItemPosition == 1,
                title = labelTitle.text.toString(),
                note = labelNote.text.toString(),
                code = labelCode.text.toString(),
                removeAccents = ::removeAccents
            )
        }
        sendPrintData(if (kindPicker.selectedItemPosition == 0) "In thử hóa đơn K${printer.paperWidth}" else "In thử tem TSPL", data)
    }
    designConfig.addView(row(saveButton to 1f, printButton to 1f))
    setupPanel.addView(designConfig)

    val continueButton = btn("Tiếp theo: thiết kế mẫu") {}
    continueButton.setOnClickListener {
        val printer = printers.getOrNull(printerPicker.selectedItemPosition) ?: return@setOnClickListener
        val chosenKind = kindPicker.selectedItemPosition
        val chosenBillWidth = billWidthPicker.selectedItemPosition
        val chosenLabelSize = labelSizePicker.selectedItemPosition
        val chosenLabelType = labelTypePicker.selectedItemPosition
        loadConfig(printer)
        kindPicker.setSelection(chosenKind)
        billWidthPicker.setSelection(chosenBillWidth)
        labelSizePicker.setSelection(chosenLabelSize)
        labelTypePicker.setSelection(chosenLabelType)
        updateTypeChoices()
        labelFields.visibility = if (chosenKind == 1) View.VISIBLE else View.GONE
        listOf(shopName, shopPhone, shopAddress, documentTitle, footer).forEach {
            it.visibility = if (chosenKind == 0) View.VISIBLE else View.GONE
        }
        refreshPreview()
        selectionPanel.visibility = View.GONE
        continueButton.visibility = View.GONE
        setupPanel.visibility = View.VISIBLE
    }
    body.addView(continueButton)
    setupPanel.addView(btn("Quay lại chọn máy in / khổ giấy") {
        setupPanel.visibility = View.GONE
        selectionPanel.visibility = View.VISIBLE
        continueButton.visibility = View.VISIBLE
    })
}

private fun MainActivity.spinner(items: List<String>) = Spinner(this).apply {
    adapter = ArrayAdapter(this@spinner, android.R.layout.simple_spinner_dropdown_item, items)
}

private fun spinnerListener(onSelected: () -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected()
}

private fun labelDimensions(index: Int): Pair<Int, Int> = when (index) {
    1 -> 30 to 40
    2 -> 50 to 30
    3 -> 60 to 40
    4 -> 100 to 150
    else -> 40 to 30
}

private fun connTypeFor(printer: SavedPrinter) = when (printer.connectType) {
    POSConnect.DEVICE_TYPE_BLUETOOTH -> "Bluetooth ${printer.btMac}"
    POSConnect.DEVICE_TYPE_USB -> "USB ${printer.usbPath}"
    else -> "LAN/Wi-Fi"
}

private object PrintSetupTemplates {
    fun bill(
        width: Int,
        shopName: String,
        phone: String,
        address: String,
        title: String,
        footer: String,
        removeAccents: (String) -> String,
        commandBytes: (IntArray) -> ByteArray
    ): ByteArray {
        val columns = if (width <= 58) 32 else 48
        val out = ByteArrayOutputStream()
        fun text(value: String) = out.write(removeAccents(value).toByteArray(Charsets.US_ASCII))
        fun line(value: String) = text(value.take(columns) + "\n")
        out.write(commandBytes(intArrayOf(0x1B, 0x40)))
        out.write(commandBytes(intArrayOf(0x1B, 0x61, 0x01)))
        out.write(commandBytes(intArrayOf(0x1D, 0x21, 0x01)))
        line(shopName.ifBlank { "CỬA HÀNG" })
        out.write(commandBytes(intArrayOf(0x1D, 0x21, 0x00)))
        if (phone.isNotBlank()) line("ĐT: $phone")
        if (address.isNotBlank()) line(address)
        line("-".repeat(columns))
        line(title.ifBlank { "HÓA ĐƠN BÁN HÀNG" })
        line(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date()))
        out.write(commandBytes(intArrayOf(0x1B, 0x61, 0x00)))
        line("Sản phẩm mẫu                  1")
        line("Đơn giá: 35.000đ")
        line("-".repeat(columns))
        out.write(commandBytes(intArrayOf(0x1B, 0x61, 0x02)))
        line("TỔNG TIỀN: 35.000đ")
        out.write(commandBytes(intArrayOf(0x1B, 0x61, 0x01)))
        if (footer.isNotBlank()) line(footer)
        text("\n\n\n")
        out.write(commandBytes(intArrayOf(0x1D, 0x56, 0x00)))
        return out.toByteArray()
    }

    fun label(
        width: Int,
        height: Int,
        double: Boolean,
        title: String,
        note: String,
        code: String,
        removeAccents: (String) -> String
    ): ByteArray {
        fun clean(value: String) = removeAccents(value).replace('"', '\'').replace('\n', ' ').replace('\r', ' ')
        val mediaWidth = if (double) width * 2 else width
        val titleText = clean(title)
        val noteText = clean(note)
        val codeText = clean(code)
        val dotsPerMm = 8 // TSPL text/barcode coordinates are dots, while SIZE uses millimeters.
        val positions = if (double) listOf(1, width + 1) else listOf(1)
        return buildString {
            append("SIZE $mediaWidth mm, $height mm\r\nGAP 2 mm, 0 mm\r\nCLS\r\n")
            positions.forEach { xMm ->
                val x = xMm * dotsPerMm
                append("TEXT $x,${2 * dotsPerMm},\"3\",0,1,1,\"$titleText\"\r\n")
                append("TEXT $x,${(height * 0.32 * dotsPerMm).toInt()},\"2\",0,1,1,\"$noteText\"\r\n")
                append("BARCODE $x,${(height * 0.58 * dotsPerMm).toInt()},\"128\",${(height * 0.3 * dotsPerMm).toInt()},1,0,2,2,\"$codeText\"\r\n")
            }
            append("PRINT 1,1\r\n")
        }.toByteArray(Charsets.US_ASCII)
    }
}
