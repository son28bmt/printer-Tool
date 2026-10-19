package com.example.xptool

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Image preparation shared by direct image and PDF printing. Widths are estimates for common POS heads. */
internal object PrintImageProcessor {
    private const val MAX_HEIGHT_PX = 4096

    fun decodeImage(
        resolver: ContentResolver,
        uri: Uri,
        targetWidthPx: Int,
        targetHeightPx: Int? = null,
        marginPx: Int = 0
    ): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Tệp ảnh không đọc được." }
        val sample = max(1, max(bounds.outWidth / (targetWidthPx * 2), bounds.outHeight / (MAX_HEIGHT_PX * 2)))
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Không mở được tệp ảnh.")
        fitToArea(decoded, targetWidthPx, targetHeightPx, marginPx)
    }.getOrNull()

    fun pdfPageCount(pfd: ParcelFileDescriptor): Int {
        val renderer = PdfRenderer(pfd)
        return try {
            renderer.pageCount
        } finally {
            renderer.close()
        }
    }

    fun renderPdfPage(
        pfd: ParcelFileDescriptor,
        pageIndex: Int,
        targetWidthPx: Int,
        targetHeightPx: Int? = null,
        marginPx: Int = 0
    ): Bitmap? {
        val renderer = try {
            PdfRenderer(pfd)
        } catch (_: Exception) {
            return null
        }
        return try {
            if (pageIndex !in 0 until renderer.pageCount) return null
            val page = renderer.openPage(pageIndex)
            try {
                val margin = marginPx.coerceAtLeast(0)
                val availableWidth = (targetWidthPx - margin * 2).coerceAtLeast(1)
                val availableHeight = targetHeightPx?.let { (it - margin * 2).coerceAtLeast(1) }
                    ?: (MAX_HEIGHT_PX - margin * 2).coerceAtLeast(1)
                val scale = min(
                    availableWidth.toFloat() / page.width,
                    availableHeight.toFloat() / page.height
                )
                val contentWidth = max(1, (page.width * scale).roundToInt())
                val contentHeight = max(1, (page.height * scale).roundToInt())
                val canvasHeight = targetHeightPx?.coerceAtLeast(1)
                    ?: (contentHeight + margin * 2).coerceAtMost(MAX_HEIGHT_PX)
                val bitmap = Bitmap.createBitmap(targetWidthPx, canvasHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                val left = ((targetWidthPx - contentWidth) / 2).coerceAtLeast(0)
                val top = ((canvasHeight - contentHeight) / 2).coerceAtLeast(0)
                page.render(
                    bitmap,
                    Rect(left, top, (left + contentWidth).coerceAtMost(targetWidthPx), (top + contentHeight).coerceAtMost(canvasHeight)),
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                )
                dither(bitmap).also { bitmap.recycle() }
            } finally {
                page.close()
            }
        } catch (_: Exception) {
            null
        } finally {
            renderer.close()
        }
    }

    fun resizeAndDither(source: Bitmap, targetWidthPx: Int): Bitmap {
        val resized = resize(source, targetWidthPx)
        return dither(resized).also { if (it !== resized && resized !== source) resized.recycle() }
    }

    private fun fitToArea(source: Bitmap, targetWidthPx: Int, targetHeightPx: Int?, marginPx: Int): Bitmap {
        val margin = marginPx.coerceAtLeast(0)
        val contentWidth = (targetWidthPx - margin * 2).coerceAtLeast(1)
        val contentHeightLimit = targetHeightPx?.let { (it - margin * 2).coerceAtLeast(1) }
            ?: (MAX_HEIGHT_PX - margin * 2).coerceAtLeast(1)
        val scale = min(contentWidth.toFloat() / source.width, contentHeightLimit.toFloat() / source.height)
        val drawWidth = max(1, (source.width * scale).roundToInt())
        val drawHeight = max(1, (source.height * scale).roundToInt())
        val canvasHeight = targetHeightPx?.coerceAtLeast(1)
            ?: (drawHeight + margin * 2).coerceAtMost(MAX_HEIGHT_PX)
        val fitted = Bitmap.createBitmap(targetWidthPx, canvasHeight, Bitmap.Config.ARGB_8888)
        fitted.eraseColor(Color.WHITE)
        val canvas = Canvas(fitted)
        val left = (targetWidthPx - drawWidth) / 2f
        val top = (canvasHeight - drawHeight) / 2f
        canvas.drawBitmap(source, null, android.graphics.RectF(left, top, left + drawWidth, top + drawHeight), Paint(Paint.FILTER_BITMAP_FLAG))
        if (!source.isRecycled) source.recycle()
        return dither(fitted).also { fitted.recycle() }
    }

    private fun resize(source: Bitmap, targetWidthPx: Int): Bitmap {
        val scale = min(targetWidthPx.toFloat() / source.width, MAX_HEIGHT_PX.toFloat() / source.height)
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        if (source.width == width && source.height == height) return source
        return Bitmap.createScaledBitmap(source, width, height, true).also {
            if (it !== source) source.recycle()
        }
    }

    private fun dither(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = FloatArray(pixels.size) { index ->
            val color = pixels[index]
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            (red * 299 + green * 587 + blue * 114) / 1000f
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val old = gray[index].coerceIn(0f, 255f)
                val next = if (old >= 128f) 255f else 0f
                val error = old - next
                pixels[index] = if (next == 0f) Color.BLACK else Color.WHITE
                if (x + 1 < width) gray[index + 1] += error * 7f / 16f
                if (y + 1 < height) {
                    if (x > 0) gray[index + width - 1] += error * 3f / 16f
                    gray[index + width] += error * 5f / 16f
                    if (x + 1 < width) gray[index + width + 1] += error / 16f
                }
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
