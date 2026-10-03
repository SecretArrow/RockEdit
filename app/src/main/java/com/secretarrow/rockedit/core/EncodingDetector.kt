package com.secretarrow.rockedit.core

import org.mozilla.universalchardet.UniversalDetector

/**
 * Character encoding detection backed by juniversalchardet (MPL 1.1),
 * wrapped so the algorithm stays unit testable on the JVM.
 */
object EncodingDetector {

    const val DEFAULT_CHARSET = "UTF-8"

    /**
     * Detects the charset name from raw bytes.
     * Returns [DEFAULT_CHARSET] when detection fails or the bytes are empty.
     */
    fun detectName(bytes: ByteArray): String {
        if (bytes.isEmpty()) return DEFAULT_CHARSET
        val detector = UniversalDetector(null)
        try {
            detector.handleData(bytes, 0, bytes.size)
            detector.dataEnd()
            return detector.detectedCharset ?: DEFAULT_CHARSET
        } finally {
            detector.reset()
        }
    }

    /**
     * Decodes bytes using the named charset, falling back to UTF-8 with
     * replacement characters so the editor never crashes on odd files.
     */
    fun decode(bytes: ByteArray, charsetName: String): String {
        return try {
            val cs = charset(charsetName)
            String(bytes, cs)
        } catch (_: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }

    /** Encodes text with the named charset, falling back to UTF-8. */
    fun encode(text: String, charsetName: String): ByteArray {
        return try {
            text.toByteArray(charset(charsetName))
        } catch (_: Exception) {
            text.toByteArray(Charsets.UTF_8)
        }
    }
}
