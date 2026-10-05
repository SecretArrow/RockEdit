package com.secretarrow.rockedit.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.secretarrow.rockedit.core.PdfPlan
import com.secretarrow.rockedit.core.PrintLayout
import com.secretarrow.rockedit.core.SyntaxTokenType
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/** Sealed result contract of [PdfExporter.export]: no branch can be missed. */
sealed class PdfExportResult {
    data class Success(
        val pages: Int,
        val bytes: Long,
    ) : PdfExportResult()

    data class Failure(
        val message: String,
    ) : PdfExportResult()
}

/**
 * Thin Android renderer for the colored PDF export (backlog #12). It paints
 * a [PdfPlan] produced by `core/PdfExportPlanner` into an A4 PDF and writes
 * it to a SAF [Uri]. All pagination, wrapping, and coloring decisions were
 * already made by the planner; this object only draws and streams, so the
 * risky logic stays unit-testable on the JVM.
 *
 * The document uses a fixed light print palette (not the app theme) so the
 * PDF is always readable on white paper, in print and in dark mode viewers.
 *
 * Threading: [export] blocks (render + serialize + stream write). Callers
 * must invoke it off the main thread (e.g. `Dispatchers.IO`).
 *
 * Defensive rules (event -> handling; there is no dead end, every path
 * returns a result with an informative message and the document is always
 * closed):
 *
 * | # | Phase  | Event                       | Handling                                 |
 * |---|--------|-----------------------------|------------------------------------------|
 * | 1 | Render | Invalid page geometry       | Failure: "PDF page rendering failed".    |
 * | 2 | Render | Any unexpected throwable    | Failure naming the exception kind, no    |
 * |   |        |                             | stack leak.                              |
 * | 3 | Open   | FileNotFoundException       | Failure: target not found / creatable.   |
 * | 4 | Open   | SecurityException           | Failure: write permission denied.        |
 * | 5 | Open   | IOException / IllegalState  | Failure: I/O error / resolver state.     |
 * | 6 | Open   | Provider returned null      | Failure: provider returned no stream.    |
 * | 7 | Write  | IOException / IllegalState  | Failure: writing PDF data failed;        |
 * |   |        | or anything unexpected      | storage full or stream broken.           |
 * | 8 | Always | -                           | document.close() in finally; streams     |
 * |   |        |                             | closed via use.                          |
 *
 * Documented v1 tradeoff: the PDF is serialized to an in-memory buffer first
 * so [PdfExportResult.Success.bytes] is exact; input is bounded by the
 * planner's `MAX_EXPORT_LINES` (100k lines), so the buffer stays in the
 * low megabyte range.
 */
object PdfExporter {
    /** Fixed light print palette (print-safe on white paper). */
    private const val COLOR_HEADER = 0xFF808080.toInt()
    private const val COLOR_LINE_NUMBER = 0xFF9E9E9E.toInt()
    private const val COLOR_PLAIN = 0xFF1A1A1A.toInt()
    private const val COLOR_KEYWORD = 0xFF6A3AB2.toInt()
    private const val COLOR_STRING = 0xFF2E7D32.toInt()
    private const val COLOR_COMMENT = 0xFF8A8A8A.toInt()
    private const val COLOR_NUMBER = 0xFFB25000.toInt()

    /** Header baseline and size; content starts below it. */
    private const val HEADER_BASELINE_Y = 24f
    private const val HEADER_TEXT_SIZE = 8f

    /** Right edge of the line-number column and content start when numbered. */
    private const val NUMBER_RIGHT_X = PrintLayout.DEFAULT_MARGIN_POINTS + 28f
    private const val NUMBERED_TEXT_X = PrintLayout.DEFAULT_MARGIN_POINTS + 40f

    /** Job names longer than this are cut with an ellipsis in the header. */
    private const val MAX_JOB_NAME_CHARS = 60
    private const val ELLIPSIS = "…"

    /** Initial capacity of the serialization buffer. */
    private const val INITIAL_BUFFER_CHARS = 64 * 1024

    /**
     * Renders [plan] and writes the PDF to [target]. Runs on the calling
     * thread; wrap in a background dispatcher.
     */
    fun export(
        context: Context,
        plan: PdfPlan,
        jobName: String,
        target: Uri,
    ): PdfExportResult {
        val document = PdfDocument()
        try {
            return renderAndWrite(context, document, plan, jobName, target)
        } finally {
            document.close()
        }
    }

    private fun renderAndWrite(
        context: Context,
        document: PdfDocument,
        plan: PdfPlan,
        jobName: String,
        target: Uri,
    ): PdfExportResult {
        // Phase 1: vector-render every planned page into the document.
        try {
            drawPages(document, plan, jobName)
        } catch (e: IllegalArgumentException) {
            return PdfExportResult.Failure(
                "PDF page rendering failed: invalid page geometry " +
                    "(${e.message ?: "no detail"}).",
            )
        } catch (e: IllegalStateException) {
            return PdfExportResult.Failure(
                "PDF page rendering failed: page already finished or document closed " +
                    "(${e.javaClass.simpleName}).",
            )
        } catch (e: Exception) {
            return PdfExportResult.Failure(
                "PDF page rendering failed unexpectedly (${e.javaClass.simpleName}).",
            )
        }
        // Phase 2: open the SAF target stream.
        val out: OutputStream? =
            try {
                context.contentResolver.openOutputStream(target)
            } catch (e: FileNotFoundException) {
                return PdfExportResult.Failure(
                    "Cannot open export target: location not found or not creatable ($target).",
                )
            } catch (e: SecurityException) {
                return PdfExportResult.Failure(
                    "Cannot open export target: write permission denied ($target).",
                )
            } catch (e: IOException) {
                return PdfExportResult.Failure(
                    "Cannot open export target: I/O error ($target).",
                )
            } catch (e: IllegalStateException) {
                return PdfExportResult.Failure(
                    "Cannot open export target: resolver in unexpected state " +
                        "(${e.message ?: "no detail"}).",
                )
            } catch (e: Exception) {
                return PdfExportResult.Failure(
                    "Cannot open export target: unexpected error (${e.javaClass.simpleName}).",
                )
            }
        if (out == null) {
            return PdfExportResult.Failure(
                "Cannot open export target: the provider returned no stream ($target).",
            )
        }
        // Phase 3: serialize to a buffer (exact byte count), then stream out.
        return try {
            out.use { stream ->
                val buffer = ByteArrayOutputStream(INITIAL_BUFFER_CHARS)
                document.writeTo(buffer)
                buffer.writeTo(stream)
                PdfExportResult.Success(plan.pages.size, buffer.size().toLong())
            }
        } catch (e: IOException) {
            PdfExportResult.Failure(
                "Writing PDF data to the target failed: storage may be full or the " +
                    "file was modified meanwhile.",
            )
        } catch (e: IllegalStateException) {
            PdfExportResult.Failure(
                "Writing PDF data failed: document or stream in unexpected state " +
                    "(${e.message ?: "no detail"}).",
            )
        } catch (e: Exception) {
            PdfExportResult.Failure(
                "Writing PDF data failed unexpectedly (${e.javaClass.simpleName}).",
            )
        }
    }

    private fun drawPages(
        document: PdfDocument,
        plan: PdfPlan,
        jobName: String,
    ) {
        val margin = PrintLayout.DEFAULT_MARGIN_POINTS.toFloat()
        val lineHeight = PrintLayout.DEFAULT_LINE_HEIGHT_POINTS
        val contentPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                textSize = PrintLayout.DEFAULT_TEXT_SIZE_POINTS
            }
        val numberPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                textSize = PrintLayout.DEFAULT_TEXT_SIZE_POINTS
                color = COLOR_LINE_NUMBER
            }
        val headerPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                textSize = HEADER_TEXT_SIZE
                color = COLOR_HEADER
            }
        val headerLabel = truncateJobName(jobName)
        val total = plan.pages.size
        // Line numbers are shown iff the planner emitted any number: a single
        // decision for the whole document keeps the text column aligned even
        // on pages that start inside a wrapped line (continuation-only page).
        val numbered = plan.pages.any { page -> page.lines.any { it.number != null } }
        val textX = if (numbered) NUMBERED_TEXT_X else margin
        plan.pages.forEachIndexed { pageIndex, page ->
            val pageInfo =
                PdfDocument.PageInfo
                    .Builder(
                        PrintLayout.PAGE_WIDTH_POINTS,
                        PrintLayout.PAGE_HEIGHT_POINTS,
                        pageIndex + 1,
                    ).create()
            val pdfPage = document.startPage(pageInfo)
            val canvas = pdfPage.canvas
            canvas.drawColor(Color.WHITE)
            val headerRight = String.format(Locale.US, "%1\$d / %2\$d", pageIndex + 1, total)
            canvas.drawText(headerLabel, margin, HEADER_BASELINE_Y, headerPaint)
            canvas.drawText(
                headerRight,
                PrintLayout.PAGE_WIDTH_POINTS - margin - headerPaint.measureText(headerRight),
                HEADER_BASELINE_Y,
                headerPaint,
            )
            var y = margin + lineHeight
            for (line in page.lines) {
                if (numbered && line.number != null) {
                    val label = String.format(Locale.US, "%1\$6d ", line.number)
                    val numberX = NUMBER_RIGHT_X - numberPaint.measureText(label)
                    canvas.drawText(label, numberX, y, numberPaint)
                }
                var x = textX
                for (segment in line.segments) {
                    if (segment.text.isEmpty()) continue
                    contentPaint.color = colorFor(segment.role)
                    canvas.drawText(segment.text, x, y, contentPaint)
                    x += contentPaint.measureText(segment.text)
                }
                y += lineHeight
            }
            document.finishPage(pdfPage)
        }
    }

    private fun colorFor(role: SyntaxTokenType?): Int =
        when (role) {
            SyntaxTokenType.KEYWORD -> COLOR_KEYWORD
            SyntaxTokenType.STRING -> COLOR_STRING
            SyntaxTokenType.COMMENT -> COLOR_COMMENT
            SyntaxTokenType.NUMBER -> COLOR_NUMBER
            null -> COLOR_PLAIN
        }

    private fun truncateJobName(jobName: String): String =
        if (jobName.length > MAX_JOB_NAME_CHARS) {
            jobName.take(MAX_JOB_NAME_CHARS) + ELLIPSIS
        } else {
            jobName
        }
}
