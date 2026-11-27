package com.jpcottin.tairoto.ui.draw

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import java.io.FileOutputStream
import java.io.IOException

/** Sends the page to colour in to the system's printing (a printer, or a PDF to keep). */
object TemplatePrint {
    fun print(context: Context) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .build()
        manager.print("Tairoto fish to colour in", Adapter(context.applicationContext), attributes)
    }

    private class Adapter(private val context: Context) : PrintDocumentAdapter() {
        private var attributes: PrintAttributes? = null

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            attributes = newAttributes
            if (cancellationSignal.isCanceled) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder("tairoto-fish.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            callback: WriteResultCallback,
        ) {
            val pdf = PrintedPdfDocument(context, attributes ?: return callback.onWriteFailed("no page size"))
            try {
                val page = pdf.startPage(0)
                // Turned to fit if the paper is upright: the squares, not the paper, frame the page.
                val c = page.canvas
                val w = page.info.contentRect.width().toFloat()
                val h = page.info.contentRect.height().toFloat()
                if (h > w) {
                    c.save()
                    c.translate(w, 0f)
                    c.rotate(90f)
                    TemplateArt.draw(c, h, w)
                    c.restore()
                } else {
                    TemplateArt.draw(c, w, h)
                }
                pdf.finishPage(page)
                if (cancellationSignal.isCanceled) {
                    callback.onWriteCancelled()
                    return
                }
                FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: IOException) {
                callback.onWriteFailed(e.message)
            } finally {
                pdf.close()
            }
        }
    }
}
