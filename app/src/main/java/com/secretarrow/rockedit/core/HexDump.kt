package com.secretarrow.rockedit.core

/**
 * Hex dump engine (v0.13.0): turns raw bytes into fixed-width hex dump lines
 * and parses dumps back into bytes. Pure JVM (kotlin stdlib only) so it is
 * fully unit-testable; the hex viewer UI only renders what this produces.
 *
 * Case map (defensive rule 1):
 * - Empty byte array -> `Success(emptyList())`; the viewer renders a dedicated
 *   "0 byte" state. Documented asymmetry: dumping nothing is legitimate (an
 *   empty file exists), so [toDumpLines] succeeds and [toDumpText] returns "",
 *   while [parse] of an empty/blank/data-less dump is `Failure(EMPTY)` because
 *   parsing "nothing" is almost always an upstream mistake.
 * - Input above [MAX_BYTES] (1 MiB) -> `Failure(TOO_LARGE)` from [toDumpLines]
 *   and `IllegalArgumentException` from [toDumpText]; both messages state the
 *   actual size and the cap. The cap keeps the line list bounded (1 MiB is
 *   already up to 65,536 dump lines).
 * - Invalid [DumpOptions] -> `Failure` with the exact violated constraint and
 *   its allowed range (bytesPerLine 4..64, groupSize 1..bytesPerLine,
 *   offsetBase >= 0). Nothing is ever formatted with degraded parameters.
 * - Formatting: every line of one dump carries identical column widths. The
 *   hex column is padded with spaces to the width implied by
 *   bytesPerLine/groupSize, the ASCII column to bytesPerLine, so monospace
 *   columns align even on the padded last line.
 * - Offset rendering: zero-padded to at least 8 hex digits (classic hexdump
 *   style); the width grows only when the highest offset requires more digits.
 * - [parse] is deliberately tolerant: `\r` is stripped, blank lines and lines
 *   that do not start with a 1-16 hex-digit offset followed by whitespace are
 *   skipped as junk. Inside a data line the hex column ends at two or more
 *   consecutive spaces (the ASCII separator) or end of line; tabs inside the
 *   hex column are errors, not separators. Errors are positional (1-based
 *   line, 0-based column): ODD_HEX reports the unpaired digit, INVALID_HEX the
 *   offending character, TOO_LARGE the line where the cap was first exceeded.
 * - [isProbablyBinary] mirrors the repo-wide sniff convention (see
 *   FolderGrep.BINARY_SNIFF_BYTES): a NUL byte within the first
 *   [BINARY_SNIFF_BYTES] bytes means binary; an empty array is never binary.
 */
object HexDump {
    /** Hard cap for both directions: 1 MiB of bytes. */
    const val MAX_BYTES = 1_048_576

    /** Bytes inspected by [isProbablyBinary] when looking for a NUL byte. */
    const val BINARY_SNIFF_BYTES = 8_192

    private const val MIN_BYTES_PER_LINE = 4
    private const val MAX_BYTES_PER_LINE = 64
    private const val MAX_OFFSET_DIGITS = 16

    private val HEX_LOWER = Array(256) { index -> index.toString(16).padStart(2, '0') }
    private val HEX_UPPER = Array(256) { index -> index.toString(16).padStart(2, '0').uppercase() }

    enum class ErrorCode {
        BYTES_PER_LINE,
        GROUP_SIZE,
        OFFSET_BASE,
        TOO_LARGE,
        EMPTY,
        ODD_HEX,
        INVALID_HEX,
    }

    /**
     * Dump layout parameters. Defaults reproduce the classic 16-bytes-per-row,
     * 2-byte-group hexdump layout with decimal-free 8-digit offsets.
     */
    data class DumpOptions(
        val bytesPerLine: Int = 16,
        val groupSize: Int = 2,
        val offsetBase: Long = 0,
        val uppercase: Boolean = false,
    )

    /** One rendered dump row: byte [offset] plus the aligned hex/ASCII columns. */
    data class DumpLine(
        val offset: Long,
        val hexText: String,
        val asciiText: String,
    )

    sealed interface ResultLines {
        data class Success(
            val lines: List<DumpLine>,
        ) : ResultLines

        data class Failure(
            val code: ErrorCode,
            val message: String,
        ) : ResultLines
    }

    sealed interface ParseResult {
        data class Success(
            val bytes: ByteArray,
        ) : ParseResult {
            override fun equals(other: Any?): Boolean = other is Success && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int = bytes.contentHashCode()
        }

        data class Failure(
            val code: ErrorCode,
            val message: String,
        ) : ParseResult
    }

    /**
     * Validates [options] before any formatting happens.
     *
     * @return `null` when the options are usable, otherwise a [ResultLines.Failure]
     *   carrying a specific message (what failed, why, and the allowed range).
     */
    internal fun validateOptions(options: DumpOptions): ResultLines.Failure? =
        when {
            options.bytesPerLine < MIN_BYTES_PER_LINE || options.bytesPerLine > MAX_BYTES_PER_LINE ->
                ResultLines.Failure(
                    ErrorCode.BYTES_PER_LINE,
                    "bytesPerLine is ${options.bytesPerLine}, allowed range is " +
                        "$MIN_BYTES_PER_LINE..$MAX_BYTES_PER_LINE",
                )
            options.groupSize < 1 || options.groupSize > options.bytesPerLine ->
                ResultLines.Failure(
                    ErrorCode.GROUP_SIZE,
                    "groupSize is ${options.groupSize}, allowed range is 1..${options.bytesPerLine} " +
                        "(must not exceed bytesPerLine)",
                )
            options.offsetBase < 0 ->
                ResultLines.Failure(
                    ErrorCode.OFFSET_BASE,
                    "offsetBase is ${options.offsetBase}, must be >= 0",
                )
            else -> null
        }

    /**
     * Splits [bytes] into aligned dump lines (empty input -> zero lines).
     * Never throws: invalid options and oversized input come back as
     * [ResultLines.Failure] with a specific message.
     */
    fun toDumpLines(
        bytes: ByteArray,
        options: DumpOptions = DumpOptions(),
    ): ResultLines {
        validateOptions(options)?.let { return it }
        if (bytes.size > MAX_BYTES) {
            return ResultLines.Failure(
                ErrorCode.TOO_LARGE,
                "input has ${bytes.size} bytes, limit is $MAX_BYTES",
            )
        }
        if (bytes.isEmpty()) {
            // Documented decision: an empty file dumps to zero lines; the
            // viewer shows a dedicated "0 byte" state for it.
            return ResultLines.Success(emptyList())
        }
        val lineCount = (bytes.size + options.bytesPerLine - 1) / options.bytesPerLine
        val width = hexWidth(options.bytesPerLine, options.groupSize)
        val digits = offsetDigits(options.offsetBase, bytes.size)
        val lines = ArrayList<DumpLine>(lineCount)
        for (line in 0 until lineCount) {
            val start = line * options.bytesPerLine
            val count = minOf(options.bytesPerLine, bytes.size - start)
            lines.add(
                DumpLine(
                    offset = options.offsetBase + start,
                    hexText = hexColumn(bytes, start, count, options, width),
                    asciiText = asciiColumn(bytes, start, count, options.bytesPerLine),
                ),
            )
        }
        return ResultLines.Success(lines)
    }

    /**
     * Whole dump as one text: `offset` + 2 spaces + hex column + 2 spaces +
     * ASCII column per line, joined with `\n` and no trailing newline.
     * Empty input yields `""`.
     *
     * Failure contract: unlike [toDumpLines] this convenience returns the
     * [String] directly, so invalid options or oversized input throw
     * [IllegalArgumentException] carrying the same specific message. Callers
     * that cannot guarantee the cap should call [toDumpLines] instead.
     */
    fun toDumpText(
        bytes: ByteArray,
        options: DumpOptions = DumpOptions(),
    ): String {
        val lines =
            when (val result = toDumpLines(bytes, options)) {
                is ResultLines.Failure -> throw IllegalArgumentException(result.message)
                is ResultLines.Success -> result.lines
            }
        if (lines.isEmpty()) {
            return ""
        }
        val digits = offsetDigits(options.offsetBase, bytes.size)
        return lines.joinToString(separator = "\n") { line ->
            offsetText(line.offset, digits, options.uppercase) + "  " +
                line.hexText + "  " + line.asciiText
        }
    }

    /**
     * Parses a dump with the standard [MAX_BYTES] cap. See [parseWithCap] for
     * the full tolerance and error rules.
     */
    fun parse(dump: String): ParseResult = parseWithCap(dump, MAX_BYTES)

    /**
     * Test-visible [parse] with a replaceable cap so TOO_LARGE can be
     * exercised without allocating 1 MiB of text.
     *
     * Rules: split on `\n`, strip a trailing `\r`, skip blank lines and lines
     * that do not start with a 1-16 hex-digit offset followed by whitespace.
     * Within a data line the hex portion runs to two or more consecutive
     * spaces (the ASCII column) or end of line; single spaces inside it are
     * group separators and are removed before decoding. An odd digit count
     * fails with ODD_HEX at the unpaired digit, a non-hex character fails with
     * INVALID_HEX at its column, a decoded size above [cap] fails with
     * TOO_LARGE, and a dump with no decodable data fails with EMPTY.
     */
    internal fun parseWithCap(
        dump: String,
        cap: Int,
    ): ParseResult {
        if (cap < 0) {
            throw IllegalArgumentException("cap is $cap, must be >= 0")
        }
        if (dump.isBlank()) {
            return ParseResult.Failure(
                ErrorCode.EMPTY,
                "dump is empty or contains only whitespace; parsing nothing is an error " +
                    "(toDumpLines of an empty array succeeds by design, parse does not)",
            )
        }
        val lines = dump.split('\n')
        val chunks = ArrayList<ByteArray>()
        var total = 0
        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.removeSuffix("\r")
            when (val parsed = parseDataLine(line)) {
                null -> {
                    // Tolerated junk: blank or missing an offset prefix; skip.
                }
                is LineData.Bad -> return ParseResult.Failure(
                    parsed.code,
                    "line ${index + 1}, column ${parsed.column}: ${parsed.detail}",
                )
                is LineData.Data -> {
                    if (total + parsed.data.size > cap) {
                        return ParseResult.Failure(
                            ErrorCode.TOO_LARGE,
                            "decoded dump exceeds the cap: at least ${total + parsed.data.size} " +
                                "bytes, cap is $cap (first exceeded at line ${index + 1})",
                        )
                    }
                    total += parsed.data.size
                    chunks.add(parsed.data)
                }
            }
        }
        if (total == 0) {
            return ParseResult.Failure(
                ErrorCode.EMPTY,
                "no hex data found in dump (${lines.size} lines scanned, all blank, junk " +
                    "or offset-only)",
            )
        }
        val out = ByteArray(total)
        var position = 0
        for (chunk in chunks) {
            chunk.copyInto(out, position)
            position += chunk.size
        }
        return ParseResult.Success(out)
    }

    /**
     * Binary sniff: `true` iff a NUL byte occurs within the first
     * [BINARY_SNIFF_BYTES] bytes. An empty array is never binary.
     */
    fun isProbablyBinary(bytes: ByteArray): Boolean {
        val end = minOf(bytes.size, BINARY_SNIFF_BYTES)
        for (i in 0 until end) {
            if (bytes[i] == 0.toByte()) {
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------ internals

    private sealed interface LineData {
        data class Data(
            val data: ByteArray,
        ) : LineData

        data class Bad(
            val code: ErrorCode,
            val column: Int,
            val detail: String,
        ) : LineData
    }

    /**
     * Parses one dump line: `null` for tolerated junk (blank, or no 1-16 hex
     * digit offset followed by whitespace), [LineData.Bad] for positional
     * errors, [LineData.Data] for the decoded bytes (possibly zero of them).
     */
    private fun parseDataLine(line: String): LineData? {
        var offsetEnd = 0
        while (offsetEnd < line.length && line[offsetEnd].isHexDigit()) {
            offsetEnd++
        }
        if (offsetEnd == 0 || offsetEnd > MAX_OFFSET_DIGITS) {
            return null
        }
        if (offsetEnd >= line.length || !line[offsetEnd].isWhitespace()) {
            return null
        }
        var i = offsetEnd
        while (i < line.length && line[i].isWhitespace()) {
            i++
        }
        if (i >= line.length) {
            return LineData.Data(ByteArray(0))
        }
        val hex = StringBuilder()
        var lastDigitColumn = -1
        var spaces = 0
        while (i < line.length) {
            val c = line[i]
            if (c == ' ') {
                spaces++
                if (spaces >= 2) {
                    break
                }
                hex.append(' ')
            } else {
                spaces = 0
                if (c.isHexDigit()) {
                    hex.append(c)
                    lastDigitColumn = i
                } else {
                    return LineData.Bad(
                        ErrorCode.INVALID_HEX,
                        i,
                        "invalid hex character '$c'; allowed are 0-9, a-f, A-F and " +
                            "single spaces between groups",
                    )
                }
            }
            i++
        }
        val digits = hex.toString().replace(" ", "")
        if (digits.isEmpty()) {
            return LineData.Data(ByteArray(0))
        }
        if (digits.length % 2 != 0) {
            return LineData.Bad(
                ErrorCode.ODD_HEX,
                lastDigitColumn,
                "odd number of hex digits (${digits.length}); digits must come in byte " +
                    "pairs (unpaired digit '${digits.last()}')",
            )
        }
        val data = ByteArray(digits.length / 2)
        for (k in data.indices) {
            data[k] = ((hexValue(digits[2 * k]) shl 4) or hexValue(digits[2 * k + 1])).toByte()
        }
        return LineData.Data(data)
    }

    private fun hexColumn(
        bytes: ByteArray,
        start: Int,
        count: Int,
        options: DumpOptions,
        width: Int,
    ): String {
        val table = if (options.uppercase) HEX_UPPER else HEX_LOWER
        val sb = StringBuilder(width)
        for (i in 0 until count) {
            if (i > 0 && i % options.groupSize == 0) {
                sb.append(' ')
            }
            sb.append(table[bytes[start + i].toInt() and 0xFF])
        }
        while (sb.length < width) {
            sb.append(' ')
        }
        return sb.toString()
    }

    private fun asciiColumn(
        bytes: ByteArray,
        start: Int,
        count: Int,
        bytesPerLine: Int,
    ): String {
        val sb = StringBuilder(bytesPerLine)
        for (i in 0 until count) {
            val value = bytes[start + i].toInt() and 0xFF
            sb.append(if (value in 0x20..0x7E) value.toChar() else '.')
        }
        while (sb.length < bytesPerLine) {
            sb.append(' ')
        }
        return sb.toString()
    }

    /** Fixed hex-column width for a full line: hex digits plus group separators. */
    private fun hexWidth(
        bytesPerLine: Int,
        groupSize: Int,
    ): Int {
        val groups = (bytesPerLine + groupSize - 1) / groupSize
        return bytesPerLine * 2 + (groups - 1)
    }

    /** Offset digit width: at least 8, grown only when the highest offset needs more. */
    private fun offsetDigits(
        offsetBase: Long,
        size: Int,
    ): Int {
        var needed = 1
        var value = offsetBase + (size - 1).coerceAtLeast(0)
        while (value >= 16) {
            value /= 16
            needed++
        }
        return if (needed < 8) 8 else needed
    }

    private fun offsetText(
        offset: Long,
        digits: Int,
        uppercase: Boolean,
    ): String {
        val raw = offset.toString(16).padStart(digits, '0')
        return if (uppercase) raw.uppercase() else raw
    }

    private fun hexValue(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw IllegalStateException(
                "non-hex character '$c' reached hexValue; parser validation gap",
            )
        }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
