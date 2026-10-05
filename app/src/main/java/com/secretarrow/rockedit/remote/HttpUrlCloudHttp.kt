package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.CloudHttp
import com.secretarrow.rockedit.core.CloudResponse
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Production [CloudHttp] over [HttpURLConnection] (dependency-free, same
 * stack as the other remote clients).
 *
 * Defensive behavior:
 *  - connect/read timeouts protect the caller thread;
 *  - response bodies are capped ([MAX_BODY_BYTES]) so a misbehaving endpoint
 *    cannot exhaust memory; larger API payloads are not expected for JSON
 *    metadata responses (file downloads go through the same cap with an
 *    informative error);
 *  - every IOException is rethrown as-is so cloud clients can wrap it with
 *    operation context (no silent swallowing).
 */
class HttpUrlCloudHttp(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
) : CloudHttp {
    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): CloudResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val bytes =
                if (stream == null) {
                    ByteArray(0)
                } else {
                    stream.use { readCapped(it, MAX_BODY_BYTES) }
                }
            val headersOut =
                conn.headerFields
                    .filterKeys { it != null }
                    .mapKeys { it.key as String }
                    .mapValues { it.value.joinToString(",") }
            return CloudResponse(code, headersOut, bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(
        stream: java.io.InputStream,
        cap: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        stream.use { s ->
            while (true) {
                val read = s.read(buffer)
                if (read < 0) break
                total += read
                if (total > cap) {
                    throw IllegalStateException("response exceeds the $cap byte safety cap")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_BODY_BYTES = 16 * 1024 * 1024
    }
}
