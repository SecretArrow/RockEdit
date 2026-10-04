package com.secretarrow.rockedit.core

/** Highlightable token categories produced by [SyntaxTokenizer]. */
enum class SyntaxTokenType { KEYWORD, STRING, COMMENT, NUMBER }

/** A colored range in the source text (end exclusive). */
data class SyntaxToken(
    val type: SyntaxTokenType,
    val start: Int,
    val end: Int,
)

/**
 * Small, dependency-free single-pass lexer. It scans the whole document and
 * returns non-plain token ranges (keywords, strings, comments, numbers).
 * Deterministic and side-effect free, so it is fully unit testable on the JVM.
 */
object SyntaxTokenizer {
    /**
     * Tokenizes [text] according to [language]. Adjacent plain text is not
     * emitted: the caller only needs the colored ranges.
     */
    fun tokenize(
        text: String,
        language: SyntaxLanguage,
    ): List<SyntaxToken> {
        if (text.isEmpty()) return emptyList()
        val tokens = ArrayList<SyntaxToken>()
        val len = text.length
        val lineComments = language.lineComments.filter { it.isNotEmpty() }
        val blocks =
            language.blockComments
                .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        val delims = language.stringDelims
        val keywords =
            if (language.caseInsensitive) {
                language.keywords.mapTo(HashSet()) { it.lowercase() }
            } else {
                language.keywords
            }

        var i = 0
        var blockEnd: String? = null
        var blockStart = 0
        while (i < len) {
            val currentEnd = blockEnd
            if (currentEnd != null) {
                val close = text.indexOf(currentEnd, i)
                if (close < 0) {
                    tokens.add(SyntaxToken(SyntaxTokenType.COMMENT, blockStart, len))
                    i = len
                } else {
                    tokens.add(SyntaxToken(SyntaxTokenType.COMMENT, blockStart, close + currentEnd.length))
                    i = close + currentEnd.length
                    blockEnd = null
                }
                continue
            }

            val lineMarker = longestMatchAt(text, i, lineComments)
            val blockMarker = longestBlockMatchAt(text, i, blocks)
            // A longer block start at the same position wins over the line
            // marker: Lua "--[[" over "--", Julia "#=" over "#", etc.
            if (blockMarker != null && (lineMarker == null || blockMarker.first.length > lineMarker.length)) {
                blockStart = i
                blockEnd = blockMarker.second
                i += blockMarker.first.length
                continue
            }
            if (lineMarker != null) {
                val nl = text.indexOf('\n', i)
                val end = if (nl < 0) len else nl
                tokens.add(SyntaxToken(SyntaxTokenType.COMMENT, i, end))
                i = end
                continue
            }

            val c = text[i]
            if (c in delims) {
                var j = i + 1
                while (j < len) {
                    val ch = text[j]
                    if (ch == '\\' && j + 1 < len) {
                        j += 2
                        continue
                    }
                    if (ch == c) {
                        j++
                        break
                    }
                    if (ch == '\n') break
                    j++
                }
                tokens.add(SyntaxToken(SyntaxTokenType.STRING, i, j))
                i = j
                continue
            }

            if (c.isDigit()) {
                var j = i
                while (j < len && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                tokens.add(SyntaxToken(SyntaxTokenType.NUMBER, i, j))
                i = j
                continue
            }

            if (c.isLetter() || c == '_' || c == '$') {
                var j = i
                while (j < len && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) j++
                val word = text.substring(i, j)
                val hit = if (language.caseInsensitive) word.lowercase() in keywords else word in keywords
                if (hit) tokens.add(SyntaxToken(SyntaxTokenType.KEYWORD, i, j))
                i = j
                continue
            }

            i++
        }
        return tokens
    }

    private fun longestMatchAt(
        text: String,
        pos: Int,
        markers: List<String>,
    ): String? {
        var found: String? = null
        for (marker in markers) {
            if (text.startsWith(marker, pos) && (found == null || marker.length > found.length)) {
                found = marker
            }
        }
        return found
    }

    private fun longestBlockMatchAt(
        text: String,
        pos: Int,
        blocks: List<Pair<String, String>>,
    ): Pair<String, String>? {
        var found: Pair<String, String>? = null
        for (block in blocks) {
            val start = block.first
            if (text.startsWith(start, pos) && (found == null || start.length > found.first.length)) {
                found = block
            }
        }
        return found
    }
}
