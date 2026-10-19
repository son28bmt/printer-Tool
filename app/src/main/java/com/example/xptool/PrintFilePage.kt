package com.example.xptool

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.text.InputType
import net.posprinter.POSConnect
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

private data class PrintArea(
    val widthPx: Int,
    val heightPx: Int?,
    val marginPx: Int,
    val sizeLabel: String,
    val marginMm: Float
)

private fun formatPrintMillimeters(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

private val printAreaOptions = listOf(
    "Cuộn K58 · vùng in ~48 mm",
    "Cuộn K80 · vùng in ~72 mm",
    "Tem 40 × 30 mm",
    "Tem 30 × 40 mm",
    "Tem vận đơn 100 × 150 mm",
    "Tự nhập kích thước"
)

internal fun MainActivity.pagePrintFiles(uri: Uri? = null, mimeType: String? = null) = showPage("In PDF / ảnh", false) { body ->
    val resolvedType = uri?.let { mimeType ?: contentResolver.getType(it) }.orEmpty()
    val isPdf = resolvedType.equals("application/pdf", true) || uri?.lastPathSegment.orEmpty().endsWith(".pdf", true)
    val pageCount = if (uri != null && isPdf) runCatching {
        contentResolver.openFileDescriptor(uri, "r")?.use { PdfToPrintHelper.pdfPageCount(it) } ?: 0
    }.getOrElse { error ->
        log("Không mở được PDF: ${error.message ?: "tệp lỗi hoặc không được phép đọc"}")
        0
    } else 0

    body.addView(label("Chọn PDF hoặc ảnh rồi xem trước trước khi in. Nội dung sẽ được chuyển sang đen trắng.", 14f))
    body.addView(label("Chọn khổ giấy trước khi mở tệp. Nội dung sẽ tự thu phóng, căn giữa và giữ nguyên tỉ lệ; kích thước dot chỉ là ước lượng theo máy in.", 12f).apply { setTextColor(colorMuted) })
    val savedPrinters = PrinterStore.printers.toList()
    fun printerDescription(printer: SavedPrinter): String {
        val transport = when (printer.connType) {
            POSConnect.DEVICE_TYPE_BLUETOOTH -> "Bluetooth ${printer.btMac.ifBlank { printer.mac.orEmpty() }}"
            POSConnect.DEVICE_TYPE_USB -> "USB ${printer.usbPath}"
            else -> "${printer.lastIp}:${printer.port}"
        }
        return "${printer.name} · $transport"
    }
    val printerPicker = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@pagePrintFiles,
            android.R.layout.simple_spinner_dropdown_item,
            savedPrinters.map { printerDescription(it) }
        )
        setSelection(savedPrinters.indexOfFirst { it.id == PrinterStore.activePrinterKey }.coerceAtLeast(0))
        visibility = if (savedPrinters.isEmpty()) View.GONE else View.VISIBLE
    }
    body.addView(label("Máy in", 14f, true))
    body.addView(printerPicker)
    if (savedPrinters.isEmpty()) {
        body.addView(label("Chưa có máy in đã lưu. Hãy chọn giao tiếp và lưu máy in trước khi in tệp.", 12f).apply { setTextColor(colorMuted) })
        body.addView(btn("Kết nối / lưu máy in") { pageSelectConnection() })
    }
    val selectedFile = label(uri?.lastPathSegment ?: "Chưa chọn tệp", 13f)
    val areaPicker = Spinner(this).apply {
        adapter = ArrayAdapter(this@pagePrintFiles, android.R.layout.simple_spinner_dropdown_item, printAreaOptions)
        setSelection(if (paperWidth >= 80) 1 else 0)
    }
    body.addView(label("Khổ giấy / vùng in", 14f, true))
    body.addView(areaPicker)
    body.addView(label("Vùng in thực tế phụ thuộc máy in. Tem dùng kích thước cố định; nội dung được thu phóng vừa vùng và giữ nguyên tỉ lệ.", 12f).apply { setTextColor(colorMuted) })
    val customArea = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }
    fun dimensionInput(hint: String, initial: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(initial)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(true)
    }
    val customWidth = dimensionInput("Rộng (mm)", "48")
    val customHeight = dimensionInput("Dài (mm), 0 = cuộn liên tục", "0")
    val customMargin = dimensionInput("Lề an toàn (mm)", "1")
    customArea.addView(customWidth)
    customArea.addView(customHeight)
    customArea.addView(customMargin)
    body.addView(customArea)
    body.addView(row(
        btn("Chọn PDF") { choosePrintDocument("application/pdf") } to 1f,
        btn("Chọn ảnh") { choosePrintDocument("image/*") } to 1f
    ))
    body.addView(selectedFile)

    val pagePicker = Spinner(this).apply {
        adapter = ArrayAdapter(this@pagePrintFiles, android.R.layout.simple_spinner_dropdown_item, listOf("Chưa có PDF"))
        visibility = if (isPdf && pageCount > 1) View.VISIBLE else View.GONE
    }
    if (isPdf && pageCount > 0) {
        val pages = (1..pageCount).map { "Trang $it" } + "In tất cả ($pageCount trang)"
        pagePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pages)
        pagePicker.setSelection(0)
    }
    body.addView(pagePicker)
    val info = label(
        when {
            uri == null -> "Chưa chọn tệp."
            isPdf && pageCount == 0 -> "PDF không đọc được hoặc không có trang. Hãy chọn tệp khác."
            isPdf -> "PDF có $pageCount trang. Chọn một trang hoặc in toàn bộ."
            resolvedType.startsWith("image/") -> "Ảnh đã sẵn sàng để xem trước."
            else -> "Định dạng chưa hỗ trợ. Chọn PDF hoặc ảnh JPG/PNG."
        },
        13f
    ).apply { setTextColor(colorMuted) }
    body.addView(info)
    val preview = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        minimumHeight = dp(240)
        maxHeight = dp(420)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            dp(360)
        )
        styleCard(this)
    }
    body.addView(preview)
    val renderVersion = AtomicInteger()
    var shownBitmap: Bitmap? = null

    fun selectedArea(): PrintArea {
        val mmToDots = 8f // common 203 dpi thermal heads; printer-dependent estimate
        val position = areaPicker.selectedItemPosition
        val widthMm: Float
        val heightMm: Float?
        when (position) {
            1 -> { widthMm = 72f; heightMm = null }
            2 -> { widthMm = 40f; heightMm = 30f }
            3 -> { widthMm = 30f; heightMm = 40f }
            4 -> { widthMm = 100f; heightMm = 150f }
            5 -> {
                widthMm = customWidth.text.toString().toFloatOrNull()?.coerceIn(20f, 120f) ?: 48f
                val enteredHeight = customHeight.text.toString().toFloatOrNull()?.coerceIn(0f, 300f) ?: 0f
                heightMm = enteredHeight.takeIf { it > 0f }
            }
            else -> { widthMm = 48f; heightMm = null }
        }
        val marginMm = if (position == 5) customMargin.text.toString().toFloatOrNull()?.coerceIn(0f, 10f) ?: 1f else 1f
        val width = (widthMm * mmToDots).roundToInt().coerceAtLeast(1)
        val height = heightMm?.let { (it * mmToDots).roundToInt().coerceAtLeast(1) }
        val margin = (marginMm * mmToDots).roundToInt().coerceAtLeast(0).coerceAtMost((width - 1) / 2)
        val sizeLabel = heightMm?.let { "${formatPrintMillimeters(widthMm)} × ${formatPrintMillimeters(it)} mm" }
            ?: "cuộn ${formatPrintMillimeters(widthMm)} mm"
        return PrintArea(width, height, margin, sizeLabel, marginMm)
    }

    fun renderPage(fileUri: Uri, pageIndex: Int, area: PrintArea): Bitmap? = try {
        if (isPdf) {
            contentResolver.openFileDescriptor(fileUri, "r")?.use { PdfToPrintHelper.renderPdfPage(it, pageIndex, area.widthPx, area.heightPx, area.marginPx) }
        } else {
            PrintImageProcessor.decodeImage(contentResolver, fileUri, area.widthPx, area.heightPx, area.marginPx)
        }
    } catch (error: Exception) {
        log("Không xử lý được tệp: ${error.message ?: "lỗi đọc tệp"}")
        null
    }

    fun refreshPreview() {
        val fileUri = uri ?: return
        if (!isPdf && !resolvedType.startsWith("image/")) return
        if (isPdf && pageCount == 0) return
        val index = if (isPdf && pagePicker.selectedItemPosition == pageCount) 0 else pagePicker.selectedItemPosition.coerceAtLeast(0)
        val area = selectedArea()
        val version = renderVersion.incrementAndGet()
        info.text = if (isPdf) "Đang dựng xem trước trang ${index + 1}…" else "Đang dựng ảnh xem trước…"
        thread(name = "xp-file-preview") {
            val bitmap = renderPage(fileUri, index, area)
            runOnUiThread {
                if (version != renderVersion.get()) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                val oldBitmap = shownBitmap
                shownBitmap = bitmap
                preview.setImageBitmap(bitmap)
                if (oldBitmap != null && oldBitmap !== bitmap && !oldBitmap.isRecycled) oldBitmap.recycle()
                info.text = if (bitmap == null) "Không dựng được xem trước. Kiểm tra tệp rồi thử lại." else "Khổ chọn: ${area.sizeLabel} · raster ${area.widthPx} × ${bitmap.height} dot (ước lượng 203 dpi), lề ${formatPrintMillimeters(area.marginMm)} mm."
            }
        }
    }

    val printButton = btn(if (isPdf) "In PDF" else "In ảnh") {}
    printButton.setOnClickListener {
        val fileUri = uri ?: return@setOnClickListener log("Hãy chọn tệp trước.")
        if (isPdf && pageCount == 0) return@setOnClickListener log("PDF chưa đọc được nên chưa thể in.")
        if (!isPdf && !resolvedType.startsWith("image/")) return@setOnClickListener log("Định dạng chưa hỗ trợ.")
        val selectedPrinter = savedPrinters.getOrNull(printerPicker.selectedItemPosition)
            ?: return@setOnClickListener log("Hãy kết nối và lưu máy in trước khi in tệp.")
        PrinterManager.setActivePrinter(this@pagePrintFiles, selectedPrinter.id)
        applySavedPrinter(selectedPrinter)
        val area = selectedArea()
        val selectedPage = pagePicker.selectedItemPosition
        val pages = if (isPdf && selectedPage == pageCount) (0 until pageCount).toList() else listOf(selectedPage.coerceAtLeast(0))
        printButton.isEnabled = false
        printButton.text = "Đang chuẩn bị…"
        thread(name = "xp-file-print") {
            var queuedCount = 0
            for (pageIndex in pages) {
                val bitmap = renderPage(fileUri, pageIndex, area)
                if (bitmap == null) {
                    log("Không dựng được trang ${pageIndex + 1}; đã dừng để tránh in thiếu âm thầm.")
                    break
                }
                sendPrintBitmap(if (isPdf) "PDF trang ${pageIndex + 1}" else "Ảnh", bitmap, area.widthPx)
                bitmap.recycle()
                queuedCount++
            }
            runOnUiThread {
                printButton.isEnabled = true
                printButton.text = if (isPdf) "In PDF" else "In ảnh"
                if (queuedCount > 0) log("Đã xếp $queuedCount ảnh trang vào hàng đợi in.")
            }
        }
    }
    body.addView(printButton)
    pagePicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = refreshPreview()
    }
    areaPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            customArea.visibility = if (position == 5) View.VISIBLE else View.GONE
            refreshPreview()
        }
    }
    val customWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (areaPicker.selectedItemPosition == 5) refreshPreview() }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }
    customWidth.addTextChangedListener(customWatcher)
    customHeight.addTextChangedListener(customWatcher)
    customMargin.addTextChangedListener(customWatcher)
    if (uri != null && (isPdf && pageCount > 0 || !isPdf && resolvedType.startsWith("image/"))) refreshPreview()
}
