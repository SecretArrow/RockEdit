package com.secretarrow.rockedit.core

/** Pure text statistics used by the Statistics dialog. */
object TextStats {

    /** Number of characters (code units, same as String.length). */
    fun charCount(text: String): Int = text.length

    /**
     * Number of words: maximal runs of non-whitespace characters.
     * Empty or whitespace-only text has 0 words.
     */
    fun wordCount(text: String): Int {
        if (text.isBlank()) return 0
        var count = 0
        var inWord = false
        var i = 0
        val n = text.length
        while (i < n) {
            val isWs = Character.isWhitespace(text[i])
            if (!isWs && !inWord) count++
            inWord = !isWs
            i++
        }
        return count
    }

    /**
     * Number of lines: an empty text has 0 lines; otherwise the count of
     * line terminators plus 1 when the text does not end with a terminator.
     * "a\n" -> 1, "a\nb" -> 2, "a\n\n" -> 2, "" -> 0.
     */
    fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        var lines = 1
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                if (i + 1 < n) lines++
            }
            i++
        }
        return lines
    }
}
