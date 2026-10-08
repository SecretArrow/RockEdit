package com.secretarrow.rockedit.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * v0.24.0: EditText subclass exposing caret movement to the activity.
 *
 * Relative/hybrid gutter numbering must follow the caret even when the text
 * does not change (arrow keys, taps, selection drags) — the only reliable
 * hook for that is onSelectionChanged, which requires a subclass. The
 * callback stays nullable and unused behavior is byte-identical to a plain
 * AppCompatEditText: nothing is stored, nothing is filtered, and every
 * override first delegates to super so platform selection handling
 * (handles, action mode) is never disturbed.
 */
class EditorView : AppCompatEditText {
    /** Fired after every caret/selection change (selection start, end). */
    var onSelectionMoved: ((start: Int, end: Int) -> Unit)? = null

    constructor(context: Context) : super(context)

    constructor(
        context: Context,
        attrs: AttributeSet?,
    ) : super(context, attrs)

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
    ) : super(context, attrs, defStyleAttr)

    override fun onSelectionChanged(
        selStart: Int,
        selEnd: Int,
    ) {
        super.onSelectionChanged(selStart, selEnd)
        // Defensive: a listener throwing here would crash every caret move;
        // the gutter refresh is an optimization, never a correctness path
        // for the editor itself.
        try {
            onSelectionMoved?.invoke(selStart, selEnd)
        } catch (e: Exception) {
            // Documented best-effort ignore: see KDoc above.
        }
    }
}
