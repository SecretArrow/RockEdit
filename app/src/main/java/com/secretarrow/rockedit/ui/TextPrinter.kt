package com.secretarrow.rockedit.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.secretarrow.rockedit.core.PrintLayout
import java.io.FileOutputStream

/**
 * Prints the current document via the Android print framework (roadmap:
 * print). Pagination is computed by [PrintLayout]; pages are rendered into a
 * temporary PDF that the framework spools to the chosen service.
 */
object TextPrinter {
    fun print(
        context: Context,
        jobName: String,
        text: String,
    ) {
        val printManager =
            context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                ?: return
        printManager.print(
            jobName,
            TextPrintDocumentAdapter(jobName, text),
            PrintAttributes
                .Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build(),
        )
    }
}

class TextPrintDocumentAdapter(
    private val jobName: String,
    private val text: String,
) : PrintDocumentAdapter() {
    private var pages: List<List<String>> = emptyList()

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        pages =
            PrintLayout.paginate(
                text,
                PrintLayout.DEFAULT_LINES_PER_PAGE,
                PrintLayout.DEFAULT_CHARS_PER_LINE,
            )
        val info =
            PrintDocumentInfo
                .Builder("$jobName.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pages.size)
                .build()
        callback.onLayoutFinished(info, newAttributes != oldAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            val document = PdfDocument()
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.MONOSPACE
                    textSize = PrintLayout.DEFAULT_TEXT_SIZE_POINTS
                }
            val margin = PrintLayout.DEFAULT_MARGIN_POINTS
            val lineHeight = PrintLayout.DEFAULT_LINE_HEIGHT_POINTS

            this.pages.forEachIndexed { index, lines ->
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    document.close()
                    return
                }
                val pageInfo =
                    PdfDocument.PageInfo
                        .Builder(
                            PrintLayout.PAGE_WIDTH_POINTS,
                            PrintLayout.PAGE_HEIGHT_POINTS,
                            index + 1,
                        ).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                var y = margin + lineHeight
                for (line in lines) {
                    if (line.isNotEmpty()) canvas.drawText(line, margin.toFloat(), y, paint)
                    y += lineHeight
                }
                document.finishPage(page)
            }
            FileOutputStream(destination.fileDescriptor).use { out ->
                document.writeTo(out)
            }
            document.close()
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}
