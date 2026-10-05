package com.secretarrow.rockedit.core

/**
 * Wire contract of the prettier WASM/WebView engine (backlog item 10).
 *
 * The envelope JSON is built and parsed MANUALLY here — no org.json —
 * because JVM unit tests run against a stubbed org.json, and because a
 * hand-rolled scanner is fully deterministic on both sides of the bridge.
 *
 * Request (Kotlin -> JS, [buildPayload]):
 * ```
 * {"id":"<id>","parser":"babel","text":"<source>",
 *  "options":{"tabWidth":4,"useTabs":false,"endOfLine":"lf"}}
 * ```
 * Response (JS -> Kotlin, built by assets/formatter/host.html):
 * ```
 * {"id":"<id>","ok":true,"text":"<formatted>"}
 * {"id":"<id>","ok":false,"code":"PARSE_ERROR","message":"<why>"}
 * ```
 *
 * Escaping rules (UTF-8 passthrough for everything >= 0x20, so emoji and
 * literal `</script>` survive verbatim):
 * - `\` -> `\\`, `"` -> `\"`, newline -> `\n`, CR -> `\r`, tab -> `\t`,
 * - every other control character (< 0x20) -> `\u00XX`.
 *
 * Documented decision: [FormatOptions.minify] is IGNORED — prettier has no
 * minifier, so the request never carries it and formatting is unaffected.
 *
 * [parsePayload] and [decodeJsonString] NEVER throw: every malformed input
 * surfaces as a [WasmResponse.Err] with code [CODE_MALFORMED] (or null),
 * never as an exception.
 */
object WasmFormatterContract {
    const val CODE_MALFORMED = "MALFORMED"
    const val CODE_ID_MISMATCH = "ID_MISMATCH"
    const val CODE_ENGINE_ERROR = "ENGINE_ERROR"

    private const val KEY_ID = "id"
    private const val KEY_OK = "ok"
    private const val KEY_TEXT = "text"
    private const val KEY_CODE = "code"
    private const val KEY_MESSAGE = "message"

    // ---------------------------------------------------------- JSON building

    /**
     * Builds the request envelope for one format call. [id] correlates
     * request and response; [parser] is a prettier parser name from
     * [WasmFormatterCatalog.parserFor]; options map to prettier as:
     * tabWidth = indentSize, useTabs = (indentStyle == TABS),
     * endOfLine = "crlf" for CRLF, "lf" otherwise (prettier has no lone-CR
     * mode; CR-only files format as LF — documented degradation).
     */
    fun buildPayload(
        id: String,
        parser: String,
        text: String,
        options: FormatOptions,
    ): String {
        val out = StringBuilder(text.length + 128)
        out
            .append("{\"")
            .append(KEY_ID)
            .append("\":\"")
            .append(escapeJson(id))
        out.append("\",\"parser\":\"").append(escapeJson(parser))
        out.append("\",\"text\":\"").append(escapeJson(text))
        out.append("\",\"options\":{\"tabWidth\":").append(options.indentSize)
        out.append(",\"useTabs\":")
        out.append(if (options.indentStyle == IndentStyle.TABS) "true" else "false")
        out.append(",\"endOfLine\":\"").append(endOfLineName(options.lineBreak))
        out.append("\"}}")
        return out.toString()
    }

    /**
     * Builds a success envelope in the exact host.html shape. Used by the
     * WebView host bridge and by tests that fake the JS side.
     */
    fun buildOkResponse(
        id: String,
        text: String,
    ): String =
        StringBuilder(text.length + 32)
            .append("{\"")
            .append(KEY_ID)
            .append("\":\"")
            .append(escapeJson(id))
            .append("\",\"")
            .append(KEY_OK)
            .append("\":true,\"")
            .append(KEY_TEXT)
            .append("\":\"")
            .append(escapeJson(text))
            .append("\"}")
            .toString()

    /** Builds a failure envelope in the exact host.html shape. */
    fun buildErrResponse(
        id: String,
        code: String,
        message: String,
    ): String =
        StringBuilder(message.length + 64)
            .append("{\"")
            .append(KEY_ID)
            .append("\":\"")
            .append(escapeJson(id))
            .append("\",\"")
            .append(KEY_OK)
            .append("\":false,\"")
            .append(KEY_CODE)
            .append("\":\"")
            .append(escapeJson(code))
            .append("\",\"")
            .append(KEY_MESSAGE)
            .append("\":\"")
            .append(escapeJson(message))
            .append("\"}")
            .toString()

    private fun endOfLineName(lineBreak: LineBreak): String =
        when (lineBreak) {
            LineBreak.CRLF -> "crlf"
            // Lone CR has no prettier equivalent; it formats as LF (documented).
            LineBreak.LF, LineBreak.CR -> "lf"
        }

    /**
     * JSON-escapes one string value: `\`, `"`, `\n`, `\r`, `\t` get named
     * escapes, other control characters become `\u00XX`, and everything
     * >= 0x20 passes through untouched (UTF-8 passthrough).
     */
    private fun escapeJson(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else ->
                    if (ch.code < 0x20) {
                        out.append(unicodeEscape(ch.code))
                    } else {
                        out.append(ch)
                    }
            }
        }
        return out.toString()
    }

    private fun unicodeEscape(code: Int): String {
        val digits = "0123456789abcdef"
        val hex =
            "" +
                digits[(code shr 12) and 0xF] +
                digits[(code shr 8) and 0xF] +
                digits[(code shr 4) and 0xF] +
                digits[code and 0xF]
        return "\\u$hex"
    }

    // ------------------------------------------------------------ JSON parsing

    /** One parsed top-level JSON value. Numbers/null/containers are "Other". */
    private sealed interface JsonValue {
        data class Str(
            val value: String,
        ) : JsonValue

        data class Bool(
            val value: Boolean,
        ) : JsonValue

        object Other : JsonValue
    }

    /** Internal control-flow signal of the hand-rolled scanner. Never leaks. */
    private class MalformedJsonException(
        message: String,
    ) : Exception(message)

    /**
     * Minimal strict JSON scanner: enough to read the flat envelope object
     * (string/boolean top-level values) and to skip anything else
     * (numbers, null, nested containers) in a string-aware, balanced way.
     */
    private class JsonScanner(
        private val src: String,
    ) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun peek(): Char? = if (pos < src.length) src[pos] else null

        private fun fail(message: String): Nothing = throw MalformedJsonException("$message (offset $pos)")

        private fun expect(expected: Char) {
            val c = peek()
            if (c != expected) fail("expected '$expected' but found '$c'")
            pos++
        }

        /**
         * Reads one JSON string (opening quote included), applying the full
         * unescape set: `\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t` and
         * `\uXXXX` (surrogate pairs pass through as two chars). Raw control
         * characters and unknown escapes are rejected, never guessed.
         */
        fun scanString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (pos >= src.length) fail("unterminated string")
                when (val c = src[pos]) {
                    '"' -> {
                        pos++
                        return out.toString()
                    }
                    '\\' -> {
                        pos++
                        if (pos >= src.length) fail("unterminated escape")
                        when (val esc = src[pos]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 >= src.length) fail("truncated \\u escape")
                                val hex = src.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16) ?: fail("bad \\u escape \"$hex\"")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("unknown escape '\\$esc'")
                        }
                        pos++
                    }
                    else -> {
                        if (c.code < 0x20) fail("raw control character inside string")
                        out.append(c)
                        pos++
                    }
                }
            }
        }

        /** Dispatches one JSON value; containers/numbers/null become [JsonValue.Other]. */
        fun scanValue(): JsonValue =
            when (val c = peek()) {
                '"' -> JsonValue.Str(scanString())
                't' -> {
                    scanLiteral("true")
                    JsonValue.Bool(true)
                }
                'f' -> {
                    scanLiteral("false")
                    JsonValue.Bool(false)
                }
                'n' -> {
                    scanLiteral("null")
                    JsonValue.Other
                }
                '{', '[' -> {
                    skipContainer()
                    JsonValue.Other
                }
                else -> {
                    if (c == null || (c != '-' && !c.isDigit())) {
                        fail("unexpected character '$c'")
                    }
                    scanNumber()
                    JsonValue.Other
                }
            }

        private fun scanLiteral(word: String) {
            if (!src.startsWith(word, pos)) fail("invalid literal at offset $pos")
            pos += word.length
        }

        private fun scanNumber() {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] in "-+.eE")) pos++
            if (pos == start) fail("invalid number at offset $start")
        }

        /** Balanced, string-aware skip of a nested `{...}` / `[...]` block. */
        private fun skipContainer() {
            val first = peek() ?: fail("nothing to skip")
            val stack = ArrayDeque<Char>()
            stack.addLast(if (first == '{') '}' else ']')
            pos++
            while (stack.isNotEmpty()) {
                if (pos >= src.length) fail("unterminated container")
                when (val c = src[pos]) {
                    '"' -> scanString()
                    '{' -> {
                        stack.addLast('}')
                        pos++
                    }
                    '[' -> {
                        stack.addLast(']')
                        pos++
                    }
                    '}', ']' -> {
                        if (stack.removeLast() != c) fail("mismatched container at offset $pos")
                        pos++
                    }
                    else -> pos++
                }
            }
        }

        /** Reads the top-level flat object into a key -> value map. */
        fun scanObject(): MutableMap<String, JsonValue> {
            val map = HashMap<String, JsonValue>()
            expect('{')
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected an object key")
                val key = scanString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = scanValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> fail("expected ',' or '}' between members")
                }
            }
        }
    }

    private fun scanTopLevelObject(json: String): MutableMap<String, JsonValue>? =
        try {
            val scanner = JsonScanner(json)
            scanner.skipWhitespace()
            if (scanner.peek() != '{') {
                null
            } else {
                val map = scanner.scanObject()
                scanner.skipWhitespace()
                if (scanner.atEnd()) map else null
            }
        } catch (_: MalformedJsonException) {
            null
        }

    /**
     * Parses the engine response envelope. Behavior:
     * - malformed JSON / missing keys / wrong value types -> Err([CODE_MALFORMED]),
     * - id differs from [expectedId] -> Err([CODE_ID_MISMATCH]),
     * - ok=false -> Err(code from the payload, or [CODE_ENGINE_ERROR] when absent),
     * - ok=true -> Ok(text).
     * NEVER throws (belt-and-suspenders catch-all included).
     */
    fun parsePayload(
        expectedId: String,
        json: String,
    ): WasmResponse =
        try {
            parsePayloadChecked(expectedId, json)
        } catch (e: Exception) {
            WasmResponse.Err(
                CODE_MALFORMED,
                "engine response could not be parsed: ${e.message ?: e::class.java.simpleName}",
            )
        }

    private fun parsePayloadChecked(
        expectedId: String,
        json: String,
    ): WasmResponse {
        val fields =
            scanTopLevelObject(json)
                ?: return WasmResponse.Err(CODE_MALFORMED, malformedMessage(json))
        val idValue =
            fields[KEY_ID]
                ?: return WasmResponse.Err(CODE_MALFORMED, "engine response is missing the \"id\" key")
        val id =
            (idValue as? JsonValue.Str)?.value
                ?: return WasmResponse.Err(CODE_MALFORMED, "engine response \"id\" is not a string")
        if (id != expectedId) {
            return WasmResponse.Err(
                CODE_ID_MISMATCH,
                "engine response id \"$id\" does not match the expected id \"$expectedId\"",
            )
        }
        val okValue =
            fields[KEY_OK]
                ?: return WasmResponse.Err(CODE_MALFORMED, "engine response is missing the \"ok\" key")
        val ok =
            (okValue as? JsonValue.Bool)?.value
                ?: return WasmResponse.Err(CODE_MALFORMED, "engine response \"ok\" is not a boolean")
        return if (ok) {
            val textValue =
                fields[KEY_TEXT]
                    ?: return WasmResponse.Err(
                        CODE_MALFORMED,
                        "successful engine response is missing the \"text\" key",
                    )
            val text =
                (textValue as? JsonValue.Str)?.value
                    ?: return WasmResponse.Err(CODE_MALFORMED, "engine response \"text\" is not a string")
            WasmResponse.Ok(text)
        } else {
            val code = (fields[KEY_CODE] as? JsonValue.Str)?.value ?: CODE_ENGINE_ERROR
            val message = (fields[KEY_MESSAGE] as? JsonValue.Str)?.value ?: ""
            WasmResponse.Err(code, message)
        }
    }

    private fun malformedMessage(json: String): String {
        val trimmed = json.trim()
        val head =
            if (trimmed.length <= 32) {
                trimmed
            } else {
                trimmed.substring(0, 32) + "..."
            }
        val shown = head.replace("\n", "\\n").replace("\r", "\\r")
        return "engine response is not a valid JSON object (${json.length} chars): \"$shown\""
    }

    /**
     * Decodes a JSON-encoded string (the wrapper evaluateJavascript puts
     * around the JS return value). Returns null when [raw] is not a single
     * JSON string. Used by the WebView host, kept here so the unescape
     * rules live in exactly one place.
     */
    fun decodeJsonString(raw: String): String? =
        try {
            val scanner = JsonScanner(raw)
            scanner.skipWhitespace()
            if (scanner.peek() != '"') {
                null
            } else {
                val value = scanner.scanString()
                scanner.skipWhitespace()
                if (scanner.atEnd()) value else null
            }
        } catch (_: Exception) {
            null
        }
}

/**
 * Result of [WasmFormatterContract.parsePayload]:
 * - [Ok] carries the formatted text,
 * - [Err] carries a machine-readable code (PARSE_ERROR, MALFORMED,
 *   ID_MISMATCH, ENGINE_ERROR or anything the engine sent) and a message.
 */
sealed class WasmResponse {
    data class Ok(
        val text: String,
    ) : WasmResponse()

    data class Err(
        val code: String,
        val message: String,
    ) : WasmResponse()
}
