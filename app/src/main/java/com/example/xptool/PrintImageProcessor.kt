package com.example.xptool

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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

    fun decodeImage(resolver: ContentResolver, uri: Uri, targetWidthPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Tệp ảnh không đọc được." }
        val sample = max(1, max(bounds.outWidth / (targetWidthPx * 2), bounds.outHeight / (MAX_HEIGHT_PX * 2)))
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Không mở được tệp ảnh.")
        val resized = resize(decoded, targetWidthPx)
        dither(resized).also { if (it !== resized) resized.recycle() }
    }.getOrNull()

    fun pdfPageCount(pfd: ParcelFileDescriptor): Int {
        val renderer = PdfRenderer(pfd)
        return try {
            renderer.pageCount
        } finally {
            renderer.close()
        }
    }

    fun renderPdfPage(pfd: ParcelFileDescriptor, pageIndex: Int, targetWidthPx: Int): Bitmap? {
        val renderer = try {
            PdfRenderer(pfd)
        } catch (_: Exception) {
            return null
        }
        return try {
            if (pageIndex !in 0 until renderer.pageCount) return null
            val page = renderer.openPage(pageIndex)
            try {
                val scale = min(targetWidthPx.toFloat() / page.width, MAX_HEIGHT_PX.toFloat() / page.height)
                val width = max(1, (page.width * scale).roundToInt())
                val height = max(1, (page.height * scale).roundToInt())
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, Rect(0, 0, width, height), null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
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
