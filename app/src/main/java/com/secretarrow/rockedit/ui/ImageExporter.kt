package com.secretarrow.rockedit.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.secretarrow.rockedit.core.ColorRole
import com.secretarrow.rockedit.core.ImagePalette
import com.secretarrow.rockedit.core.ImagePlan
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Thin Android renderer for the "export code as PNG image" feature (v0.16.0).
 * It paints an [ImagePlan] produced by `core/ImageExportPlanner` into a
 * monospace [Bitmap] and encodes it to PNG bytes. All wrapping, coloring,
 * numbering, and palette decisions were already made by the planner; this
 * object only measures, draws, and compresses, so the risky logic stays
 * unit-testable on the JVM.
 *
 * Geometry: sp->px is a plain multiply by [render]'s `density` (the caller
 * passes `resources.displayMetrics.density`). The 1.35f line-height factor
 * already contains the line gap (~35% leading), so no extra gap constant is
 * added. Width = `padding*2 + gutter (digits + one cell gap, when numbered) +
 * longest measured segment row`. The title line uses the same line height as
 * code lines (v1 keeps the grid uniform).
 *
 * Threading: [render] and [toPngBytes] block; callers must invoke them off
 * the main thread (e.g. `Dispatchers.IO`).
 *
 * Defensive rules (event -> handling; no path throws, every failure has a
 * reason the caller can surface):
 *
 * | # | Phase  | Event                                    | Handling                          |
 * |---|--------|------------------------------------------|-----------------------------------|
 * | 1 | Measure| `density <= 0f` (or NaN)                 | 1f is used, fail-safe.            |
 * | 2 | Measure| `plan.lines` empty                       | null: degenerate plan (the        |
 * |   |        |                                          | planner never emits one).         |
 * | 3 | Measure| Computed width/height above              | null: caller shows an informative |
 * |   |        | [MAX_BITMAP_WIDTH_PX]/[MAX_BITMAP_HEIGHT_PX] | "image too large" error.      |
 * | 4 | Measure| Computed width/height below 1 px         | null: nothing to draw.            |
 * | 5 | Alloc  | OutOfMemoryError from createBitmap       | null: image too large for heap.   |
 * | 6 | Draw   | Any unexpected Exception                 | null; canvas discarded, never     |
 * |   |        |                                          | rethrown.                         |
 * | 7 | Encode | compress false / throws / recycled input | empty array (see [toPngBytes]).   |
 */
object ImageExporter {
    /** Hard bitmap caps; a plan exceeding either is rejected before allocation. */
    const val MAX_BITMAP_WIDTH_PX = 8192
    const val MAX_BITMAP_HEIGHT_PX = 8192

    /** Line height multiplier, line gap included (~35% leading). */
    private const val LINE_HEIGHT_FACTOR = 1.35f

    /** PNG ignores quality; kept explicit for readability. */
    private const val PNG_QUALITY = 100

    /**
     * Renders [plan] to a Bitmap, or returns null with a documented reason
     * (rows 2-6 of the table above) when the geometry exceeds the caps or
     * allocation/drawing fails. Never throws.
     */
    fun render(
        plan: ImagePlan,
        density: Float,
    ): Bitmap? {
        if (plan.lines.isEmpty()) return null
        val safeDensity = if (density > 0f) density else 1f
        val fontPx = plan.options.fontSizeSp * safeDensity
        val paddingPx = plan.options.paddingSp * safeDensity
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                textSize = fontPx
            }
        val charW = paint.measureText("M")
        val lineHeight = fontPx * LINE_HEIGHT_FACTOR
        val numbered = plan.options.lineNumbers && plan.lines.any { it.number != null }
        val maxNumber = plan.lines.maxOfOrNull { it.number ?: 0 } ?: 0
        // One extra monospace cell separates gutter digits from the code.
        val gutterPx = if (numbered) (maxNumber.toString().length + 1) * charW else 0f
        var longest = 0f
        for (line in plan.lines) {
            var rowWidth = 0f
            for (segment in line.segments) rowWidth += paint.measureText(segment.text)
            if (rowWidth > longest) longest = minOf(rowWidth, MAX_BITMAP_WIDTH_PX.toFloat())
        }
        val widthPx = (paddingPx * 2 + gutterPx + longest).roundToInt()
        val heightPx = (paddingPx * 2 + lineHeight * plan.lines.size).roundToInt()
        if (widthPx < 1 || heightPx < 1) return null
        if (widthPx > MAX_BITMAP_WIDTH_PX || heightPx > MAX_BITMAP_HEIGHT_PX) return null
        return try {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            drawPlan(Canvas(bitmap), plan, paint, fontPx, lineHeight, paddingPx, gutterPx, numbered)
            bitmap
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Encodes [bitmap] to PNG bytes. Returns an empty array on any failure:
     * compression failures are OOM/IO-level, the ByteArray API has no message
     * channel, and an empty array is the documented failure sentinel the
     * caller must treat as "encoding failed" (never as a valid empty file —
     * PNG bytes are always non-empty on success).
     */
    fun toPngBytes(bitmap: Bitmap): ByteArray {
        if (bitmap.isRecycled) return ByteArray(0)
        val buffer = ByteArrayOutputStream()
        return try {
            val ok = bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, buffer)
            if (ok) buffer.toByteArray() else ByteArray(0)
        } catch (e: OutOfMemoryError) {
            ByteArray(0)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private fun drawPlan(
        canvas: Canvas,
        plan: ImagePlan,
        paint: Paint,
        fontPx: Float,
        lineHeight: Float,
        paddingPx: Float,
        gutterPx: Float,
        numbered: Boolean,
    ) {
        canvas.drawColor(plan.palette.background.toInt())
        val textX = paddingPx + gutterPx
        var baseline = paddingPx + fontPx
        for (line in plan.lines) {
            if (numbered && line.number != null) {
                val label = line.number.toString()
                paint.color = colorFor(ColorRole.LINE_NUMBER, plan.palette)
                canvas.drawText(label, paddingPx, baseline, paint)
            }
            var x = textX
            for (segment in line.segments) {
                if (segment.text.isEmpty()) continue
                paint.color = colorFor(segment.role, plan.palette)
                canvas.drawText(segment.text, x, baseline, paint)
                x += paint.measureText(segment.text)
            }
            baseline += lineHeight
        }
    }

    /** Exhaustive role -> palette mapping (PLAIN and FOREGROUND share a color). */
    private fun colorFor(
        role: ColorRole,
        palette: ImagePalette,
    ): Int =
        when (role) {
            ColorRole.PLAIN, ColorRole.FOREGROUND -> palette.foreground.toInt()
            ColorRole.KEYWORD -> palette.keyword.toInt()
            ColorRole.STRING -> palette.string.toInt()
            ColorRole.COMMENT -> palette.comment.toInt()
            ColorRole.NUMBER -> palette.number.toInt()
            ColorRole.LINE_NUMBER -> palette.lineNumber.toInt()
            ColorRole.TITLE -> palette.title.toInt()
            ColorRole.BACKGROUND -> palette.background.toInt()
        }
}
