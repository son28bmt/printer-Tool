package com.example.xptool

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import net.posprinter.POSConnect
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt

private data class PrintSetupOrderItem(
    val name: String,
    val quantity: Long,
    val unitPrice: Long
) {
    val subtotal: Long
        get() = if (quantity > 0 && unitPrice > Long.MAX_VALUE / quantity) Long.MAX_VALUE else quantity * unitPrice
}

private data class PrintSetupReceiptLine(val text: String, val alignment: Int = 0)

/** Draws a label at the selected physical ratio and follows the same element positions as TSPL output. */
private class PrintSetupLabelPreview(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 215, 208)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 32, 28)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    private val barPaint = Paint().apply { color = Color.BLACK }
    private val dividerPaint = Paint().apply { color = Color.LTGRAY; strokeWidth = density }
    private var labelWidthMm = 40
    private var labelHeightMm = 30
    private var doubleLabel = false
    private var title = ""
    private var note = ""
    private var code = ""

    fun setContent(widthMm: Int, heightMm: Int, isDouble: Boolean, title: String, note: String, code: String) {
        labelWidthMm = widthMm
        labelHeightMm = heightMm
        doubleLabel = isDouble
        this.title = title
        this.note = note
        this.code = code
        contentDescription = "Label ${if (isDouble) "double" else "single"}, ${widthMm} by ${heightMm} mm: $title, $note, $code"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = if (doubleLabel) 2 else 1
        val physicalWidth = labelWidthMm.toFloat() * count
        val scale = min(width.toFloat() / physicalWidth.coerceAtLeast(1f), height.toFloat() / labelHeightMm.coerceAtLeast(1))
        val labelW = labelWidthMm * scale
        val labelH = labelHeightMm * scale
        val left = (width - labelW * count) / 2f
        val top = (height - labelH) / 2f

        repeat(count) { index ->
            val x = left + index * labelW
            val rect = android.graphics.RectF(x, top, x + labelW, top + labelH)
            val radius = 2f * density
            canvas.drawRoundRect(rect, radius, radius, paperPaint)
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
            if (index == 1) canvas.drawLine(x, top, x, top + labelH, dividerPaint)

            val pad = labelW / labelWidthMm.coerceAtLeast(1).toFloat() // TSPL starts text/barcode 1 mm from the left edge.
            val usableWidth = (labelW - pad * 2).coerceAtLeast(1f)
            textPaint.textSize = (labelW * 0.095f).coerceIn(8f * density, 18f * density)
            drawFittedText(canvas, title, x + pad, top + labelH * 0.20f, usableWidth)

            textPaint.textSize = (labelW * 0.075f).coerceIn(7f * density, 15f * density)
            drawFittedText(canvas, note, x + pad, top + labelH * 0.32f, usableWidth)

            val barcodeTop = top + labelH * 0.58f
            val barcodeBottom = top + labelH * 0.88f
            drawPreviewBarcode(canvas, x + pad, barcodeTop, usableWidth, barcodeBottom - barcodeTop)
            textPaint.textSize = (labelW * 0.075f).coerceIn(7f * density, 14f * density)
            drawFittedText(canvas, code, x + pad, min(top + labelH * 0.98f, barcodeBottom + textPaint.textSize * 1.25f), usableWidth)
        }
    }

    private fun drawFittedText(canvas: Canvas, value: String, x: Float, baseline: Float, maxWidth: Float) {
        if (value.isBlank()) return
        val cleaned = value.replace('\n', ' ').trim()
        var shown = cleaned
        while (shown.isNotEmpty() && textPaint.measureText(shown) > maxWidth) shown = shown.dropLast(1)
        if (shown.length < cleaned.length && shown.length > 1) shown = shown.dropLast(1) + "…"
        canvas.drawText(shown, x, baseline, textPaint)
    }

    private fun drawPreviewBarcode(canvas: Canvas, left: Float, top: Float, barWidth: Float, barHeight: Float) {
        val pattern = intArrayOf(2, 1, 1, 3, 1, 2, 3, 1, 2, 1, 1, 2, 2, 3, 1, 1, 3, 2, 1, 2, 1, 3, 2, 1)
        val total = pattern.sum().toFloat()
        var x = left
        var draw = true
        pattern.forEach { unit ->
            val segmentWidth = barWidth * unit / total
            if (draw) canvas.drawRect(x, top, x + segmentWidth * 0.78f, top + barHeight, barPaint)
            x += segmentWidth
            draw = !draw
        }
    }
}

private data class PrintSetupProductEditor(
    val name: EditText,
    val quantity: EditText,
    val unitPrice: EditText,
    val amountView: TextView,
    val card: View
)

internal fun MainActivity.pagePrintSetup() = showPage("Thiết lập & in thử", false) { body ->
    val printers = PrinterManager.savedPrinters.toList()
    if (printers.isEmpty()) {
        body.addView(label("Chưa có máy in trong danh sách. Hãy dò mạng và lưu máy in trước."))
        body.addView(btn("Chọn kết nối Bluetooth / USB / IP") { pageSelectConnection() })
        body.addView(btn("Dò máy in qua Wi-Fi") { pageNetwork() })
        return@showPage
    }

    val savedTemplates = getSharedPreferences("print_setup_templates", android.content.Context.MODE_PRIVATE)
    val names = printers.map { "${it.name} · ${if (it.connectType == POSConnect.DEVICE_TYPE_ETHERNET) "${it.lastIp}:${it.port}" else connTypeFor(it)}" }
    val printerPicker = Spinner(this).apply {
        adapter = ArrayAdapter(this@pagePrintSetup, android.R.layout.simple_spinner_dropdown_item, names)
        setSelection(printers.indexOfFirst { it.id == PrinterManager.activePrinterId }.coerceAtLeast(0))
    }
    val templatePicker = Spinner(this).apply {
        adapter = ArrayAdapter(this@pagePrintSetup, android.R.layout.simple_spinner_dropdown_item, emptyList<String>())
    }
    val templateSetState = arrayOfNulls<PrintSetupTemplateSet>(1)

    fun selectedTemplate(printer: SavedPrinter): PrintSetupTemplate? {
        val set = templateSetState[0] ?: return null
        return set.templates.getOrNull(templatePicker.selectedItemPosition)
    }

    fun refreshTemplates(printer: SavedPrinter, preferredId: String? = null) {
        val set = PrintSetupTemplateStore.load(savedTemplates, printer.id, printer.paperWidth)
        templateSetState[0] = set
        val desired = preferredId ?: set.selectedId
        val selectedIndex = set.templates.indexOfFirst { it.id == desired }.coerceAtLeast(0)
        set.selectedId = set.templates[selectedIndex].id
        templatePicker.adapter = ArrayAdapter(this@pagePrintSetup, android.R.layout.simple_spinner_dropdown_item, set.templates.map { it.name })
        templatePicker.setSelection(selectedIndex)
        PrintSetupTemplateStore.save(savedTemplates, printer.id, set)
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
    selectionPanel.addView(label("Mẫu thiết kế", 15f, true))
    selectionPanel.addView(templatePicker)
    selectionPanel.addView(row(
        btn("+ Thêm mẫu") {
            val printer = printers.getOrNull(printerPicker.selectedItemPosition) ?: return@btn
            val set = templateSetState[0] ?: return@btn
            val nameInput = edit("Tên mẫu", "Mẫu ${set.templates.size + 1}")
            AlertDialog.Builder(this@pagePrintSetup)
                .setTitle("Thêm mẫu in")
                .setView(nameInput)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Thêm") { _, _ ->
                    val name = nameInput.text.toString().trim().ifBlank { "Mẫu ${set.templates.size + 1}" }
                    val source = selectedTemplate(printer) ?: set.templates.first()
                    val added = PrintSetupTemplateStore.newTemplate(name, source)
                    set.templates += added
                    set.selectedId = added.id
                    PrintSetupTemplateStore.save(savedTemplates, printer.id, set)
                    refreshTemplates(printer, added.id)
                }
                .show()
        } to 1f,
        btn("Đổi tên") {
            val printer = printers.getOrNull(printerPicker.selectedItemPosition) ?: return@btn
            val template = selectedTemplate(printer) ?: return@btn
            val nameInput = edit("Tên mẫu", template.name)
            AlertDialog.Builder(this@pagePrintSetup)
                .setTitle("Đổi tên mẫu")
                .setView(nameInput)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu") { _, _ ->
                    template.name = nameInput.text.toString().trim().ifBlank { template.name }
                    templateSetState[0]?.let { PrintSetupTemplateStore.save(savedTemplates, printer.id, it) }
                    refreshTemplates(printer, template.id)
                }
                .show()
        } to 1f,
        btn("Xóa") {
            val printer = printers.getOrNull(printerPicker.selectedItemPosition) ?: return@btn
            val set = templateSetState[0] ?: return@btn
            if (set.templates.size <= 1) {
                log("Cần giữ ít nhất một mẫu in.")
                return@btn
            }
            val template = selectedTemplate(printer) ?: return@btn
            AlertDialog.Builder(this@pagePrintSetup)
                .setTitle("Xóa mẫu ${template.name}?")
                .setMessage("Cấu hình và logo của mẫu này sẽ bị xóa.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xóa") { _, _ ->
                    set.templates.removeAll { it.id == template.id }
                    set.selectedId = set.templates.first().id
                    PrintSetupTemplateStore.save(savedTemplates, printer.id, set)
                    refreshTemplates(printer, set.selectedId)
                }
                .show()
        } to 1f
    ))
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
    templatePicker.onItemSelectedListener = spinnerListener {
        val set = templateSetState[0] ?: return@spinnerListener
        set.templates.getOrNull(templatePicker.selectedItemPosition)?.let { set.selectedId = it.id }
        val printer = printers.getOrNull(printerPicker.selectedItemPosition) ?: return@spinnerListener
        PrintSetupTemplateStore.save(savedTemplates, printer.id, set)
    }
    fun applySavedPrintAssignment(printer: SavedPrinter, config: JSONObject) {
        val kind = printer.printKind.takeIf { it in 0..1 } ?: config.optInt("kind", 0).coerceIn(0, 1)
        kindPicker.setSelection(kind)
        // Paper width belongs to the saved printer so switching devices restores its own roll size.
        billWidthPicker.setSelection(if (printer.paperWidth >= 80) 1 else 0)
        labelSizePicker.setSelection(if (printer.printKind >= 0) printer.labelSize.coerceIn(0, 4) else config.optInt("labelSize", 0).coerceIn(0, 4))
        labelTypePicker.setSelection(if (printer.printKind >= 0) printer.labelType.coerceIn(0, 1) else config.optInt("labelType", 0).coerceIn(0, 1))
        updateTypeChoices()
    }
    printerPicker.onItemSelectedListener = spinnerListener {
        val printer = printers.getOrNull(printerPicker.selectedItemPosition) ?: return@spinnerListener
        refreshTemplates(printer)
        val config = selectedTemplate(printer)?.config ?: JSONObject()
        applySavedPrintAssignment(printer, config)
    }
    printerPicker.post {
        val printer = printers.getOrNull(printerPicker.selectedItemPosition)
        if (printer != null) {
            refreshTemplates(printer)
            val config = selectedTemplate(printer)?.config ?: JSONObject()
            applySavedPrintAssignment(printer, config)
        }
        updateTypeChoices()
    }

    val setupPanel = column().apply { visibility = View.GONE }
    body.addView(setupPanel)

    val designConfig = column()
    designConfig.addView(label("3. Thiết lập nội dung", 15f, true))
    designConfig.addView(label("Thông tin được lưu trong mẫu đang chọn của máy in.", 12f).apply { setTextColor(colorMuted) })
    val shopName = edit("Tên cửa hàng", "CỬA HÀNG")
    val shopPhone = edit("Số điện thoại", "0900 000 000", InputType.TYPE_CLASS_PHONE)
    val shopAddress = edit("Địa chỉ", "Địa chỉ cửa hàng")
    val documentTitle = edit("Tiêu đề hóa đơn", "HÓA ĐƠN BÁN HÀNG")
    val footer = edit("Lời nhắn cuối hóa đơn", "Cảm ơn quý khách!")
    val billFields = column()
    listOf(shopName, shopPhone, shopAddress, documentTitle, footer).forEach { billFields.addView(it) }
    val logoUri = arrayOfNulls<Uri>(1)
    val logoStatus = label("Chưa chọn logo", 12f).apply { setTextColor(colorMuted) }
    val logoPreview = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        maxHeight = dp(120)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120))
        styleCard(this)
    }
    fun showLogo(uri: Uri?) {
        logoUri[0] = uri
        if (uri == null) {
            logoPreview.setImageDrawable(null)
            logoStatus.text = "Chưa chọn logo"
            return
        }
        logoStatus.text = "Đang tải logo…"
        val target = if (billWidthPicker.selectedItemPosition == 1) 576 else 384
        thread(name = "xp-logo-preview") {
            val bitmap = PrintImageProcessor.decodeImage(contentResolver, uri, target)
            runOnUiThread {
                logoPreview.setImageBitmap(bitmap)
                logoStatus.text = if (bitmap == null) "Không đọc được logo. Chọn ảnh PNG hoặc JPG khác." else "Logo đã co về khoảng $target dot, không lưu vào bộ nhớ NV của máy in."
            }
        }
    }
    billFields.addView(label("Logo cửa hàng", 14f, true))
    billFields.addView(row(
        btn("Chọn logo") { choosePrintLogo { picked -> if (picked != null) showLogo(picked) } } to 1f,
        btn("Bỏ logo") { showLogo(null) } to 1f
    ))
    billFields.addView(btn("In thử logo") {
        val uri = logoUri[0] ?: return@btn log("Hãy chọn logo trước.")
        val target = if (billWidthPicker.selectedItemPosition == 1) 576 else 384
        thread(name = "xp-logo-print") {
            val bitmap = PrintImageProcessor.decodeImage(contentResolver, uri, target)
            if (bitmap == null) log("Không đọc được logo để in.") else {
                sendPrintBitmap("Logo cửa hàng", bitmap, target)
                bitmap.recycle()
            }
        }
    })
    billFields.addView(logoStatus)
    billFields.addView(logoPreview)

    val productEditors = mutableListOf<PrintSetupProductEditor>()
    val productRows = column()
    val discountMode = spinner(listOf("Giảm số tiền (đ)", "Giảm theo phần trăm (%)"))
    val discountValue = edit("Giảm giá", "0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
    val grossTotalView = label("Tạm tính: 0đ", 13f, true)
    val discountTotalView = label("Giảm giá: 0đ", 13f)
    val finalTotalView = label("Tổng thanh toán: 0đ", 15f, true)
    var refreshPreviewAction: () -> Unit = {}

    fun parsedProducts(): List<PrintSetupOrderItem> = productEditors.mapNotNull { editor ->
        val name = editor.name.text.toString().trim()
        val quantity = editor.quantity.text.toString().toLongOrNull() ?: 0L
        val unitPrice = editor.unitPrice.text.toString().toLongOrNull() ?: 0L
        if (name.isBlank() || quantity <= 0L || unitPrice < 0L) null
        else PrintSetupOrderItem(name, quantity, unitPrice)
    }

    fun updateOrderSummary() {
        val products = parsedProducts()
        productEditors.forEach { editor ->
            val quantity = editor.quantity.text.toString().toLongOrNull() ?: 0L
            val unitPrice = editor.unitPrice.text.toString().toLongOrNull() ?: 0L
            val subtotal = if (quantity > 0L && unitPrice > Long.MAX_VALUE / quantity) Long.MAX_VALUE else quantity * unitPrice
            editor.amountView.text = "Thành tiền: ${formatPrintMoney(subtotal)}"
        }
        val gross = products.fold(0L) { total, item -> addPrintMoney(total, item.subtotal) }
        val discount = calculatePrintDiscount(gross, discountValue.text.toString(), discountMode.selectedItemPosition)
        grossTotalView.text = "Tạm tính: ${formatPrintMoney(gross)}"
        discountTotalView.text = "Giảm giá: ${formatPrintMoney(discount)}"
        finalTotalView.text = "Tổng thanh toán: ${formatPrintMoney(gross - discount)}"
        refreshPreviewAction()
    }

    fun addProductRow() {
        val card = column().apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            styleCard(this)
        }
        val name = edit("Tên sản phẩm")
        val quantity = edit("SL", "1", InputType.TYPE_CLASS_NUMBER)
        val unitPrice = edit("Đơn giá", "0", InputType.TYPE_CLASS_NUMBER)
        val amountView = label("Thành tiền: 0đ", 12f, true)
        lateinit var editor: PrintSetupProductEditor
        card.addView(row(label("Mặt hàng", 13f, true) to 1f, btn("Xóa") {
            productEditors.remove(editor)
            productRows.removeView(card)
            updateOrderSummary()
        } to 0.55f))
        card.addView(name)
        card.addView(row(quantity to 0.8f, unitPrice to 1.2f))
        card.addView(amountView)
        editor = PrintSetupProductEditor(name, quantity, unitPrice, amountView, card)
        productEditors.add(editor)
        productRows.addView(card)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateOrderSummary()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        name.addTextChangedListener(watcher)
        quantity.addTextChangedListener(watcher)
        unitPrice.addTextChangedListener(watcher)
        updateOrderSummary()
    }

    billFields.addView(label("Mặt hàng trong đơn", 15f, true))
    billFields.addView(productRows)
    billFields.addView(btn("+ Thêm dòng") { addProductRow() })
    billFields.addView(label("Giảm giá", 14f, true))
    billFields.addView(row(discountMode to 1.25f, discountValue to 0.75f))
    billFields.addView(grossTotalView)
    billFields.addView(discountTotalView)
    billFields.addView(finalTotalView)
    addProductRow()
    discountMode.onItemSelectedListener = spinnerListener {
        discountValue.inputType = if (discountMode.selectedItemPosition == 1) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        } else {
            InputType.TYPE_CLASS_NUMBER
        }
        updateOrderSummary()
    }
    discountValue.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateOrderSummary()
        override fun afterTextChanged(s: Editable?) = Unit
    })
    designConfig.addView(billFields)

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
    val labelPreview = PrintSetupLabelPreview(this).apply { visibility = View.GONE }
    val previewHost = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
    designConfig.addView(label("Xem trước", 15f, true))
    previewHost.addView(preview)
    previewHost.addView(labelPreview)
    designConfig.addView(previewHost)

    fun currentPreview(): String {
        if (kindPicker.selectedItemPosition == 1) {
            val (w, h) = labelDimensions(labelSizePicker.selectedItemPosition)
            val copies = if (labelTypePicker.selectedItemPosition == 1) "Tem đôi · $w × $h mm" else "Tem đơn · $w × $h mm"
            return "$copies\n${labelTitle.text}\n${labelNote.text}\n${labelCode.text}"
        }
        val width = if (billWidthPicker.selectedItemPosition == 1) 80 else 58
        val gross = parsedProducts().fold(0L) { total, item -> addPrintMoney(total, item.subtotal) }
        val discount = calculatePrintDiscount(gross, discountValue.text.toString(), discountMode.selectedItemPosition)
        val lines = PrintSetupTemplates.billLines(
            width = width,
            shopName = shopName.text.toString(),
            phone = shopPhone.text.toString(),
            address = shopAddress.text.toString(),
            title = documentTitle.text.toString(),
            footer = footer.text.toString(),
            items = parsedProducts(),
            gross = gross,
            discount = discount,
            removeAccents = ::removeAccents
        )
        return "K$width\n" + lines.joinToString("\n") { it.text }
    }

    fun refreshPreview() {
        if (kindPicker.selectedItemPosition == 1) {
            val (labelWidthMm, labelHeightMm) = labelDimensions(labelSizePicker.selectedItemPosition)
            val isDouble = labelTypePicker.selectedItemPosition == 1
            val mediaWidthMm = if (isDouble) labelWidthMm * 2 else labelWidthMm
            val maxWidthPx = min(dp(340), (resources.displayMetrics.widthPixels - dp(48)).coerceAtLeast(dp(180)))
            val maxHeightPx = dp(420)
            val scale = min(maxWidthPx.toFloat() / mediaWidthMm, maxHeightPx.toFloat() / labelHeightMm)
            val previewWidthPx = (mediaWidthMm * scale).roundToInt().coerceAtLeast(dp(120))
            val previewHeightPx = (labelHeightMm * scale).roundToInt().coerceAtLeast(dp(120))
            preview.visibility = View.GONE
            labelPreview.visibility = View.VISIBLE
            labelPreview.setContent(
                labelWidthMm,
                labelHeightMm,
                isDouble,
                labelTitle.text.toString(),
                labelNote.text.toString(),
                labelCode.text.toString()
            )
            labelPreview.layoutParams = LinearLayout.LayoutParams(previewWidthPx, previewHeightPx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
            }
        } else {
            preview.visibility = View.VISIBLE
            labelPreview.visibility = View.GONE
            preview.text = currentPreview()
            preview.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            preview.setPadding(dp(12), dp(12), dp(12), dp(12))
            preview.textSize = 12f
        }
    }
    refreshPreviewAction = ::refreshPreview
    var updatePrintButtonLabel: () -> Unit = {}
    val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshPreview()
        override fun afterTextChanged(s: Editable?) = Unit
    }
    listOf(shopName, shopPhone, shopAddress, documentTitle, footer, labelTitle, labelNote, labelCode).forEach { it.addTextChangedListener(watcher) }
    listOf(billWidthPicker, labelSizePicker, labelTypePicker).forEach { picker ->
        picker.onItemSelectedListener = spinnerListener {
            if (picker === billWidthPicker && logoUri[0] != null) showLogo(logoUri[0])
            refreshPreview()
        }
    }
    kindPicker.onItemSelectedListener = spinnerListener {
        updateTypeChoices()
        labelFields.visibility = if (kindPicker.selectedItemPosition == 1) View.VISIBLE else View.GONE
        billFields.visibility = if (kindPicker.selectedItemPosition == 0) View.VISIBLE else View.GONE
        updatePrintButtonLabel()
        refreshPreview()
    }

    fun loadConfig(printer: SavedPrinter, template: PrintSetupTemplate) {
        val config = template.config
        shopName.setText(config.optString("shopName", "CỬA HÀNG"))
        shopPhone.setText(config.optString("shopPhone", "0900 000 000"))
        shopAddress.setText(config.optString("shopAddress", "Địa chỉ cửa hàng"))
        documentTitle.setText(config.optString("documentTitle", "HÓA ĐƠN BÁN HÀNG"))
        footer.setText(config.optString("footer", "Cảm ơn quý khách!"))
        labelTitle.setText(config.optString("labelTitle", "SẢN PHẨM MẪU"))
        labelNote.setText(config.optString("labelNote", "Giá: 35.000đ"))
        labelCode.setText(config.optString("labelCode", "SP-001"))
        val savedLogo = config.optString("logoUri", "").takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        showLogo(savedLogo)
        kindPicker.setSelection(config.optInt("kind", 0).coerceIn(0, 1))
        billWidthPicker.setSelection(if (config.optInt("paperWidth", printer.paperWidth) >= 80) 1 else 0)
        labelSizePicker.setSelection(config.optInt("labelSize", 0).coerceIn(0, 4))
        labelTypePicker.setSelection(config.optInt("labelType", 0).coerceIn(0, 1))
        labelFields.visibility = if (kindPicker.selectedItemPosition == 1) View.VISIBLE else View.GONE
        billFields.visibility = if (kindPicker.selectedItemPosition == 0) View.VISIBLE else View.GONE
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
            .put("logoUri", logoUri[0]?.toString() ?: "")
            .put("kind", kindPicker.selectedItemPosition)
            .put("paperWidth", if (billWidthPicker.selectedItemPosition == 1) 80 else 58)
            .put("labelSize", labelSizePicker.selectedItemPosition)
            .put("labelType", labelTypePicker.selectedItemPosition)
        val set = templateSetState[0] ?: return
        val selected = set.templates.firstOrNull { it.id == selectedTemplate(printer)?.id } ?: return
        selected.config = JSONObject(config.toString())
        set.selectedId = selected.id
        PrintSetupTemplateStore.save(savedTemplates, printer.id, set)
        printer.printKind = kindPicker.selectedItemPosition
        if (kindPicker.selectedItemPosition == 0) {
            printer.paperWidth = if (billWidthPicker.selectedItemPosition == 1) 80 else 58
        } else {
            printer.labelSize = labelSizePicker.selectedItemPosition.coerceIn(0, 4)
            printer.labelType = labelTypePicker.selectedItemPosition.coerceIn(0, 1)
        }
        PrinterStore.saveOrUpdate(printer)
        log("Đã lưu thiết lập in cho ${printer.name}.")
    }

    val saveButton = btn("Lưu làm mẫu") {
        val printer = printers.getOrNull(printerPicker.selectedItemPosition) ?: return@btn
        saveConfig(printer)
    }
    val printButton = btn(if (kindPicker.selectedItemPosition == 0) "In hóa đơn này" else "In thử tem") {
        val printer = printers.getOrNull(printerPicker.selectedItemPosition) ?: return@btn
        val isBill = kindPicker.selectedItemPosition == 0
        val items = if (isBill) parsedProducts() else emptyList()
        if (isBill && items.isEmpty()) {
            log("Hãy nhập ít nhất một mặt hàng có tên và số lượng lớn hơn 0 trước khi in.")
            return@btn
        }
        saveConfig(printer)
        PrinterManager.setActivePrinter(this@pagePrintSetup, printer.id)
        applySavedPrinter(printer)
        val data = if (isBill) {
            val gross = items.fold(0L) { total, item -> addPrintMoney(total, item.subtotal) }
            val discount = calculatePrintDiscount(gross, discountValue.text.toString(), discountMode.selectedItemPosition)
            PrintSetupTemplates.bill(
                width = if (billWidthPicker.selectedItemPosition == 1) 80 else 58,
                shopName = shopName.text.toString(),
                phone = shopPhone.text.toString(),
                address = shopAddress.text.toString(),
                title = documentTitle.text.toString(),
                footer = footer.text.toString(),
                items = items,
                gross = gross,
                discount = discount,
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
        val printLabel = if (isBill) "In hóa đơn K${printer.paperWidth}" else "In thử tem TSPL"
        val currentLogo = if (isBill) logoUri[0] else null
        if (currentLogo == null) {
            sendPrintData(printLabel, data)
        } else {
            val imageWidth = if (billWidthPicker.selectedItemPosition == 1) 576 else 384
            thread(name = "xp-invoice-with-logo") {
                val bitmap = PrintImageProcessor.decodeImage(contentResolver, currentLogo, imageWidth)
                if (bitmap != null) {
                    sendPrintBitmap("Logo trước hóa đơn", bitmap, imageWidth)
                    bitmap.recycle()
                } else {
                    log("Không đọc được logo; hóa đơn vẫn được xếp in không logo.")
                }
                sendPrintData(printLabel, data)
            }
        }
    }
    updatePrintButtonLabel = {
        printButton.text = if (kindPicker.selectedItemPosition == 0) "In hóa đơn này" else "In thử tem"
    }
    updatePrintButtonLabel()
    designConfig.addView(row(saveButton to 1f, printButton to 1f))
    setupPanel.addView(designConfig)

    val continueButton = btn("Sửa / thiết lập mẫu") {}
    continueButton.setOnClickListener {
        val printer = printers.getOrNull(printerPicker.selectedItemPosition) ?: return@setOnClickListener
        val chosenKind = kindPicker.selectedItemPosition
        val chosenBillWidth = billWidthPicker.selectedItemPosition
        val chosenLabelSize = labelSizePicker.selectedItemPosition
        val chosenLabelType = labelTypePicker.selectedItemPosition
        val template = selectedTemplate(printer) ?: return@setOnClickListener
        loadConfig(printer, template)
        kindPicker.setSelection(chosenKind)
        billWidthPicker.setSelection(chosenBillWidth)
        labelSizePicker.setSelection(chosenLabelSize)
        labelTypePicker.setSelection(chosenLabelType)
        updateTypeChoices()
        labelFields.visibility = if (chosenKind == 1) View.VISIBLE else View.GONE
        billFields.visibility = if (chosenKind == 0) View.VISIBLE else View.GONE
        refreshPreview()
        selectionPanel.visibility = View.GONE
        continueButton.visibility = View.GONE
        setupPanel.visibility = View.VISIBLE
    }
    body.addView(continueButton)
    setupPanel.addView(btn("Quay lại chọn máy in / khổ giấy") {
        printers.getOrNull(printerPicker.selectedItemPosition)?.let { saveConfig(it) }
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

private fun formatPrintMoney(amount: Long): String = String.format(Locale.US, "%,dđ", amount.coerceAtLeast(0L))

private fun addPrintMoney(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun calculatePrintDiscount(gross: Long, rawValue: String, mode: Int): Long {
    if (gross <= 0L) return 0L
    return if (mode == 1) {
        val percent = rawValue.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
        if (percent <= 0.0) 0L else if (percent >= 100.0) gross else (gross.toDouble() * percent / 100.0).toLong().coerceIn(0L, gross)
    } else {
        (rawValue.toLongOrNull() ?: 0L).coerceIn(0L, gross)
    }
}

private fun wrapPrintText(text: String, columns: Int): List<String> {
    if (text.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    var line = StringBuilder()
    for (word in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
        if (word.length > columns) {
            if (line.isNotEmpty()) {
                result += line.toString()
                line = StringBuilder()
            }
            var remaining = word
            while (remaining.length > columns) {
                result += remaining.take(columns)
                remaining = remaining.drop(columns)
            }
            if (remaining.isNotEmpty()) line.append(remaining)
        } else if (line.isEmpty()) {
            line.append(word)
        } else if (line.length + 1 + word.length <= columns) {
            line.append(' ').append(word)
        } else {
            result += line.toString()
            line = StringBuilder(word)
        }
    }
    if (line.isNotEmpty()) result += line.toString()
    return result
}

private object PrintSetupTemplates {
    fun bill(
        width: Int,
        shopName: String,
        phone: String,
        address: String,
        title: String,
        footer: String,
        items: List<PrintSetupOrderItem>,
        gross: Long,
        discount: Long,
        removeAccents: (String) -> String,
        commandBytes: (IntArray) -> ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(commandBytes(intArrayOf(0x1B, 0x40)))
        var currentAlignment = -1
        billLines(width, shopName, phone, address, title, footer, items, gross, discount, removeAccents).forEach { receiptLine ->
            if (receiptLine.alignment != currentAlignment) {
                out.write(commandBytes(intArrayOf(0x1B, 0x61, receiptLine.alignment)))
                currentAlignment = receiptLine.alignment
            }
            out.write(receiptLine.text.toByteArray(Charsets.US_ASCII))
            out.write(byteArrayOf('\n'.code.toByte()))
        }
        out.write(byteArrayOf('\n'.code.toByte(), '\n'.code.toByte(), '\n'.code.toByte()))
        out.write(commandBytes(intArrayOf(0x1D, 0x56, 0x00)))
        return out.toByteArray()
    }

    fun billLines(
        width: Int,
        shopName: String,
        phone: String,
        address: String,
        title: String,
        footer: String,
        items: List<PrintSetupOrderItem>,
        gross: Long,
        discount: Long,
        removeAccents: (String) -> String
    ): List<PrintSetupReceiptLine> {
        val columns = if (width <= 58) 32 else 48
        val lines = mutableListOf<PrintSetupReceiptLine>()
        fun addWrapped(value: String, alignment: Int = 0) {
            wrapPrintText(removeAccents(value), columns).forEach { lines += PrintSetupReceiptLine(it, alignment) }
        }

        addWrapped(shopName.ifBlank { "CỬA HÀNG" }, 1)
        if (phone.isNotBlank()) addWrapped("ĐT: $phone", 1)
        if (address.isNotBlank()) addWrapped(address, 1)
        lines += PrintSetupReceiptLine("-".repeat(columns))
        addWrapped(title.ifBlank { "HÓA ĐƠN BÁN HÀNG" }, 1)
        addWrapped(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date()), 1)
        lines += PrintSetupReceiptLine("-".repeat(columns))
        if (items.isEmpty()) {
            addWrapped("Chưa có mặt hàng")
        } else {
            items.forEach { item ->
                addWrapped(item.name)
                val detail = removeAccents("${item.quantity} x ${formatPrintMoney(item.unitPrice)}")
                val amount = removeAccents(formatPrintMoney(item.subtotal))
                if (detail.length + amount.length + 1 <= columns) {
                    lines += PrintSetupReceiptLine(detail.padEnd(columns - amount.length) + amount)
                } else {
                    addWrapped("$detail = $amount")
                }
            }
        }
        lines += PrintSetupReceiptLine("-".repeat(columns))
        addWrapped("TẠM TÍNH: ${formatPrintMoney(gross)}", 2)
        if (discount > 0L) addWrapped("GIẢM GIÁ: -${formatPrintMoney(discount)}", 2)
        addWrapped("TỔNG THANH TOÁN: ${formatPrintMoney(gross - discount)}", 2)
        if (footer.isNotBlank()) addWrapped(footer, 1)
        return lines
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
