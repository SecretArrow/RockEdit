package com.secretarrow.rockedit.ui

import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.EditText
import androidx.core.content.ContextCompat
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.SyntaxLanguage
import com.secretarrow.rockedit.core.SyntaxTokenType
import com.secretarrow.rockedit.core.SyntaxTokenizer

/**
 * Applies lightweight syntax highlighting to the editor text.
 *
 * Re-highlight runs are debounced while typing and skipped for very large
 * documents. Colors come from the theme resources, so light, dark and the
 * AMOLED black theme all get readable palettes automatically.
 */
class SyntaxHighlighter(
    private val editor: EditText,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val applyRunnable = Runnable { applyNow() }
    private var language: SyntaxLanguage? = null
    private var enabled = true

    fun setLanguage(value: SyntaxLanguage?) {
        language = value
        if (enabled) rehighlightNow()
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) rehighlightNow() else clearSpans()
    }

    /** Schedules a debounced re-highlight; safe to call on every text change. */
    fun scheduleHighlight() {
        if (!enabled) return
        handler.removeCallbacks(applyRunnable)
        handler.postDelayed(applyRunnable, DEBOUNCE_MS)
    }

    /** Applies highlighting immediately (used after loads, undo/redo, toggles). */
    fun rehighlightNow() {
        handler.removeCallbacks(applyRunnable)
        applyNow()
    }

    private fun applyNow() {
        val lang = language ?: return
        if (!enabled) return
        val editable = editor.editableText ?: return
        val text = editable.toString()
        if (text.isEmpty() || text.length > MAX_HIGHLIGHT_CHARS) {
            clearSpans()
            return
        }
        for (span in editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)) {
            editable.removeSpan(span)
        }
        for (token in SyntaxTokenizer.tokenize(text, lang)) {
            val color = colorFor(token.type)
            editable.setSpan(
                ForegroundColorSpan(color),
                token.start,
                token.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun clearSpans() {
        val editable = editor.editableText ?: return
        for (span in editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)) {
            editable.removeSpan(span)
        }
    }

    private fun colorFor(type: SyntaxTokenType): Int =
        when (type) {
            SyntaxTokenType.KEYWORD -> ContextCompat.getColor(editor.context, R.color.syntax_keyword)
            SyntaxTokenType.STRING -> ContextCompat.getColor(editor.context, R.color.syntax_string)
            SyntaxTokenType.COMMENT -> ContextCompat.getColor(editor.context, R.color.syntax_comment)
            SyntaxTokenType.NUMBER -> ContextCompat.getColor(editor.context, R.color.syntax_number)
        }

    companion object {
        private const val DEBOUNCE_MS = 250L
        private const val MAX_HIGHLIGHT_CHARS = 150_000
    }
}
