package com.secretarrow.rockedit.core

/** Machine-readable failure category of [ImageExportPlanner.plan]. */
enum class ImageExportErrorCode { EMPTY, TOO_LARGE, TOKEN_MISMATCH }

/**
 * Color role of one rendered slice. [LINE_NUMBER] and [BACKGROUND] never
 * appear in plan segments; they exist so the Android renderer can map every
 * role exhaustively (gutter digits and the canvas backdrop).
 */
enum class ColorRole {
    PLAIN,
    KEYWORD,
    STRING,
    COMMENT,
    NUMBER,
    LINE_NUMBER,
    TITLE,
    BACKGROUND,
    FOREGROUND,
}

/**
 * Theme-agnostic palette: ARGB colors as `Long` in `0xFFrrggbb` style, so the
 * pure planner never touches `android.graphics.Color`.
 *
 * @property background canvas fill.
 * @property foreground default text color (role [ColorRole.PLAIN] and
 *   [ColorRole.FOREGROUND]).
 * @property lineNumber gutter digit color.
 * The `keyword`/`string`/`comment`/`number` syntax colors are restrained and
 * print-like; `title` colors the optional title line.
 */
data class ImagePalette(
    val background: Long,
    val foreground: Long,
    val lineNumber: Long,
    val keyword: Long,
    val string: Long,
    val comment: Long,
    val number: Long,
    val title: Long,
)

/** The two shipped palettes, selected by [ImageExportOptions.darkTheme]. */
object ImagePalettes {
    /** Warm off-white paper look, dark slate text, restrained syntax colors. */
    val LIGHT: ImagePalette =
        ImagePalette(
            background = 0xFFFAF7F2L,
            foreground = 0xFF2E3440L,
            lineNumber = 0xFF9C948AL,
            keyword = 0xFF6A3AB2L,
            string = 0xFF2E7D32L,
            comment = 0xFF8A8A8AL,
            number = 0xFFB25000L,
            title = 0xFF6E655AL,
        )

    /** Dark slate-blue look (#1E1E2E style) with light text and pastel roles. */
    val DARK: ImagePalette =
        ImagePalette(
            background = 0xFF1E1E2EL,
            foreground = 0xFFE6E6F0L,
            lineNumber = 0xFF6C7086L,
            keyword = 0xFFCBA6F7L,
            string = 0xFFA6E3A1L,
            comment = 0xFF7F849CL,
            number = 0xFFFAB387L,
            title = 0xFFB8B8CCL,
        )
}

/**
 * Export knobs for the PNG image pipeline. The data-class defaults are always
 * valid, but [ImageExportPlanner.plan] still clamps every numeric field
 * (fail-safe against hand-constructed options).
 *
 * @property darkTheme pick [ImagePalettes.DARK] instead of [ImagePalettes.LIGHT].
 * @property lineNumbers print the 1-based source line number; DECISION v1:
 *   every wrapped continuation chunk repeats the SAME source number (simpler
 *   than gutter ranges, matches the editor's per-line gutter mental model).
 * @property wrapColumns columns per printed line before hard wrapping;
 *   clamped into [ImageExportPlanner.MIN_WRAP_COLUMNS]..
 *   [ImageExportPlanner.MAX_WRAP_COLUMNS] (clamp, not fail — documented).
 * @property maxLines hard cap on source lines; clamped to >= 1.
 * @property fontSizeSp text size; clamped to [ImageExportPlanner.MIN_FONT_SP]..
 *   [ImageExportPlanner.MAX_FONT_SP].
 * @property paddingSp outer margin; clamped to >= 0.
 * @property title rendered as a first TITLE line when non-blank; blank
 *   ([String.isNotBlank] `false`, e.g. `"  "`) suppresses the line entirely.
 */
data class ImageExportOptions(
    val darkTheme: Boolean = false,
    val lineNumbers: Boolean = true,
    val wrapColumns: Int = 80,
    val maxLines: Int = 5_000,
    val fontSizeSp: Int = 14,
    val paddingSp: Int = 16,
    val title: String = "",
)

/** One colored slice of a printed line; plain text uses [ColorRole.PLAIN]. */
data class ImageSegment(
    val text: String,
    val role: ColorRole,
)

/**
 * One printed line. [number] is the 1-based source line number repeated on
 * every wrapped chunk of that source line (v1 decision, see
 * [ImageExportOptions.lineNumbers]), `null` for the TITLE line and when
 * line numbers are disabled.
 */
data class ImageLine(
    val number: Int?,
    val segments: List<ImageSegment>,
)

/**
 * Fully resolved export plan.
 *
 * @property lines printed lines; the optional TITLE line (number `null`) is
 *   the first element when [ImageExportOptions.title] is non-blank.
 * @property palette palette selected by the `darkTheme` flag.
 * @property options the EFFECTIVE (clamped) options actually used to build
 *   this plan — the renderer must read sizes from here, not from the caller's
 *   original instance.
 * @property sourceLineCount raw `split('\n')` count of the preprocessed text
 *   (a trailing newline counts as one extra empty line, exactly like the PDF
 *   planner).
 */
data class ImagePlan(
    val lines: List<ImageLine>,
    val palette: ImagePalette,
    val options: ImageExportOptions,
    val sourceLineCount: Int,
)

/** Sealed result contract: callers cannot miss a branch. */
sealed class ImagePlanResult {
    data class Success(
        val plan: ImagePlan,
    ) : ImagePlanResult()

    data class Failure(
        val code: ImageExportErrorCode,
        val message: String,
        val actualLines: Int = -1,
    ) : ImagePlanResult()
}

/**
 * Deterministic, pure-JVM planner for the "export code as PNG image" feature
 * (v0.16.0). It turns preprocessed source text into wrapped, syntax-colored
 * [ImageLine]s plus a palette, and hands the result to the thin Android
 * renderer (`ui/ImageExporter`) which only paints what it is given.
 *
 * Pipeline contract (identical to the PDF planner): [plan] does NOT
 * preprocess. The caller must run [preprocess] -> `SyntaxTokenizer.tokenize`
 * -> [plan] and pass the same preprocessed text the tokens were computed
 * from. Token offsets are UTF-16 code unit indexes (one emoji counts as two
 * units); the tokenizer uses the same indexing, so offsets always agree.
 * Documented v1 assumption: display width is NOT measured — CJK wide
 * characters count as one column like every other code unit.
 *
 * Deliberate v1 duplication: the wrap/color/merge algorithm below is a
 * close copy of `PdfExportPlanner.appendLineChunks`, reimplemented here
 * instead of shared, so the two export modules stay independently
 * maintainable (the accepted cost is a small amount of duplicated logic).
 * Two intentional differences from the PDF planner: wrapped continuations
 * REPEAT the source line number (PDF emits `null`), and blank text fails
 * with [ImageExportErrorCode.EMPTY] instead of rendering an empty sheet.
 * Trailing-newline semantics were verified against `PdfExportPlanner` and
 * matched: Kotlin `split('\n')` keeps the trailing empty string, so `"a\n"`
 * yields two lines (`"a"` and `""`) and `sourceLineCount == 2`.
 *
 * plan() never throws for structurally valid inputs. There is deliberately
 * no `require()`: every numeric option is clamped before use (so e.g. a
 * negative maxLines after clamping is impossible), and token problems are
 * DATA errors surfaced as [ImageExportErrorCode.TOKEN_MISMATCH], not
 * programmer errors.
 *
 * Defensive rules (scenario -> handling -> covering test; every path
 * returns a specific result, there is no dead end):
 *
 * | Scenario                    | Handling                       | Test                         |
 * |-----------------------------|--------------------------------|------------------------------|
 * | Blank text                  | EMPTY failure; actualLines     | emptyTextFailsEmpty,         |
 * |                             | stays at the -1 sentinel.      | blankSpacesTextFailsEmpty    |
 * | `wrapColumns` < 20 or >200  | Clamped into range (fail-safe, | wrapColumnsBelowMin,         |
 * |                             | documented; never a failure).  | wrapColumnsAboveMax          |
 * | `fontSizeSp` < 8 or > 40    | Clamped into range; the plan   | fontSizeBelowMinClampsTo8,   |
 * |                             | echoes the effective values.   | fontSizeAboveMaxClampsTo40   |
 * | `paddingSp` < 0 or          | Clamped to 0 / 1 before use.   | negativePaddingClampsToZero, |
 * | `maxLines` < 1              |                                | zeroMaxLinesClampsToOne      |
 * | Token out of bounds         | TOKEN_MISMATCH with the token  | tokenStartNegative,          |
 * |                             | index and bounds; never an     | tokenEndBeyondLength,        |
 * |                             | IndexOutOfBoundsException.     | invertedRange, hugeEnd       |
 * | Source lines > clamped      | TOO_LARGE failure carrying the | overMaxLinesFailsTooLarge,   |
 * | `maxLines`                  | actual source line count.      |                              |
 * | Empty source line           | Grid row kept: a single empty  | emptyMiddleLineKeepsGridRow  |
 * |                             | PLAIN segment.                 |                              |
 * | Token crossing a wrap       | Clipped into each chunk; both  | tokenCrossingWrapKeepsRoles  |
 * | boundary                    | parts keep the token role.     |                              |
 * | Tokens overlap (patho-      | Earlier token wins; the        | overlappingTokensEarlierWins |
 * | logical callers)            | uncovered remainder keeps its  |                              |
 * |                             | own role; line stays covered.  |                              |
 * | Adjacent same-role slices   | Merged into one segment.       | adjacentSameRoleTokensMerge  |
 * | Line exactly `wrapColumns`  | One chunk; no phantom          | exactBoundaryWrapsWithout,   |
 * | long                        | continuation row appears.      |                              |
 * | `lineNumbers` = false       | All numbers null; invariants   | lineNumbersFalseNulls,       |
 * |                             | unchanged.                     |                              |
 * | Non-blank title             | First line is TITLE with       | nonBlankTitleBecomesFirst,   |
 * |                             | number `null`; a blank title   |                              |
 * |                             | suppresses the line entirely.  |                              |
 * | Trailing newline            | Matched to PdfExportPlanner:   | trailingNewlineMatchesPdf,   |
 * |                             | split keeps the trailing empty |                              |
 * |                             | string, so `"a\n"` is 2 lines. |                              |
 */
object ImageExportPlanner {
    /** Smallest allowed [ImageExportOptions.wrapColumns] (clamped, not rejected). */
    const val MIN_WRAP_COLUMNS = 20

    /** Largest allowed [ImageExportOptions.wrapColumns]. */
    const val MAX_WRAP_COLUMNS = 200

    /** Smallest allowed [ImageExportOptions.fontSizeSp]. */
    const val MIN_FONT_SP = 8

    /** Largest allowed [ImageExportOptions.fontSizeSp]. */
    const val MAX_FONT_SP = 40

    /**
     * Canonical normalization, byte-identical to [PdfExportPlanner.preprocess]:
     * `\r\n` and lone `\r` become `\n`, tabs become four spaces. Tokens MUST
     * be computed from the output of this function.
     */
    fun preprocess(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")

    /**
     * Plans the printed lines for [text] (already preprocessed) using
     * [tokens] computed from that same text. Deterministic: same inputs
     * always yield the same [ImagePlan]. Guarantees per line: the segment
     * list is non-empty, segments are ordered and non-overlapping, adjacent
     * same-role segments are merged, and their concatenation equals the
     * printed line text exactly (whitespace preserved).
     */
    fun plan(
        text: String,
        tokens: List<SyntaxToken>,
        options: ImageExportOptions,
    ): ImagePlanResult {
        // 1. Fail-safe clamp of every numeric knob (the data-class defaults
        // are always valid; hand-built options still cannot break the math).
        val effective =
            ImageExportOptions(
                darkTheme = options.darkTheme,
                lineNumbers = options.lineNumbers,
                wrapColumns = options.wrapColumns.coerceIn(MIN_WRAP_COLUMNS, MAX_WRAP_COLUMNS),
                maxLines = options.maxLines.coerceAtLeast(1),
                fontSizeSp = options.fontSizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP),
                paddingSp = options.paddingSp.coerceAtLeast(0),
                title = options.title,
            )
        // 2. Blank text: an image of nothing has no value; fail with EMPTY
        // (checked before token validation, so a blank text with stale tokens
        // still reports EMPTY — the order is part of the contract).
        if (text.isBlank()) {
            return ImagePlanResult.Failure(
                ImageExportErrorCode.EMPTY,
                "text is blank; there is nothing to render into an image",
            )
        }
        // 3. Token bounds — same check style as PdfExportPlanner.plan.
        for ((index, token) in tokens.withIndex()) {
            if (token.start < 0 || token.end > text.length || token.start > token.end) {
                return ImagePlanResult.Failure(
                    ImageExportErrorCode.TOKEN_MISMATCH,
                    "token[$index] range [${token.start}, ${token.end}) does not fit text " +
                        "length ${text.length}; tokens must come from tokenize(preprocess(text)) " +
                        "with the same text passed to plan()",
                )
            }
        }
        // 4. Source-line cap (uses the CLAMPED maxLines, so maxLines <= 0 can
        // never reach this comparison with a nonsensical bound).
        val rawLines = text.split('\n')
        if (rawLines.size > effective.maxLines) {
            return ImagePlanResult.Failure(
                ImageExportErrorCode.TOO_LARGE,
                "document has ${rawLines.size} source lines but image export is capped at " +
                    "${effective.maxLines}; split the file or raise the cap consciously",
                actualLines = rawLines.size,
            )
        }
        // 5. Build lines. Defensive: sort a copy so even out-of-order tokens
        // color deterministically (the real tokenizer emits document order).
        val ordered = tokens.sortedWith(compareBy({ it.start }, { it.end }))
        val lines = ArrayList<ImageLine>(rawLines.size + 1)
        if (options.title.isNotBlank()) {
            val titleSegments = listOf(ImageSegment(options.title, ColorRole.TITLE))
            lines.add(ImageLine(number = null, segments = titleSegments))
        }
        var cursor = 0
        var lineStart = 0
        for (index in rawLines.indices) {
            val lineEnd = lineStart + rawLines[index].length
            // v1 decision: every wrapped chunk repeats the same source number.
            val number = if (effective.lineNumbers) index + 1 else null
            cursor =
                appendLineChunks(
                    lines,
                    text,
                    lineStart,
                    lineEnd,
                    number,
                    ordered,
                    cursor,
                    effective.wrapColumns,
                )
            lineStart = lineEnd + 1
        }
        return ImagePlanResult.Success(
            ImagePlan(
                lines = lines,
                palette = if (effective.darkTheme) ImagePalettes.DARK else ImagePalettes.LIGHT,
                options = effective,
                sourceLineCount = rawLines.size,
            ),
        )
    }

    /**
     * Wraps one source line window `[lineStart, lineEnd)` into chunks of
     * [wrapColumns] UTF-16 units, colors each chunk from [tokens], merges
     * adjacent same-role slices, and appends the resulting [ImageLine]s to
     * [out]. Returns the advanced token cursor (tokens are consumed strictly
     * left to right, so a single monotonic cursor serves the whole document).
     */
    private fun appendLineChunks(
        out: MutableList<ImageLine>,
        text: String,
        lineStart: Int,
        lineEnd: Int,
        number: Int?,
        tokens: List<SyntaxToken>,
        tokenCursor: Int,
        wrapColumns: Int,
    ): Int {
        var cursor = tokenCursor
        var chunkStart = lineStart
        do {
            val chunkEnd =
                if (lineEnd - chunkStart <= wrapColumns) {
                    lineEnd
                } else {
                    chunkStart + wrapColumns
                }
            // Tokens fully behind this chunk can never intersect later chunks
            // (chunkStart is monotonic across the whole document).
            while (cursor < tokens.size && tokens[cursor].end <= chunkStart) cursor++
            val segments = ArrayList<ImageSegment>(4)
            var pen = chunkStart
            var i = cursor
            while (i < tokens.size && tokens[i].start < chunkEnd) {
                val token = tokens[i]
                i++
                val start = maxOf(token.start, chunkStart)
                val end = minOf(token.end, chunkEnd)
                if (end <= start) continue
                if (start > pen) {
                    segments.add(ImageSegment(text.substring(pen, start), ColorRole.PLAIN))
                    pen = start
                }
                // Defensive clip: an overlapping later token only colors the
                // remainder the earlier token did not cover.
                if (end > pen) {
                    segments.add(ImageSegment(text.substring(pen, end), roleFor(token.type)))
                    pen = end
                }
            }
            if (pen < chunkEnd) {
                segments.add(ImageSegment(text.substring(pen, chunkEnd), ColorRole.PLAIN))
            }
            if (segments.isEmpty()) segments.add(ImageSegment("", ColorRole.PLAIN))
            out.add(ImageLine(number, mergeAdjacent(segments)))
            chunkStart = chunkEnd
        } while (chunkStart < lineEnd)
        return cursor
    }

    /** Merges neighbor slices with the same role into one segment. */
    private fun mergeAdjacent(segments: List<ImageSegment>): List<ImageSegment> {
        val merged = ArrayList<ImageSegment>(segments.size)
        for (segment in segments) {
            val last = merged.lastOrNull()
            if (last != null && last.role == segment.role) {
                merged[merged.size - 1] = ImageSegment(last.text + segment.text, last.role)
            } else {
                merged.add(segment)
            }
        }
        return merged
    }

    /** Exhaustive token-role mapping (plain gaps never pass through here). */
    private fun roleFor(type: SyntaxTokenType): ColorRole =
        when (type) {
            SyntaxTokenType.KEYWORD -> ColorRole.KEYWORD
            SyntaxTokenType.STRING -> ColorRole.STRING
            SyntaxTokenType.COMMENT -> ColorRole.COMMENT
            SyntaxTokenType.NUMBER -> ColorRole.NUMBER
        }
}
