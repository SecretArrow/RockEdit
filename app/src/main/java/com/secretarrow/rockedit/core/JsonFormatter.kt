package com.secretarrow.rockedit.core

/**
 * JSON formatter.
 *
 * Uses a small, strict (RFC 8259) parser written for this project:
 * - errors carry 1-based line/column positions,
 * - number tokens are kept VERBATIM (no float conversion: "1.10", "1e3",
 *   and arbitrarily large integers survive formatting byte-for-byte),
 * - object key order is preserved,
 * - depth is bounded ([MAX_DEPTH]) and the deadline is polled while lexing
 *   and rendering, so huge or pathological inputs fail fast instead of
 *   hanging the caller.
 */
class JsonFormatter(
    nowMs: () -> Long = System::currentTimeMillis,
) : AbstractCodeFormatter(nowMs) {
    override val id: String = "json"
    override val supportedLanguages: Set<String> = setOf("json")

    override fun formatValidated(
        language: String,
        text: String,
        options: FormatOptions,
        deadline: Deadline,
    ): FormatResult {
        val value =
            try {
                JsonLexer(text, deadline).parseTopLevel()
            } catch (e: JsonLexer.PositionError) {
                return FormatResult.Failure(
                    FormatError(FormatErrorCode.PARSE_ERROR, "invalid JSON: ${e.message}", e.line, e.column),
                )
            }
        // TimeoutSignal / DepthSignal propagate to the guard pipeline on purpose.
        val out = StringBuilder(text.length + (text.length / 4) + 16)
        if (options.minify) {
            renderCompact(value, out, depth = 0, deadline)
        } else {
            renderPretty(value, out, depth = 0, options, deadline)
        }
        val formatted = applyFinalTouches(out, options)
        return FormatResult.Success(formatted, formatted != text, 0L)
    }

    private fun renderPretty(
        value: JsonValue,
        out: StringBuilder,
        depth: Int,
        options: FormatOptions,
        deadline: Deadline,
    ) {
        if (depth > MAX_DEPTH) throw DepthSignal(MAX_DEPTH)
        if (deadline.isExpired()) throw TimeoutSignal(deadline.budgetMs)
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Number -> out.append(value.raw)
            is JsonValue.Text -> renderString(value.value, out)
            is JsonValue.Array -> {
                if (value.items.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                val last = value.items.size - 1
                value.items.forEachIndexed { index, item ->
                    out.append(pad(options, depth + 1))
                    renderPretty(item, out, depth + 1, options, deadline)
                    if (index < last) out.append(',')
                    out.append('\n')
                }
                out.append(pad(options, depth)).append(']')
            }
            is JsonValue.Object -> {
                if (value.entries.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                val last = value.entries.size - 1
                value.entries.forEachIndexed { index, (key, item) ->
                    out.append(pad(options, depth + 1))
                    renderString(key, out)
                    out.append(": ")
                    renderPretty(item, out, depth + 1, options, deadline)
                    if (index < last) out.append(',')
                    out.append('\n')
                }
                out.append(pad(options, depth)).append('}')
            }
        }
    }

    private fun renderCompact(
        value: JsonValue,
        out: StringBuilder,
        depth: Int,
        deadline: Deadline,
    ) {
        if (depth > MAX_DEPTH) throw DepthSignal(MAX_DEPTH)
        if (deadline.isExpired()) throw TimeoutSignal(deadline.budgetMs)
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Number -> out.append(value.raw)
            is JsonValue.Text -> renderString(value.value, out)
            is JsonValue.Array -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    renderCompact(item, out, depth + 1, deadline)
                }
                out.append(']')
            }
            is JsonValue.Object -> {
                out.append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(',')
                    renderString(key, out)
                    out.append(':')
                    renderCompact(item, out, depth + 1, deadline)
                }
                out.append('}')
            }
        }
    }

    private fun renderString(
        value: String,
        out: StringBuilder,
    ) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> {
                    if (c < ' ') {
                        // Other control characters must be escaped as \uXXXX.
                        val hex = c.code.toString(16).padStart(4, '0')
                        out.append("\\u").append(hex)
                    } else {
                        out.append(c) // non-ASCII stays readable (no \u00e9 noise)
                    }
                }
            }
        }
        out.append('"')
    }

    companion object {
        const val MAX_DEPTH = 512
    }
}

/** Parsed JSON value tree. Numbers keep their raw source text. */
internal sealed class JsonValue {
    object Null : JsonValue()

    data class Bool(
        val value: Boolean,
    ) : JsonValue()

    data class Number(
        val raw: String,
    ) : JsonValue()

    data class Text(
        val value: String,
    ) : JsonValue()

    data class Array(
        val items: List<JsonValue>,
    ) : JsonValue()

    data class Object(
        val entries: List<Pair<String, JsonValue>>,
    ) : JsonValue()
}

/**
 * Strict JSON lexer/parser with 1-based line/column tracking.
 * The [deadline] is polled at least every 1 KiB of consumed input.
 */
internal class JsonLexer(
    private val text: String,
    private val deadline: Deadline,
) {
    class PositionError(
        message: String,
        val line: Int,
        val column: Int,
    ) : RuntimeException("$message (line $line, column $column)")

    private var pos = 0
    private var line = 1
    private var col = 1

    fun parseTopLevel(): JsonValue {
        skipWhitespace()
        val value = parseValue(0)
        skipWhitespace()
        if (pos < text.length) {
            fail("unexpected content after the top-level value")
        }
        return value
    }

    private fun parseValue(depth: Int): JsonValue {
        if (depth > JsonFormatter.MAX_DEPTH) throw DepthSignal(JsonFormatter.MAX_DEPTH)
        pollDeadline()
        if (pos >= text.length) fail("unexpected end of input, a value was expected")
        return when (val c = text[pos]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> JsonValue.Text(parseString())
            't' -> parseKeyword("true", JsonValue.Bool(true))
            'f' -> parseKeyword("false", JsonValue.Bool(false))
            'n' -> parseKeyword("null", JsonValue.Null)
            '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> parseNumber()
            else -> fail("unexpected character '$c', a value was expected")
        }
    }

    private fun parseObject(depth: Int): JsonValue {
        expect('{')
        val entries = ArrayList<Pair<String, JsonValue>>()
        skipWhitespace()
        if (peek() == '}') {
            advance()
            return JsonValue.Object(entries)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("expected a string key in object")
            val key = parseString()
            skipWhitespace()
            if (peek() != ':') fail("expected ':' after object key")
            advance()
            skipWhitespace()
            val value = parseValue(depth + 1)
            entries.add(key to value)
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    advance()
                    // Strict JSON: a closing brace right after a comma is a
                    // trailing comma, which RFC 8259 forbids.
                    skipWhitespace()
                    if (peek() == '}') fail("trailing comma before '}'")
                }
                '}' -> {
                    advance()
                    return JsonValue.Object(entries)
                }
                else -> fail("expected ',' or '}' in object")
            }
        }
    }

    private fun parseArray(depth: Int): JsonValue {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            advance()
            return JsonValue.Array(items)
        }
        while (true) {
            skipWhitespace()
            items.add(parseValue(depth + 1))
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    advance()
                    skipWhitespace()
                    if (peek() == ']') fail("trailing comma before ']'")
                }
                ']' -> {
                    advance()
                    return JsonValue.Array(items)
                }
                else -> fail("expected ',' or ']' in array")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("unterminated string")
            val c = text[pos]
            when {
                c == '"' -> {
                    advance()
                    return sb.toString()
                }
                c == '\\' -> {
                    advance()
                    if (pos >= text.length) fail("unterminated escape sequence")
                    when (val esc = text[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            repeat(4) {
                                advance()
                                if (pos >= text.length) fail("unterminated \\u escape")
                                if (text[pos].lowercaseChar() !in "0123456789abcdef") {
                                    fail("invalid hex digit in \\u escape")
                                }
                            }
                            val hex = text.substring(pos - 3, pos + 1)
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> fail("invalid escape character '\\$esc'")
                    }
                    advance()
                }
                c < ' ' -> fail("raw control character inside string")
                else -> {
                    sb.append(c)
                    advance()
                }
            }
        }
    }

    private fun parseNumber(): JsonValue {
        val start = pos
        if (peek() == '-') advance()
        // Integer part: strict JSON forbids leading zeros ("01" is invalid).
        when {
            pos >= text.length -> fail("unexpected end of input inside number")
            text[pos] == '0' -> advance()
            text[pos] in '1'..'9' -> while (pos < text.length && text[pos] in '0'..'9') advance()
            else -> fail("invalid number")
        }
        if (pos < text.length && text[pos] == '.') {
            advance()
            if (pos >= text.length || text[pos] !in '0'..'9') fail("digit expected after decimal point")
            while (pos < text.length && text[pos] in '0'..'9') advance()
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            advance()
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) advance()
            if (pos >= text.length || text[pos] !in '0'..'9') fail("digit expected in exponent")
            while (pos < text.length && text[pos] in '0'..'9') advance()
        }
        return JsonValue.Number(text.substring(start, pos))
    }

    private fun parseKeyword(
        word: String,
        value: JsonValue,
    ): JsonValue {
        for (expected in word) {
            if (pos >= text.length || text[pos] != expected) fail("invalid literal, expected '$word'")
            advance()
        }
        return value
    }

    // ------------------------------------------------------------ low level

    private fun pollDeadline() {
        if ((pos and 0x3FF) == 0 && deadline.isExpired()) throw TimeoutSignal(deadline.budgetMs)
    }

    private fun peek(): Char {
        if (pos >= text.length) fail("unexpected end of input")
        return text[pos]
    }

    private fun expect(expected: Char) {
        if (pos >= text.length || text[pos] != expected) {
            fail("expected '$expected'")
        }
        advance()
    }

    private fun skipWhitespace() {
        while (pos < text.length) {
            when (text[pos]) {
                ' ', '\t', '\n', '\r' -> advance()
                else -> return
            }
        }
    }

    private fun advance() {
        if (text[pos] == '\n') {
            line++
            col = 1
        } else {
            col++
        }
        pos++
        pollDeadline()
    }

    private fun fail(message: String): Nothing = throw PositionError(message, line, col)
}
