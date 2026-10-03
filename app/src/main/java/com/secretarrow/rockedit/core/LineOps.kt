package com.secretarrow.rockedit.core

/**
 * Pure line-editing transforms operating on (text, selection) pairs so they can
 * be unit tested on the JVM and reused by menus or gestures.
 *
 * Semantics (aligned with common desktop editors):
 *  - "Line" means the line containing the selection start.
 *  - When two lines swap, contents swap but terminator *slots* keep their
 *    position, so a final line without a newline stays valid and a CRLF file
 *    never degrades to LF.
 *  - Moving the first line up or the last line down is a no-op.
 *  - LF, CRLF and CR terminators are all handled.
 */
object LineOps {

    /** Result of a transform: the new text plus the new selection range. */
    data class Result(val text: String, val selStart: Int, val selEnd: Int)

    // ---------------------------------------------------------- duplicate line

    /** Duplicates the line under the caret right below it; caret lands on the copy. */
    fun duplicateLine(text: String, selStart: Int, selEnd: Int): Result {
        val safe = selStart.coerceIn(0, text.length)
        val ls = lineStart(text, safe)
        val le = lineEnd(text, safe)
        val leBr = lineEndIncludingBreak(text, safe)
        val content = text.substring(ls, le)
        val breakSeq = text.substring(le, leBr)
        val copy = content + (if (breakSeq.isEmpty()) "\n" else breakSeq)
        val newText = text.substring(0, leBr) + copy + text.substring(leBr)
        val caret = leBr + (safe - ls)
        return Result(newText, caret, caret + (selEnd - selStart))
    }

    // ------------------------------------------------------------- delete line

    /**
     * Deletes the whole line under the caret (content + terminator).
     * Deleting the final line also removes the preceding terminator so the
     * text does not end with a newline that was not there before.
     */
    fun deleteLine(text: String, selStart: Int, selEnd: Int): Result {
        val safe = selStart.coerceIn(0, text.length)
        val ls = lineStart(text, safe)
        val leBr = lineEndIncludingBreak(text, safe)
        val from: Int
        val to: Int
        if (leBr == text.length && ls > 0) {
            // Last line without terminator: eat the previous break too.
            from = breakStartBefore(text, ls)
            to = text.length
        } else {
            from = ls
            to = leBr
        }
        val newText = text.substring(0, from) + text.substring(to)
        val caret = from.coerceAtMost(newText.length)
        return Result(newText, caret, caret)
    }

    // -------------------------------------------------------------- move lines

    /** Moves the line under the caret one position up. No-op on the first line. */
    fun moveLineUp(text: String, selStart: Int, selEnd: Int): Result {
        val safe = selStart.coerceIn(0, text.length)
        val line = CursorNav.lineForOffset(text, safe)
        if (line <= 1) return Result(text, selStart, selEnd)
        val ls = lineStart(text, safe)
        val le = lineEnd(text, safe)
        val leBr = lineEndIncludingBreak(text, safe)
        val bs = breakStartBefore(text, ls) // start of the break between prev line and this one
        val prevStart = lineStart(text, bs)
        val myContent = text.substring(ls, le)
        val prevContent = text.substring(prevStart, bs)
        val term = text.substring(bs, ls)
        val newText = text.substring(0, prevStart) + myContent + term + prevContent +
            text.substring(leBr)
        val caret = clampToLine(newText, CursorNav.offsetForLine(newText, line - 1) + (safe - ls))
        return Result(newText, caret, caret + (selEnd - selStart))
    }

    /** Moves the line under the caret one position down. No-op on the last line. */
    fun moveLineDown(text: String, selStart: Int, selEnd: Int): Result {
        val safe = selStart.coerceIn(0, text.length)
        val line = CursorNav.lineForOffset(text, safe)
        val ls = lineStart(text, safe)
        val le = lineEnd(text, safe)
        val leBr = lineEndIncludingBreak(text, safe)
        if (leBr >= text.length) return Result(text, selStart, selEnd)
        val nextLe = lineEnd(text, leBr)
        val nextLeBr = lineEndIncludingBreak(text, leBr)
        val myContent = text.substring(ls, le)
        val myTerm = text.substring(le, leBr)
        val nextContent = text.substring(leBr, nextLe)
        val nextTerm = if (nextLeBr > nextLe) text.substring(nextLe, nextLeBr) else ""
        val newText = text.substring(0, ls) + nextContent + myTerm + myContent + nextTerm +
            text.substring(nextLeBr)
        val caret = clampToLine(newText, CursorNav.offsetForLine(newText, line + 1) + (safe - ls))
        return Result(newText, caret, caret + (selEnd - selStart))
    }

    // ----------------------------------------------------------------- helpers

    /** Index of the first character of the line containing [offset]. */
    fun lineStart(text: String, offset: Int): Int {
        var i = offset - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '\n' || c == '\r') return i + 1
            i--
        }
        return 0
    }

    /** Index just past the last content character of the line (excludes the break). */
    fun lineEnd(text: String, offset: Int): Int {
        var i = offset
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\n' || c == '\r') return i
            i++
        }
        return n
    }

    /** Index just past the line terminator of the line containing [offset]. */
    fun lineEndIncludingBreak(text: String, offset: Int): Int {
        val le = lineEnd(text, offset)
        if (le >= text.length) return le
        val c = text[le]
        return if (c == '\r' && le + 1 < text.length && text[le + 1] == '\n') le + 2 else le + 1
    }

    /**
     * Start index of the terminator that sits immediately before a line whose
     * first character is at [lineStart]. Handles LF, CRLF and CR.
     */
    private fun breakStartBefore(text: String, lineStart: Int): Int {
        var s = lineStart - 1
        if (s >= 0 && text[s] == '\n' && s - 1 >= 0 && text[s - 1] == '\r') s--
        return s.coerceAtLeast(0)
    }

    /** Keeps [offset] from crossing past the end of the line it starts in. */
    private fun clampToLine(text: String, offset: Int): Int =
        offset.coerceAtMost(lineEnd(text, offset))
}
