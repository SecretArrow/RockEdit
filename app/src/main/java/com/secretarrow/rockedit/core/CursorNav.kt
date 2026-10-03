package com.secretarrow.rockedit.core

/**
 * Pure cursor navigation helpers for the editor.
 */
object CursorNav {

    /**
     * Returns the character offset that starts [line] (1-based).
     * When [line] is beyond the last line, returns the end of text.
     * Returns 0 for line <= 1.
     */
    fun offsetForLine(text: String, line: Int): Int {
        if (line <= 1) return 0
        var current = 1
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                current++
                if (current == line) return i + 1
            }
            i++
        }
        return n
    }

    /**
     * Returns the 1-based line number that contains [offset].
     */
    fun lineForOffset(text: String, offset: Int): Int {
        val safe = offset.coerceIn(0, text.length)
        var line = 1
        for (i in 0 until safe) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < safe && text[i + 1] == '\n') continue
                line++
            }
        }
        return line
    }
}
