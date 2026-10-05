package com.secretarrow.rockedit.core

/** Machine-readable failure category of [PdfExportPlanner.plan]. */
enum class PdfExportErrorCode { TOO_LARGE, TOKEN_MISMATCH }

/**
 * Export knobs for the colored PDF pipeline.
 *
 * @property lineNumbers print the 1-based source line number of every source
 *   line (wrapped continuations carry no number).
 * @property colored emit syntax-highlighted segments; `false` maps every
 *   segment to plain text (role `null`).
 * @property charsPerLine columns per printed line before hard wrapping;
 *   must be positive (enforced by [PdfExportPlanner.plan]).
 */
data class PdfExportOptions(
    val lineNumbers: Boolean = true,
    val colored: Boolean = true,
    val charsPerLine: Int = PrintLayout.DEFAULT_CHARS_PER_LINE,
)

/** One colored slice of a printed line; [role] `null` means plain text. */
data class PdfSegment(
    val text: String,
    val role: SyntaxTokenType?,
)

/**
 * One printed line. [number] is the 1-based source line number for the first
 * chunk of a source line, `null` for wrapped continuations and when
 * [PdfExportOptions.lineNumbers] is disabled.
 */
data class PdfLine(
    val number: Int?,
    val segments: List<PdfSegment>,
)

data class PdfPage(val lines: List<PdfLine>)

data class PdfPlan(
    val pages: List<PdfPage>,
    val sourceLineCount: Int,
)

/** Sealed result contract: callers cannot miss a branch. */
sealed class PdfPlanResult {
    data class Success(val plan: PdfPlan) : PdfPlanResult()

    data class Failure(
        val code: PdfExportErrorCode,
        val message: String,
        val actualLines: Int? = null,
    ) : PdfPlanResult()
}

/**
 * Deterministic, pure-JVM planner for the colored PDF export (backlog #12).
 * It splits preprocessed source text into wrapped, syntax-colored lines,
 * groups them into A4 pages, and hands the result to the thin Android
 * renderer (`ui/PdfExporter`) which only paints what it is given.
 *
 * Pipeline contract: [plan] does NOT preprocess. The caller must run
 * [preprocess] -> [SyntaxTokenizer.tokenize] -> [plan] and pass the same
 * preprocessed text the tokens were computed from. Token offsets are UTF-16
 * code unit indexes (Kotlin `String` indexes), so one astral-plane character
 * (emoji) counts as two units; the tokenizer uses the same indexing, therefore
 * offsets always agree. Documented v1 assumption: display width is NOT
 * measured — CJK wide characters count as one column like every other code
 * unit, so a `charsPerLine` cut may render slightly narrow/wide on paper.
 *
 * Defensive rules (event -> handling; there is no dead end, every path
 * returns a specific result or fails fast with a precise message):
 *
 * | # | Event                                   | Handling                                 |
 * |---|-----------------------------------------|------------------------------------------|
 * | 1 | `options.charsPerLine <= 0`             | `require` throws IllegalArgument         |
 * |   |                                         | Exception.                               |
 * | 2 | Token out of bounds (start < 0, end >   | TOKEN_MISMATCH failure with the token    |
 * |   | length, or start > end)                 | index and bounds; never an               |
 * |   |                                         | IndexOutOfBoundsException.               |
 * | 3 | More than MAX_EXPORT_LINES source lines | TOO_LARGE failure: actual count and      |
 * |   |                                         | limit in actualLines and the message.    |
 * | 4 | Empty text                              | One page with one empty line numbered 1. |
 * | 5 | Empty source line                       | Grid row kept: single empty plain        |
 * |   |                                         | segment.                                 |
 * | 6 | Token crossing a wrap boundary          | Clipped into each chunk; both parts keep |
 * |   |                                         | the token role.                          |
 * | 7 | Tokens overlap (pathological callers;   | Earlier token wins; only the uncovered   |
 * |   | the real tokenizer never does)          | remainder is colored; line stays covered.|
 * | 8 | `lineNumbers = false`                   | All numbers null; invariants unchanged.  |
 */
object PdfExportPlanner {
    /** Hard cap on source lines per export; keeps page count and memory bounded. */
    const val MAX_EXPORT_LINES = 100_000

    /**
     * Content lines per page (A4 with 40pt margins, 15pt line height, and
     * space reserved for the renderer's header at y=24pt — smaller than
     * [PrintLayout.DEFAULT_LINES_PER_PAGE] which prints without a header).
     */
    const val LINES_PER_PAGE = 44

    /**
     * Canonical normalization: `\r\n` and lone `\r` become `\n`, tabs become
     * four spaces. Tokens MUST be computed from the output of this function.
     */
    fun preprocess(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")

    /**
     * Plans pages for [text] (already preprocessed) using [tokens] computed
     * from that same text. Deterministic: same inputs always yield the same
     * [PdfPlan]. Guarantees per line: segments are ordered, non-overlapping,
     * and their concatenation equals the printed line text exactly.
     */
    fun plan(
        text: String,
        tokens: List<SyntaxToken>,
        options: PdfExportOptions,
    ): PdfPlanResult {
        require(options.charsPerLine > 0) {
            "charsPerLine must be positive, was: ${options.charsPerLine}"
        }
        for ((index, token) in tokens.withIndex()) {
            if (token.start < 0 || token.end > text.length || token.start > token.end) {
                return PdfPlanResult.Failure(
                    PdfExportErrorCode.TOKEN_MISMATCH,
                    "token[$index] range [${token.start}, ${token.end}) does not fit text " +
                        "length ${text.length}; tokens must come from tokenize(preprocess(text)) " +
                        "with the same text passed to plan()",
                )
            }
        }
        val rawLines = text.split('\n')
        if (rawLines.size > MAX_EXPORT_LINES) {
            return PdfPlanResult.Failure(
                PdfExportErrorCode.TOO_LARGE,
                "document has ${rawLines.size} source lines but export is capped at " +
                    "$MAX_EXPORT_LINES; split the file or raise the cap consciously",
                actualLines = rawLines.size,
            )
        }
        // Defensive: the real tokenizer emits document-ordered tokens; sorting
        // a copy keeps deterministic behavior for arbitrary callers too.
        val ordered = tokens.sortedWith(compareBy({ it.start }, { it.end }))
        val flat = ArrayList<PdfLine>(rawLines.size)
        var cursor = 0
        var lineStart = 0
        for (index in rawLines.indices) {
            val lineEnd = lineStart + rawLines[index].length
            val firstNumber = if (options.lineNumbers) index + 1 else null
            cursor =
                appendLineChunks(
                    flat, text, lineStart, lineEnd, firstNumber, ordered, cursor, options,
                )
            lineStart = lineEnd + 1
        }
        val pages = flat.chunked(LINES_PER_PAGE).map(::PdfPage)
        return PdfPlanResult.Success(PdfPlan(pages, rawLines.size))
    }

    /**
     * Wraps one source line window `[lineStart, lineEnd)` into chunks of
     * `options.charsPerLine` UTF-16 units, colors each chunk from [tokens],
     * and appends the resulting [PdfLine]s to [out]. Returns the advanced
     * token cursor (tokens are consumed strictly left to right, so a single
     * monotonic cursor serves the whole document).
     */
    private fun appendLineChunks(
        out: MutableList<PdfLine>,
        text: String,
        lineStart: Int,
        lineEnd: Int,
        firstNumber: Int?,
        tokens: List<SyntaxToken>,
        tokenCursor: Int,
        options: PdfExportOptions,
    ): Int {
        var cursor = tokenCursor
        var number = firstNumber
        var chunkStart = lineStart
        do {
            val chunkEnd =
                if (lineEnd - chunkStart <= options.charsPerLine) {
                    lineEnd
                } else {
                    chunkStart + options.charsPerLine
                }
            // Tokens fully behind this chunk can never intersect later chunks
            // (chunkStart is monotonic across the whole document).
            while (cursor < tokens.size && tokens[cursor].end <= chunkStart) cursor++
            val segments = ArrayList<PdfSegment>(4)
            if (options.colored) {
                var pen = chunkStart
                var i = cursor
                while (i < tokens.size && tokens[i].start < chunkEnd) {
                    val token = tokens[i]
                    i++
                    val start = maxOf(token.start, chunkStart)
                    val end = minOf(token.end, chunkEnd)
                    if (end <= start) continue
                    if (start > pen) {
                        segments.add(PdfSegment(text.substring(pen, start), null))
                        pen = start
                    }
                    // Defensive clip (rule 7): an overlapping later token only
                    // colors the remainder the earlier token did not cover.
                    if (end > pen) {
                        segments.add(PdfSegment(text.substring(pen, end), token.type))
                        pen = end
                    }
                }
                if (pen < chunkEnd) segments.add(PdfSegment(text.substring(pen, chunkEnd), null))
            } else {
                segments.add(PdfSegment(text.substring(chunkStart, chunkEnd), null))
            }
            if (segments.isEmpty()) segments.add(PdfSegment("", null))
            out.add(PdfLine(number, segments))
            number = null
            chunkStart = chunkEnd
        } while (chunkStart < lineEnd)
        return cursor
    }
}
