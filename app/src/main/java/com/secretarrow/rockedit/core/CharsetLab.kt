package com.secretarrow.rockedit.core

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException
import kotlin.math.ceil

/**
 * BOM verdict for one byte sample (see [CharsetLab.bomInfo]).
 *
 * @property hasBom true when the sample starts with a known BOM.
 * @property bomName one of "UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE",
 *   "UTF-32BE", or null when there is no BOM (then [hasBom] is false).
 */
data class BomInfo(
    val hasBom: Boolean,
    val bomName: String?,
)

/**
 * Detection report for one byte sample (see [CharsetLab.detectionReport]).
 *
 * @property byteCount sample length in bytes.
 * @property bom BOM verdict (see [BomInfo]).
 * @property detectedName juniversalchardet verdict via
 *   [EncodingDetector.detectName]; null only for empty bytes (the detector is
 *   not even consulted then). detectName itself never returns null for
 *   non-empty bytes: when the library cannot identify the sample it falls
 *   back to [EncodingDetector.DEFAULT_CHARSET] ("UTF-8") - that fallback is
 *   passed through verbatim, never rewritten. Pure ASCII is reported by the
 *   library as "US-ASCII" (verified against juniversalchardet 2.5.0).
 * @property effectiveName what the UI shows as the tab charset: the BOM name
 *   when one is present (BOM wins), else [detectedName].
 */
data class DetectionReport(
    val byteCount: Int,
    val bom: BomInfo,
    val detectedName: String?,
    val effectiveName: String?,
)

/**
 * One row of the encode table (see [CharsetLab.encodeReports]).
 *
 * @property charsetName requested charset name, echoed verbatim.
 * @property supported false when the JVM does not know the name (unknown,
 *   illegal or empty); the remaining fields then carry their documented
 *   sentinels and nothing throws.
 * @property byteCount total encoded bytes, -1 when unsupported. Unmappable
 *   characters are skipped by the encoder loop, so they contribute no bytes
 *   (ISO-8859-1 "a-then-euro" still reports the 1 byte for "a"). Java's
 *   "UTF-16" alias additionally prepends a 2-byte BOM for non-empty text;
 *   empty text gets no BOM, and UTF-16LE/UTF-16BE never get one.
 * @property unmappableCount characters that cannot map, -1 when unsupported.
 *   Counted one per unmappable sequence: a surrogate pair reported as
 *   UNMAPPABLE[2] is a single character and counts once, so ISO-8859-1
 *   "U+20AC U+1F600" yields 2 (the two code points), not 3 UTF-16 units.
 *   Lone surrogates (malformed during encode from a String) share this
 *   bucket so the report stays informative about every kind of loss.
 * @property firstBytesHex lowercase hex of the first [CharsetLab.HEX_BYTES]
 *   encoded bytes, "" when unsupported or nothing was produced.
 * @property roundTripOk encode-then-decode equals the original text and no
 *   unmappable was counted; false when unsupported or lossy.
 */
data class EncodeReport(
    val charsetName: String,
    val supported: Boolean,
    val byteCount: Int,
    val unmappableCount: Int,
    val firstBytesHex: String,
    val roundTripOk: Boolean,
)

/**
 * Decode report for one byte sample and charset (see [CharsetLab.decodeReport]).
 *
 * @property charsetName requested charset name, echoed verbatim.
 * @property supported false when the JVM does not know the name.
 * @property success true when the REPORT pass found no malformed/unmappable
 *   sequence and the name is supported; empty input is a trivial success.
 * @property replacementCount number of bad sequences replaced with U+FFFD,
 *   -1 when unsupported or when the decode succeeded (the API contract marks
 *   "nothing was replaced" as -1; the empty-input special case reports 0
 *   instead, as required by the spec). Counted one per bad sequence, not one
 *   per byte: CoderResult.length() is the sequence length in input units and
 *   is deliberately ignored, because REPLACE mode also emits a single U+FFFD
 *   per maximal bad sequence (verified: UTF-8 "C3 C3 C3" yields 3 sequences
 *   and exactly 3 U+FFFD).
 * @property preview first [CharsetLab.PREVIEW_CHARS] UTF-16 code units of the
 *   REPLACE-mode decode result, "" when the decode could not even start
 *   (unsupported charset). May end in a lone high surrogate when an
 *   astral-plane character straddles the cut (accepted v1 assumption).
 */
data class DecodeReport(
    val charsetName: String,
    val supported: Boolean,
    val success: Boolean,
    val replacementCount: Int,
    val preview: String,
)

/**
 * Charset lab core (v0.16.0): BOM inspection, per-charset encode/decode
 * diagnostics, and passthrough charset detection for one byte sample. Pure
 * JVM (java.nio.charset only) so the whole engine is unit-testable without
 * Android; BOM-less detection is delegated to [EncodingDetector]
 * (juniversalchardet), which is reused as-is and never modified here.
 *
 * Defensive rules (scenario -> handling; every path returns a specific
 * report and nothing throws for bad names or bytes):
 *
 * | Scenario               | Handling                          | Test                            |
 * |------------------------|-----------------------------------|---------------------------------|
 * | Empty byte array       | No BOM; null names; decode        | bomEmptyHasNoBom,               |
 * |                        | success=true, count 0, empty      | decodeEmptyBytesSucceeds,       |
 * |                        | preview.                          | detectionEmptyReportsNulls      |
 * | FF FE 00 00 prefix     | UTF-32LE wins: checked before     | bomUtf32LeBeatsUtf16Le,         |
 * |                        | UTF-16LE (longer match first).    | detectionUtf32LeWins            |
 * | Short FF FE (+00)      | UTF-16LE: an incomplete UTF-32    | bomShortFfFeIsUtf16Le           |
 * |                        | prefix is not a UTF-32 BOM.       |                                 |
 * | Unknown/illegal name   | supported=false with -1           | encodeUnknownNameUnsupported,   |
 * |                        | sentinels; never throws.          | encodeIllegalNamesNeverThrow,   |
 * |                        |                                   | decodeUnknownCharset            |
 * | Unmappable (encode)    | One count per bad sequence (a     | encodeIso88591EuroEmoji         |
 * |                        | pair counts once); skip and       |                                 |
 * |                        | continue; roundTripOk=false.      |                                 |
 * | Lone surrogate encode  | Malformed shares the unmappable   | encodeLoneSurrogateIsLossy      |
 * |                        | bucket; roundTripOk=false.        |                                 |
 * | Malformed (decode)     | One count per bad sequence via    | decodeTruncatedUtf8ThreeBytes,  |
 * |                        | the REPORT pass; REPLACE pass     | decodeThreeBadUtf8Sequences     |
 * |                        | builds the preview.               |                                 |
 * | Clean non-empty decode | success=true, count=-1 (the       | decodeValidUtf8Succeeds         |
 * |                        | nothing-replaced sentinel).       |                                 |
 * | Empty text (encode)    | byteCount 0, unmappable 0,        | encodeEmptyTextZeroBytes        |
 * |                        | roundTripOk (no UTF-16 BOM).      |                                 |
 * | UTF-16 alias (encode)  | BOM FE FF prepended for           | encodeUtf16AliasAddsBom         |
 * |                        | non-empty text; counted.          |                                 |
 * | hex maxBytes <= 0      | "" (never null, never throws).    | hexNonPositiveMaxIsEmpty        |
 * | Preview > 200 units    | Cut at 200 UTF-16 units; may      | decodePreviewSplitsSurrogate    |
 * |                        | split a surrogate pair (v1).      |                                 |
 * | Duplicate names        | Duplicate reports, input order    | encodeDuplicateNames,           |
 * |                        | preserved.                        | encodeReportOrderFollowsInput   |
 * | Detection passthrough  | juniversalchardet verdict         | detectionPlainAsciiPassthrough, |
 * |                        | passed through verbatim (ASCII    | detectionCyrillicPassthrough    |
 * |                        | -> US-ASCII; no invented          |                                 |
 * |                        | defaults).                        |                                 |
 *
 * Documented v1 assumption (no test forces it): the caller passes a bounded
 * sample (e.g. the first 64 KiB of a file). Neither detection nor decode
 * applies its own size cap, mirroring EncodingDetector.
 */
object CharsetLab {
    /** Hard preview cap in UTF-16 code units (may split a surrogate pair). */
    const val PREVIEW_CHARS = 200

    /** Bytes shown by [hex] in [EncodeReport.firstBytesHex] by default. */
    const val HEX_BYTES = 32

    private val UTF8_BOM = intArrayOf(0xEF, 0xBB, 0xBF)
    private val UTF32LE_BOM = intArrayOf(0xFF, 0xFE, 0x00, 0x00)
    private val UTF32BE_BOM = intArrayOf(0x00, 0x00, 0xFE, 0xFF)
    private val UTF16LE_BOM = intArrayOf(0xFF, 0xFE)
    private val UTF16BE_BOM = intArrayOf(0xFE, 0xFF)

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Detects a byte order mark. Empty or too-short arrays yield no BOM.
     * Overlapping-prefix rule: FF FE 00 00 is both a full UTF-32LE BOM and
     * the start of a UTF-16LE BOM; UTF-32LE is checked first and wins, so a
     * UTF-16LE file whose first character is U+0000 is (indistinguishably)
     * reported as UTF-32LE - the same convention every editor makes.
     */
    fun bomInfo(bytes: ByteArray): BomInfo {
        val name = bomName(bytes)
        return BomInfo(hasBom = name != null, bomName = name)
    }

    /**
     * Builds the full detection report for [bytes]. Empty input short-
     * circuits: byteCount 0, no BOM, both names null (the detector is not
     * consulted). Otherwise the BOM is checked first; the juniversalchardet
     * verdict is always computed as informational output, and the BOM name
     * wins for [DetectionReport.effectiveName] when one is present.
     */
    fun detectionReport(bytes: ByteArray): DetectionReport {
        if (bytes.isEmpty()) {
            return DetectionReport(
                byteCount = 0,
                bom = BomInfo(hasBom = false, bomName = null),
                detectedName = null,
                effectiveName = null,
            )
        }
        val bom = bomInfo(bytes)
        val detected = EncodingDetector.detectName(bytes)
        val effective = bom.bomName ?: detected
        return DetectionReport(
            byteCount = bytes.size,
            bom = bom,
            detectedName = detected,
            effectiveName = effective,
        )
    }

    /**
     * Encodes [text] with every requested charset, in input order (duplicate
     * names produce duplicate reports). Unknown or illegal names yield a
     * supported=false report instead of an exception.
     */
    fun encodeReports(
        text: String,
        charsetNames: List<String>,
    ): List<EncodeReport> = charsetNames.map { name -> encodeReport(text, name) }

    /**
     * Decodes [bytes] with [charsetName] twice: a REPORT pass that counts bad
     * sequences (malformed or unmappable, one count each), then a REPLACE
     * pass that builds the preview. Unknown names yield a supported=false
     * report; empty input is a trivial success with an empty preview.
     */
    fun decodeReport(
        bytes: ByteArray,
        charsetName: String,
    ): DecodeReport {
        val charset =
            tryCharset(charsetName)
                ?: return DecodeReport(
                    charsetName = charsetName,
                    supported = false,
                    success = false,
                    replacementCount = -1,
                    preview = "",
                )
        if (bytes.isEmpty()) {
            // Documented special case: an empty sample decodes trivially, and
            // the spec demands count 0 here (not the -1 success sentinel).
            return DecodeReport(
                charsetName = charsetName,
                supported = true,
                success = true,
                replacementCount = 0,
                preview = "",
            )
        }
        val badSequences = countBadSequences(bytes, charset)
        val success = badSequences == 0
        return DecodeReport(
            charsetName = charsetName,
            supported = true,
            success = success,
            // Contract quirk kept literal: -1 means "nothing was replaced"
            // for a clean non-empty decode; failures carry the real count.
            replacementCount = if (success) -1 else badSequences,
            preview = truncatePreview(decodeReplacing(bytes, charset)),
        )
    }

    /**
     * Lowercase, space-separated hex pairs ("ff fe") for at most [maxBytes]
     * leading bytes. Empty input or a non-positive [maxBytes] yields "".
     */
    fun hex(
        bytes: ByteArray,
        maxBytes: Int = HEX_BYTES,
    ): String {
        if (maxBytes <= 0 || bytes.isEmpty()) {
            return ""
        }
        val limit = minOf(bytes.size, maxBytes)
        val sb = StringBuilder(limit * 3 - 1)
        for (i in 0 until limit) {
            if (i > 0) {
                sb.append(' ')
            }
            val value = bytes[i].toInt() and 0xFF
            sb.append(HEX_DIGITS[value ushr 4])
            sb.append(HEX_DIGITS[value and 0x0F])
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ internals

    /** Raw encoder output plus the lossy count collected by the REPORT loop. */
    private class Encoded(
        val bytes: ByteArray,
        val unmappableCount: Int,
    )

    private fun bomName(bytes: ByteArray): String? =
        when {
            startsWith(bytes, UTF8_BOM) -> "UTF-8"
            // FF FE 00 00 overlaps the UTF-16LE BOM: UTF-32LE wins (rule 2).
            startsWith(bytes, UTF32LE_BOM) -> "UTF-32LE"
            startsWith(bytes, UTF32BE_BOM) -> "UTF-32BE"
            startsWith(bytes, UTF16LE_BOM) -> "UTF-16LE"
            startsWith(bytes, UTF16BE_BOM) -> "UTF-16BE"
            else -> null
        }

    /** True when [bytes] starts with exactly [prefix] (byte values 0..255). */
    private fun startsWith(
        bytes: ByteArray,
        prefix: IntArray,
    ): Boolean {
        if (bytes.size < prefix.size) {
            return false
        }
        for (i in prefix.indices) {
            if ((bytes[i].toInt() and 0xFF) != prefix[i]) {
                return false
            }
        }
        return true
    }

    /** Resolves [charsetName] or returns null for illegal/unknown names. */
    private fun tryCharset(charsetName: String): Charset? =
        try {
            Charset.forName(charsetName)
        } catch (e: IllegalCharsetNameException) {
            null
        } catch (e: UnsupportedCharsetException) {
            null
        }

    private fun encodeReport(
        text: String,
        charsetName: String,
    ): EncodeReport {
        val charset =
            tryCharset(charsetName)
                ?: return EncodeReport(
                    charsetName = charsetName,
                    supported = false,
                    byteCount = -1,
                    unmappableCount = -1,
                    firstBytesHex = "",
                    roundTripOk = false,
                )
        val encoded = encodeFully(text, charset)
        val decoded = decodeReplacing(encoded.bytes, charset)
        return EncodeReport(
            charsetName = charsetName,
            supported = true,
            byteCount = encoded.bytes.size,
            unmappableCount = encoded.unmappableCount,
            firstBytesHex = hex(encoded.bytes, HEX_BYTES),
            roundTripOk = encoded.unmappableCount == 0 && decoded == text,
        )
    }

    /**
     * REPORT-mode encode loop. Every error result stops the encoder without
     * consuming the bad input, so the loop skips it manually and continues;
     * both UNMAPPABLE and (lone-surrogate) MALFORMED results land in the
     * single unmappable bucket, one count each. Overflow grows the buffer.
     */
    private fun encodeFully(
        text: String,
        charset: Charset,
    ): Encoded {
        val encoder =
            charset
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = CharBuffer.wrap(text)
        var out = ByteBuffer.allocate(outputEstimate(text.length, charset))
        var unmappable = 0
        while (true) {
            val result = encoder.encode(input, out, true)
            when {
                result.isUnderflow -> break
                result.isOverflow -> out = grow(out)
                result.isUnmappable -> {
                    // One count per unmappable sequence: a reported surrogate
                    // pair (UNMAPPABLE[2]) is one character that cannot map.
                    unmappable += 1
                    skipChars(input, result.length())
                }
                // Malformed from a String can only be a lone surrogate; the
                // report exposes one lossy bucket, so it counts here too.
                result.isMalformed -> {
                    unmappable += 1
                    skipChars(input, result.length())
                }
                // Unreachable: CoderResult has no fifth state. Kept so the
                // when is exhaustive and can never fall through silently.
                else -> break
            }
        }
        while (true) {
            val flushed = encoder.flush(out)
            when {
                flushed.isUnderflow -> break
                flushed.isOverflow -> out = grow(out)
                // flush() errors are not produced by any real charset encoder
                // after a clean endOfInput; breaking keeps the loop total.
                else -> break
            }
        }
        out.flip()
        val bytes = ByteArray(out.remaining())
        out.get(bytes)
        return Encoded(bytes, unmappable)
    }

    /**
     * REPORT-mode decode pass that counts bad sequences (one per malformed
     * or unmappable result, matching how REPLACE mode emits one U+FFFD per
     * maximal bad sequence). Decoded output is discarded via a scratch
     * buffer; only the error count survives.
     */
    private fun countBadSequences(
        bytes: ByteArray,
        charset: Charset,
    ): Int {
        val decoder =
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes)
        val scratch = CharBuffer.allocate(1024)
        var bad = 0
        while (true) {
            val result = decoder.decode(input, scratch, true)
            when {
                result.isUnderflow -> break
                result.isOverflow -> scratch.clear()
                result.isMalformed || result.isUnmappable -> {
                    // One count per bad sequence (rule 7): result.length()
                    // is the sequence length in input units and is
                    // intentionally ignored here.
                    bad += 1
                    skipBytes(input, result.length())
                }
                // Unreachable: CoderResult has no fifth state.
                else -> break
            }
        }
        while (true) {
            val flushed = decoder.flush(scratch)
            when {
                flushed.isUnderflow -> break
                flushed.isOverflow -> scratch.clear()
                // Defensive: flush never errors for real charsets.
                else -> break
            }
        }
        return bad
    }

    /** REPLACE-mode decode; [String] guarantees U+FFFD substitution. */
    private fun decodeReplacing(
        bytes: ByteArray,
        charset: Charset,
    ): String = String(bytes, charset)

    /** Preview cap: never more than [PREVIEW_CHARS] UTF-16 code units. */
    private fun truncatePreview(decoded: String): String =
        if (decoded.length <= PREVIEW_CHARS) decoded else decoded.substring(0, PREVIEW_CHARS)

    /** Advances [buffer] past a reported bad sequence of [length] chars. */
    private fun skipChars(
        buffer: CharBuffer,
        length: Int,
    ) {
        buffer.position(buffer.position() + maxOf(1, length))
    }

    /** Advances [buffer] past a reported bad sequence of [length] bytes. */
    private fun skipBytes(
        buffer: ByteBuffer,
        length: Int,
    ) {
        buffer.position(buffer.position() + maxOf(1, length))
    }

    /** Grows the output buffer, preserving the bytes already encoded. */
    private fun grow(out: ByteBuffer): ByteBuffer {
        val bigger = ByteBuffer.allocate(out.capacity() * 2)
        out.flip()
        bigger.put(out)
        bigger.flip()
        return bigger
    }

    /**
     * Initial output size: worst-case bytes per char plus slack for the
     * UTF-16 BOM. Correctness never depends on it - the encode loop grows
     * the buffer on every OVERFLOW result.
     */
    private fun outputEstimate(
        charCount: Int,
        charset: Charset,
    ): Int {
        val perChar = ceil(charset.maxBytesPerChar.toDouble()).toInt().coerceAtLeast(1)
        return charCount * perChar + 16
    }
}
