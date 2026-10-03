package com.secretarrow.rockedit.core

/** Character set of a text file: how a new line is encoded on disk. */
enum class LineBreak(val value: String) {
    LF("\n"),
    CR("\r"),
    CRLF("\r\n");

    companion object {
        /**
         * Detects the line break style from raw text content.
         * The first line break wins (this is how most editors behave).
         * Returns [fallback] when the text contains no line break at all.
         */
        fun detect(text: String, fallback: LineBreak = CRLF): LineBreak {
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                if (c == '\r') {
                    return if (i + 1 < n && text[i + 1] == '\n') CRLF else CR
                }
                if (c == '\n') return LF
                i++
            }
            return fallback
        }

        /** Converts every line break in [text] to [target]. */
        fun normalize(text: String, target: LineBreak): String {
            if (text.isEmpty()) return text
            // Normalize everything to \n first, then expand to the target style.
            val unix = text.replace("\r\n", "\n").replace("\r", "\n")
            return when (target) {
                LF -> unix
                CR -> unix.replace("\n", "\r")
                CRLF -> unix.replace("\n", "\r\n")
            }
        }
    }
}
