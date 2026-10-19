package com.example.xptool

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor

/** Keeps PDF page counting and rendering isolated from file-picker UI code. */
internal object PdfToPrintHelper {
    fun pdfPageCount(pfd: ParcelFileDescriptor): Int = PrintImageProcessor.pdfPageCount(pfd)

    fun renderPdfPage(
        pfd: ParcelFileDescriptor,
        pageIndex: Int,
        targetWidthPx: Int,
        targetHeightPx: Int? = null,
        marginPx: Int = 0
    ): Bitmap? = PrintImageProcessor.renderPdfPage(pfd, pageIndex, targetWidthPx, targetHeightPx, marginPx)
}
