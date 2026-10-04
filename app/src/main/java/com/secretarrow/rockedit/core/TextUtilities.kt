package com.secretarrow.rockedit.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Pure text transformation toolkit (v0.11.0 "productivity tools").
 *
 * Defensive contract (mirrors the Code Formatter):
 * - Empty/blank input maps to [TextResult.Skipped] — never an error.
 * - Input above [MAX_INPUT_CHARS] maps to [TextResult.Failure] with
 *   INPUT_TOO_LARGE before any work happens.
 * - Decoders (Base64/URL/JSON-unescape) REJECT malformed input with a
 *   specific position instead of silently producing garbage.
 * - The HTML entity decoder is fail-safe: an unknown entity is kept
 *   verbatim (documented assumption), everything else still decodes.
 * - Every operation is total: no branch throws to the caller.
 *
 * Documented assumptions (rule 8):
 * - Hashes of the blank input are routed to Skipped because the dialog
 *   operates on the document/selection which the UI already refuses to
 *   run on blank text (consistent UX).
 * - Case transforms split words on every non-alphanumeric character and
 *   lowercase each word ("HTTP_Server" -> "httpServer").
 * - Line sort is plain code-unit lexicographic (stable, locale-independent);
 *   dedupe keeps the FIRST occurrence (order preserving).
 * - URL encode uses UTF-8 with the form-style safe set (space -> '+');
 *   decode treats '+' as space. They are a documented round-trip pair.
 * - Base64 accepts the standard and the URL-safe alphabets (never mixed),
 *   padded or unpadded; non-zero trailing bits are ignored (lenient tail).
 *   A pure-Kotlin implementation is used so JVM unit tests run unmocked.
 */
object TextUtilities {
    const val MAX_INPUT_CHARS = 2_000_000

    /** Every supported operation. The UI lists them in enum order. */
    enum class Op {
        BASE64_ENCODE,
        BASE64_DECODE,
        URL_ENCODE,
        URL_DECODE,
        HTML_ENCODE,
        HTML_DECODE,
        MD5,
        SHA1,
        SHA256,
        CAMEL_CASE,
        SNAKE_CASE,
        KEBAB_CASE,
        SORT_LINES_ASC,
        SORT_LINES_DESC,
        DEDUPE_LINES,
        REVERSE_LINES,
        JSON_ESCAPE,
        JSON_UNESCAPE,
    }

    enum class ErrorCode { INPUT_TOO_LARGE, PARSE_ERROR, INTERNAL_ERROR }

    data class TextError(
        val code: ErrorCode,
        val message: String,
        val position: Int? = null,
    )

    sealed class TextResult {
        data class Success(
            val text: String,
            val changed: Boolean,
        ) : TextResult()

        data class Failure(
            val error: TextError,
        ) : TextResult()

        data class Skipped(
            val reason: String,
        ) : TextResult()
    }

    /** Single entry point; never throws. */
    fun run(
        op: Op,
        input: String,
    ): TextResult {
        if (input.isBlank()) return TextResult.Skipped("empty input")
        if (input.length > MAX_INPUT_CHARS) {
            return TextResult.Failure(
                TextError(ErrorCode.INPUT_TOO_LARGE, "input exceeds $MAX_INPUT_CHARS characters"),
            )
        }
        return try {
            when (op) {
                Op.BASE64_ENCODE -> ok(encodeBase64(input.toByteArray(Charsets.UTF_8)), input)
                Op.BASE64_DECODE -> decodeBase64(input)
                Op.URL_ENCODE -> ok(URLEncoder.encode(input, "UTF-8"), input)
                Op.URL_DECODE -> decodeUrl(input)
                Op.HTML_ENCODE -> ok(encodeHtmlEntities(input), input)
                Op.HTML_DECODE -> ok(decodeHtmlEntities(input), input)
                Op.MD5 -> ok(hashHex(input, "MD5"), input)
                Op.SHA1 -> ok(hashHex(input, "SHA-1"), input)
                Op.SHA256 -> ok(hashHex(input, "SHA-256"), input)
                Op.CAMEL_CASE -> ok(joinWords(input, CaseStyle.CAMEL), input)
                Op.SNAKE_CASE -> ok(joinWords(input, CaseStyle.SNAKE), input)
                Op.KEBAB_CASE -> ok(joinWords(input, CaseStyle.KEBAB), input)
                Op.SORT_LINES_ASC -> ok(sortLines(input, descending = false), input)
                Op.SORT_LINES_DESC -> ok(sortLines(input, descending = true), input)
                Op.DEDUPE_LINES -> ok(dedupeLines(input), input)
                Op.REVERSE_LINES -> ok(reverseLines(input), input)
                Op.JSON_ESCAPE -> ok(escapeJson(input), input)
                Op.JSON_UNESCAPE -> unescapeJson(input)
            }
        } catch (e: OutOfMemoryError) {
            TextResult.Failure(TextError(ErrorCode.INTERNAL_ERROR, "not enough memory for this operation"))
        } catch (e: Exception) {
            // Last-resort guard: every operation above is total, but any
            // unexpected runtime condition must still fail with a message.
            TextResult.Failure(TextError(ErrorCode.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName))
        }
    }

    // ------------------------------------------------------------ helpers

    private fun ok(
        result: String,
        input: String,
    ): TextResult.Success = TextResult.Success(result, result != input)

    private fun parseError(
        message: String,
        position: Int? = null,
    ): TextResult.Failure = TextResult.Failure(TextError(ErrorCode.PARSE_ERROR, message, position))

    // ------------------------------------------------------------- Base64

    private const val B64_STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val B64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private fun encodeBase64(bytes: ByteArray): String {
        val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n =
                ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                    (bytes[i + 2].toInt() and 0xFF)
            sb
                .append(B64_STD[(n ushr 18) and 63])
                .append(B64_STD[(n ushr 12) and 63])
                .append(B64_STD[(n ushr 6) and 63])
                .append(B64_STD[n and 63])
            i += 3
        }
        val rem = bytes.size - i
        if (rem == 1) {
            val n = (bytes[i].toInt() and 0xFF) shl 16
            sb.append(B64_STD[(n ushr 18) and 63]).append(B64_STD[(n ushr 12) and 63]).append("==")
        } else if (rem == 2) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            sb
                .append(B64_STD[(n ushr 18) and 63])
                .append(B64_STD[(n ushr 12) and 63])
                .append(B64_STD[(n ushr 6) and 63])
                .append('=')
        }
        return sb.toString()
    }

    private fun charValue(
        alphabet: String,
        c: Char,
    ): Int = alphabet.indexOf(c)

    private fun decodeBase64(input: String): TextResult {
        val trimmed = input.trim()
        var padEnd = trimmed.length
        var pads = 0
        while (padEnd > 0 && trimmed[padEnd - 1] == '=') {
            pads++
            padEnd--
        }
        if (pads > 2) return parseError("too much Base64 padding", padEnd)
        val body = trimmed.substring(0, padEnd)
        val hasStd = body.indexOf('+') >= 0 || body.indexOf('/') >= 0
        val hasUrl = body.indexOf('-') >= 0 || body.indexOf('_') >= 0
        if (hasStd && hasUrl) return parseError("mixed Base64 alphabets (+/ and -_)")
        val alphabet = if (hasUrl && !hasStd) B64_URL else B64_STD
        for (i in body.indices) {
            if (charValue(alphabet, body[i]) < 0) {
                return parseError("invalid Base64 character '${body[i]}'", i)
            }
        }
        if (body.length % 4 == 1) return parseError("invalid Base64 length", body.length)
        val canonicalPads =
            when (body.length % 4) {
                2 -> 2
                3 -> 1
                else -> 0
            }
        // Canonical padding is enforced when present; unpadded input (0 pads)
        // is accepted for lengths 2..3 mod 4 (documented lenient tail).
        if (pads != 0 && pads != canonicalPads) {
            return parseError("invalid Base64 padding", padEnd)
        }

        val out = ByteArray((body.length * 6) / 8)
        var bitBuf = 0
        var bits = 0
        var outIdx = 0
        for (i in body.indices) {
            bitBuf = (bitBuf shl 6) or charValue(alphabet, body[i])
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outIdx] = ((bitBuf ushr bits) and 0xFF).toByte()
                outIdx++
            }
        }
        return try {
            // Strict UTF-8: silent replacement chars would corrupt code files.
            val decoder =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            ok(decoder.decode(ByteBuffer.wrap(out)).toString(), input)
        } catch (e: CharacterCodingException) {
            parseError("decoded bytes are not valid UTF-8")
        }
    }

    // ------------------------------------------------------------- URL

    private fun decodeUrl(input: String): TextResult =
        try {
            ok(URLDecoder.decode(input, "UTF-8"), input)
        } catch (e: IllegalArgumentException) {
            // URLDecoder reports illegal escapes without a position; locate
            // the first '%' that violates the %HH shape ourselves.
            var i = input.indexOf('%')
            var bad = -1
            while (i >= 0) {
                if (i + 2 >= input.length || !isHex(input[i + 1]) || !isHex(input[i + 2])) {
                    bad = i
                    break
                }
                i = input.indexOf('%', i + 3)
            }
            parseError("invalid URL escape sequence", if (bad >= 0) bad else null)
        }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    // ------------------------------------------------------------- HTML

    private fun encodeHtmlEntities(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun decodeHtmlEntities(text: String): String {
        if (!text.contains('&')) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = text.indexOf(';', i + 1)
            val entity = if (semi > i && semi - i <= 12) text.substring(i, semi + 1) else null
            val decoded = entity?.let { decodeOneEntity(it) }
            if (entity != null && decoded != null) {
                sb.append(decoded)
                i += entity.length
            } else {
                // Unknown/overlong entity: keep verbatim (fail-safe).
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun decodeOneEntity(entity: String): String? {
        if (entity.length < 3) return null
        return if (entity[1] == '#') {
            val body = entity.substring(2, entity.length - 1)
            val code =
                if (body.isNotEmpty() && (body[0] == 'x' || body[0] == 'X')) {
                    body.substring(1).toIntOrNull(16)
                } else {
                    body.toIntOrNull(10)
                } ?: return null
            if (code < 0 || code > 0x10FFFF) return null
            // Reject surrogates: they cannot stand alone in valid UTF-16.
            if (code in 0xD800..0xDFFF) return null
            String(Character.toChars(code))
        } else {
            NAMED_ENTITIES[entity.substring(1, entity.length - 1)]
        }
    }

    // ------------------------------------------------------------- hash

    private fun hashHex(
        text: String,
        algorithm: String,
    ): String {
        val digest = MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[(v ushr 4) and 0xF]).append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }

    // ------------------------------------------------------------- case

    private enum class CaseStyle { CAMEL, SNAKE, KEBAB }

    private fun joinWords(
        text: String,
        style: CaseStyle,
    ): String {
        val words = splitWords(text)
        if (words.isEmpty()) return text
        val lowered = words.map { it.lowercase() }
        return when (style) {
            CaseStyle.CAMEL ->
                lowered.first() +
                    lowered.drop(1).joinToString("") { w ->
                        if (w.isEmpty()) w else w[0].uppercase() + w.substring(1)
                    }
            CaseStyle.SNAKE -> lowered.joinToString("_")
            CaseStyle.KEBAB -> lowered.joinToString("-")
        }
    }

    private fun splitWords(text: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit()) {
                current.append(c)
            } else if (current.isNotEmpty()) {
                words.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) words.add(current.toString())
        return words
    }

    // ------------------------------------------------------------- lines

    private fun splitKeepBreaks(text: String): Pair<List<String>, String> =
        when {
            text.contains("\r\n") -> text.split(Regex("\r\n|\n|\r")) to "\r\n"
            text.contains('\r') -> text.split(Regex("\r\n|\n|\r")) to "\r"
            else -> text.split(Regex("\r\n|\n|\r")) to "\n"
        }

    private fun sortLines(
        text: String,
        descending: Boolean,
    ): String {
        val (lines, br) = splitKeepBreaks(text)
        val sorted = if (descending) lines.sortedDescending() else lines.sorted()
        return sorted.joinToString(br)
    }

    private fun dedupeLines(text: String): String {
        val (lines, br) = splitKeepBreaks(text)
        val seen = HashSet<String>(lines.size)
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (seen.add(line)) out.add(line)
        }
        return out.joinToString(br)
    }

    private fun reverseLines(text: String): String {
        val (lines, br) = splitKeepBreaks(text)
        return lines.asReversed().joinToString(br)
    }

    // ------------------------------------------------------------- JSON

    private fun escapeJson(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') {
                        sb.append("\\u").append(String.format("%04x", c.code))
                    } else {
                        sb.append(c)
                    }
            }
        }
        return sb.toString()
    }

    private fun unescapeJson(text: String): TextResult {
        if (!text.contains('\\')) return ok(text, text)
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\') {
                sb.append(c)
                i++
                continue
            }
            if (i + 1 >= text.length) {
                return parseError("dangling escape at end of input", i)
            }
            when (val next = text[i + 1]) {
                '"' -> {
                    sb.append('"')
                    i += 2
                }
                '\\' -> {
                    sb.append('\\')
                    i += 2
                }
                '/' -> {
                    sb.append('/')
                    i += 2
                }
                'n' -> {
                    sb.append('\n')
                    i += 2
                }
                'r' -> {
                    sb.append('\r')
                    i += 2
                }
                't' -> {
                    sb.append('\t')
                    i += 2
                }
                'b' -> {
                    sb.append('\b')
                    i += 2
                }
                'f' -> {
                    sb.append('\u000C')
                    i += 2
                }
                'u' -> {
                    if (i + 6 > text.length) {
                        return parseError("truncated \\u escape", i)
                    }
                    val hex = text.substring(i + 2, i + 6)
                    val code =
                        hex.toIntOrNull(16)
                            ?: return parseError("invalid \\u escape '$hex'", i)
                    // Lone surrogates are invalid JSON escapes: reject.
                    if (code in 0xD800..0xDFFF) {
                        return parseError("lone surrogate in \\u escape", i)
                    }
                    sb.append(code.toChar())
                    i += 6
                }
                else -> return parseError("unknown escape '\\$next'", i)
            }
        }
        return ok(sb.toString(), text)
    }

    private val NAMED_ENTITIES: Map<String, String> =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "copy" to "©",
            "reg" to "®",
            "trade" to "™",
            "hellip" to "…",
            "mdash" to "—",
            "ndash" to "–",
            "lsquo" to "‘",
            "rsquo" to "’",
            "ldquo" to "“",
            "rdquo" to "”",
            "bull" to "•",
            "deg" to "°",
            "plusmn" to "±",
            "times" to "×",
            "divide" to "÷",
            "euro" to "€",
            "pound" to "£",
            "yen" to "¥",
            "cent" to "¢",
            "sect" to "§",
            "para" to "¶",
            "middot" to "·",
            "laquo" to "«",
            "raquo" to "»",
            "iexcl" to "¡",
            "iquest" to "¿",
        )
}
