package com.example.xptool

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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
    body.addView(label("Lưu ý: PDF A4 sẽ thu nhỏ để vừa giấy nhiệt 58/80 mm, chữ nhỏ có thể khó đọc. Độ rộng 384/576 dot là ước lượng; cần kiểm tra theo máy in thật.", 12f).apply { setTextColor(colorMuted) })
    body.addView(row(
        btn("Chọn PDF") { choosePrintDocument("application/pdf") } to 1f,
        btn("Chọn ảnh") { choosePrintDocument("image/*") } to 1f
    ))

    val selectedFile = label(uri?.lastPathSegment ?: "Chưa chọn tệp", 13f)
    body.addView(selectedFile)
    val widthPicker = Spinner(this).apply {
        adapter = ArrayAdapter(this@pagePrintFiles, android.R.layout.simple_spinner_dropdown_item, listOf("58 mm · 384 dot (ước lượng)", "80 mm · 576 dot (ước lượng)"))
        setSelection(if (paperWidth >= 80) 1 else 0)
    }
    body.addView(label("Khổ giấy", 14f, true))
    body.addView(widthPicker)

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

    fun targetWidth() = if (widthPicker.selectedItemPosition == 1) 576 else 384

    fun renderPage(fileUri: Uri, pageIndex: Int, width: Int): Bitmap? = try {
        if (isPdf) {
            contentResolver.openFileDescriptor(fileUri, "r")?.use { PdfToPrintHelper.renderPdfPage(it, pageIndex, width) }
        } else {
            PrintImageProcessor.decodeImage(contentResolver, fileUri, width)
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
        val width = targetWidth()
        val version = renderVersion.incrementAndGet()
        info.text = if (isPdf) "Đang dựng xem trước trang ${index + 1}…" else "Đang dựng ảnh xem trước…"
        thread(name = "xp-file-preview") {
            val bitmap = renderPage(fileUri, index, width)
            runOnUiThread {
                if (version != renderVersion.get()) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                val oldBitmap = shownBitmap
                shownBitmap = bitmap
                preview.setImageBitmap(bitmap)
                if (oldBitmap != null && oldBitmap !== bitmap && !oldBitmap.isRecycled) oldBitmap.recycle()
                info.text = if (bitmap == null) "Không dựng được xem trước. Kiểm tra tệp rồi thử lại." else "Xem trước đã co về ${width}px và chuyển đen trắng."
            }
        }
    }

    val printButton = btn(if (isPdf) "In PDF" else "In ảnh") {}
    printButton.setOnClickListener {
        val fileUri = uri ?: return@setOnClickListener log("Hãy chọn tệp trước.")
        if (isPdf && pageCount == 0) return@setOnClickListener log("PDF chưa đọc được nên chưa thể in.")
        if (!isPdf && !resolvedType.startsWith("image/")) return@setOnClickListener log("Định dạng chưa hỗ trợ.")
        val width = targetWidth()
        val selectedPage = pagePicker.selectedItemPosition
        val pages = if (isPdf && selectedPage == pageCount) (0 until pageCount).toList() else listOf(selectedPage.coerceAtLeast(0))
        printButton.isEnabled = false
        printButton.text = "Đang chuẩn bị…"
        thread(name = "xp-file-print") {
            var queuedCount = 0
            for (pageIndex in pages) {
                val bitmap = renderPage(fileUri, pageIndex, width)
                if (bitmap == null) {
                    log("Không dựng được trang ${pageIndex + 1}; đã dừng để tránh in thiếu âm thầm.")
                    break
                }
                sendPrintBitmap(if (isPdf) "PDF trang ${pageIndex + 1}" else "Ảnh", bitmap, width)
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
    widthPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = refreshPreview()
    }
    if (uri != null && (isPdf && pageCount > 0 || !isPdf && resolvedType.startsWith("image/"))) refreshPreview()
}
