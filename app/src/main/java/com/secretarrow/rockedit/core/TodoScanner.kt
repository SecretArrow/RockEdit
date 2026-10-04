package com.secretarrow.rockedit.core

/**
 * TODO/FIXME-style marker scanner (v0.13.0): finds annotated lines in a text
 * and reports line, column, and character-offset positions plus the trailing
 * message, so the editor can list "task" hits like a grep. Pure JVM so it is
 * fully unit-testable.
 *
 * Case map (defensive rule 1):
 * - Input above [MAX_INPUT_CHARS] -> [ScanResult.Failure] INPUT_TOO_LARGE
 *   with the actual size and the limit in the message.
 * - An empty marker set is impossible via [TodoOptions] (its constructor
 *   throws), yet [scan] still guards with a NO_MARKERS failure for defensive
 *   completeness.
 * - Word-boundary rule: the character before and after a marker must not be
 *   a letter, digit, or underscore. "TODOS" and "xTODO" never match, "TODO:"
 *   does, and a marker can never span a line break.
 * - Optional tag `MARKER(tag)`: tag characters are letters/digits/-/_ with a
 *   length of at most [MAX_TAG_CHARS]. Malformed tags (unclosed paren, empty
 *   content, too long, invalid characters) are NOT errors: the parenthesized
 *   text stays part of the message and no tag is reported.
 * - Messages longer than [MAX_MESSAGE_CHARS] are cut at the limit with an
 *   ellipsis appended; an empty remainder stays "".
 * - Reaching [TodoOptions.maxItems] stops the scan early and reports
 *   `truncated = true` (documented perf decision: never scan a 1M-character
 *   input just to keep counting; deterministic document order is kept).
 *
 * Documented assumptions (v1):
 * - Whole-text scan with no comment/string awareness, exactly like grep-like
 *   tools: markers inside URLs WILL match when surrounded by non-word
 *   characters. Predictability beats cleverness here.
 * - Columns count characters from the line start (1-based, tabs count as one
 *   character); they are character indexes, not display cells.
 * - `\r\n` and `\n` separate lines; a lone `\r` also separates (fail-safe,
 *   mirrors DiffEngine); a trailing newline produces no extra line.
 */
object TodoScanner {

    /** Hard input cap for the public [scan] entry point. */
    const val MAX_INPUT_CHARS = 1_000_000

    /** Messages above this length are cut with [ELLIPSIS] appended. */
    const val MAX_MESSAGE_CHARS = 200

    /** `MARKER(tag)` payloads above this length are treated as malformed. */
    const val MAX_TAG_CHARS = 20

    /** Appended to messages cut at [MAX_MESSAGE_CHARS]. */
    const val ELLIPSIS = "…"

    enum class ErrorCode { INPUT_TOO_LARGE, NO_MARKERS }

    /**
     * One found marker occurrence.
     *
     * @property lineNumber 1-based line number.
     * @property column 1-based character column within the line (tabs count
     *   as one character; this is an index, not a display cell).
     * @property marker the matched marker, normalized uppercase.
     * @property tag the captured `MARKER(tag)` payload, or null when absent
     *   or malformed.
     * @property message remainder of the line after the marker (and tag),
     *   trimmed; at most [MAX_MESSAGE_CHARS] characters plus [ELLIPSIS].
     * @property offset 0-based character offset of the marker start within
     *   the full scanned text.
     */
    data class TodoItem(
        val lineNumber: Int,
        val column: Int,
        val marker: String,
        val tag: String?,
        val message: String,
        val offset: Int
    )

    /**
     * Scanner configuration. Validation runs in `init` and THROWS
     * [IllegalArgumentException]: every marker must be non-blank after
     * trimming (markers are stored normalized uppercase in
     * [normalizedMarkers]), and [maxItems] must be in 1..[MAX_ITEMS_LIMIT].
     * With [caseSensitive] enabled the uppercase [normalizedMarkers] are
     * matched literally, so lowercase input text will not match.
     */
    data class TodoOptions(
        val markers: Set<String> = DEFAULT_MARKERS,
        val caseSensitive: Boolean = false,
        val maxItems: Int = DEFAULT_MAX_ITEMS
    ) {
        /** Trimmed, uppercase marker set actually used for matching. */
        val normalizedMarkers: Set<String>

        init {
            val trimmed = markers.map { it.trim() }
            if (trimmed.isEmpty() || trimmed.any { it.isEmpty() }) {
                throw IllegalArgumentException(
                    "every marker must be non-blank after trimming, got $markers"
                )
            }
            if (maxItems < 1 || maxItems > MAX_ITEMS_LIMIT) {
                throw IllegalArgumentException(
                    "maxItems must be in 1..$MAX_ITEMS_LIMIT, got $maxItems"
                )
            }
            normalizedMarkers = trimmed.map { it.uppercase() }.toSet()
        }

        companion object {
            val DEFAULT_MARKERS = setOf("TODO", "FIXME", "HACK", "XXX", "BUG", "NOTE")
            const val DEFAULT_MAX_ITEMS = 1000
            const val MAX_ITEMS_LIMIT = 100_000
        }
    }

    sealed interface ScanResult {
        data class Success(val items: List<TodoItem>, val truncated: Boolean) : ScanResult
        data class Failure(val code: ErrorCode, val message: String) : ScanResult
    }

    /**
     * Scans [text] for marker hits (see the class KDoc for the full case
     * map). Runs in a single pass without regex line splitting so offsets
     * stay exact; stops as soon as [TodoOptions.maxItems] hits are found.
     */
    fun scan(text: String, options: TodoOptions = TodoOptions()): ScanResult =
        scanWithCap(text, options, MAX_INPUT_CHARS)

    /**
     * Same algorithm with a caller-provided input cap; internal so tests can
     * exercise the INPUT_TOO_LARGE branch without building 1M characters.
     * The public [scan] delegates with [MAX_INPUT_CHARS].
     */
    internal fun scanWithCap(text: String, options: TodoOptions, cap: Int): ScanResult {
        if (text.length > cap) {
            return ScanResult.Failure(
                ErrorCode.INPUT_TOO_LARGE,
                "input has ${text.length} characters, limit is $cap"
            )
        }
        if (options.normalizedMarkers.isEmpty()) {
            // Defensive completeness: TodoOptions already rejects empty sets.
            return ScanResult.Failure(ErrorCode.NO_MARKERS, "no markers configured")
        }
        // Longest marker first so overlapping definitions resolve the same
        // way at every position; lexicographic order keeps ties deterministic.
        val ordered = options.normalizedMarkers
            .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
        val items = ArrayList<TodoItem>()
        val caseSensitive = options.caseSensitive
        val maxItems = options.maxItems
        val n = text.length
        var lineStart = 0
        var lineNumber = 1
        var truncated = false
        while (lineStart < n) {
            var j = lineStart
            while (j < n) {
                val c = text[j]
                if (c == '\n' || c == '\r') break
                j++
            }
            val lineEnd = j
            var next = j
            if (next < n) {
                next += if (text[next] == '\r' && next + 1 < n && text[next + 1] == '\n') 2 else 1
            }
            val capped = scanLine(
                text, lineStart, lineEnd, lineNumber, ordered, caseSensitive, maxItems, items
            )
            if (capped) {
                truncated = true
                break
            }
            lineNumber++
            lineStart = next
        }
        return ScanResult.Success(items, truncated)
    }

    // ------------------------------------------------------------ internals

    /**
     * Scans one line for markers, appending hits to [items]. Returns true
     * when [maxItems] hits were reached, so the caller stops the whole scan
     * (early-stop perf decision) and reports truncation.
     */
    private fun scanLine(
        text: String,
        lineStart: Int,
        lineEnd: Int,
        lineNumber: Int,
        orderedMarkers: List<String>,
        caseSensitive: Boolean,
        maxItems: Int,
        items: MutableList<TodoItem>
    ): Boolean {
        var pos = lineStart
        while (pos < lineEnd) {
            var advance = 1
            for (marker in orderedMarkers) {
                val markerLength = marker.length
                if (pos + markerLength > lineEnd) continue
                if (!charsEqual(text[pos], marker[0], caseSensitive)) continue
                val matches = text.regionMatches(
                    pos, marker, 0, markerLength, ignoreCase = !caseSensitive
                )
                if (!matches) continue
                // Word boundary: no letter/digit/underscore directly before
                // or after. Line edges count as boundaries (terminator/EOF).
                if (pos > lineStart && isWordChar(text[pos - 1])) continue
                val after = pos + markerLength
                if (after < lineEnd && isWordChar(text[after])) continue
                var tag: String? = null
                var messageStart = after
                if (after < lineEnd && text[after] == '(') {
                    val close = findTagClose(text, after + 1, lineEnd)
                    if (close > after + 1) {
                        val candidate = text.substring(after + 1, close)
                        if (candidate.length <= MAX_TAG_CHARS && candidate.all { isTagChar(it) }) {
                            tag = candidate
                            messageStart = close + 1
                        }
                    }
                }
                val raw = text.substring(messageStart, lineEnd).trim()
                val message =
                    if (raw.length > MAX_MESSAGE_CHARS) {
                        raw.substring(0, MAX_MESSAGE_CHARS) + ELLIPSIS
                    } else {
                        raw
                    }
                items.add(
                    TodoItem(
                        lineNumber = lineNumber,
                        column = pos - lineStart + 1,
                        marker = marker,
                        tag = tag,
                        message = message,
                        offset = pos
                    )
                )
                advance = markerLength
                if (items.size >= maxItems) return true
                break
            }
            pos += advance
        }
        return false
    }

    /** Index of the first ')' in [from, end), or -1 when unclosed. */
    private fun findTagClose(text: String, from: Int, end: Int): Int {
        for (i in from until end) {
            if (text[i] == ')') return i
        }
        return -1
    }

    private fun charsEqual(a: Char, b: Char, caseSensitive: Boolean): Boolean =
        if (caseSensitive) a == b else a.equals(b, ignoreCase = true)

    /** Word characters terminate markers: letters, digits, underscore. */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** Tag payload characters: letters, digits, dash, underscore. */
    private fun isTagChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-' || c == '_'
}
