package com.secretarrow.rockedit.core

/**
 * Pure pagination helpers for printing plain text. Splitting is deterministic
 * so it is fully unit testable on the JVM; the renderer only paints the
 * resulting pages.
 */
object PrintLayout {

    /**
     * Splits [text] into pages of at most [linesPerPage] lines, hard-wrapping
     * longer lines at [charsPerLine] characters. Tabs are expanded to spaces.
     * Empty input yields a single empty page.
     */
    fun paginate(
        text: String,
        linesPerPage: Int,
        charsPerLine: Int
    ): List<List<String>> {
        require(linesPerPage > 0) { "linesPerPage must be positive" }
        require(charsPerLine > 0) { "charsPerLine must be positive" }
        val flat = ArrayList<String>()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
        val rawLines = normalized.split('\n')
        for (line in rawLines) {
            if (line.isEmpty()) {
                flat.add("")
                continue
            }
            var start = 0
            while (start < line.length) {
                val end = minOf(start + charsPerLine, line.length)
                flat.add(line.substring(start, end))
                start = end
            }
        }
        if (flat.isEmpty()) flat.add("")
        return flat.chunked(linesPerPage)
    }

    /** Page dimensions of A4 in PostScript points (1/72 inch). */
    const val PAGE_WIDTH_POINTS = 595
    const val PAGE_HEIGHT_POINTS = 842

    /** Default page geometry used by the print renderer. */
    const val DEFAULT_MARGIN_POINTS = 40
    const val DEFAULT_LINE_HEIGHT_POINTS = 15f
    const val DEFAULT_TEXT_SIZE_POINTS = 10f

    /** Comfortable monospace line width for the default A4 geometry. */
    const val DEFAULT_CHARS_PER_LINE = 88

    /** Lines per page derived from the geometry above. */
    const val DEFAULT_LINES_PER_PAGE = 47
}
