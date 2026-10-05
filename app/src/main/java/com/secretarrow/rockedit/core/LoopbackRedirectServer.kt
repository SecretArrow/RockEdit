package com.secretarrow.rockedit.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Parsed OAuth redirect query (v0.16.0 loopback flow). All fields nullable:
 * a well-formed callback carries exactly one of [code] or [error] (RFC 6749
 * §4.1.2) plus whatever the provider echoed back in [state]. The loopback
 * server normalizes empty values to null on every field — an empty code is
 * indistinguishable from a missing one for the caller.
 */
data class OAuthCallback(
    val code: String?,
    val error: String?,
    val errorDescription: String?,
    val state: String?,
)

/**
 * State generation and comparison for the OAuth redirect round-trip. The
 * state value is the only defense against a crafted redirect (a malicious
 * app or page firing `http://127.0.0.1/...?code=...` at the loopback
 * server), so [matches] is constant-time and null-safe.
 */
object OAuthState {
    /** Characters in a generated state value (16 random bytes, hex encoded). */
    const val HEX_LENGTH = 32

    private val RANDOM = SecureRandom()
    private const val HEX = "0123456789abcdef"

    /** Generates a fresh state: [HEX_LENGTH] lowercase hex characters. */
    fun generate(): String {
        val bytes = ByteArray(HEX_LENGTH / 2)
        RANDOM.nextBytes(bytes)
        val out = StringBuilder(HEX_LENGTH)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0xF])
        }
        return out.toString()
    }

    /**
     * Constant-time, null-safe equality: true only when both values are
     * non-null and identical in length and content. The loop walks the
     * longest input and folds every character difference into one accumulator
     * (no early exit), so timing does not leak how much matched.
     */
    fun matches(
        expected: String?,
        received: String?,
    ): Boolean {
        if (expected == null || received == null) return false
        var diff = expected.length xor received.length
        val longest = maxOf(expected.length, received.length)
        for (i in 0 until longest) {
            val a = if (i < expected.length) expected[i].code else 0
            val b = if (i < received.length) received[i].code else 0
            diff = diff or (a xor b)
        }
        return diff == 0
    }
}

/**
 * Minimal HTTP server for the OAuth2 loopback redirect flow (RFC 8252 §7.1,
 * v0.16.0 alternative to the v0.15.0 paste-code flow). Pure JVM: binds
 * 127.0.0.1 ONLY (never 0.0.0.0 — no LAN exposure), answers every browser
 * request with a static page and records at most ONE callback.
 *
 * Security notes:
 *  - Validating [OAuthCallback.state] against the expected value is the
 *    CALLER's job ([OAuthState.matches]); the server is a dumb transport.
 *  - Plain HTTP on the loopback interface is the installed-app pattern
 *    documented in RFC 8252 §7.1 (localhost is not treated as a confidentiality
 *    breach for this flow); v1 keeps that assumption.
 *  - Port choice: Google Drive accepts a variable loopback port for installed
 *    clients; Dropbox and OneDrive require a pre-registered exact redirect
 *    URI, so callers should pass [PREFERRED_PORT] and register
 *    `http://127.0.0.1:8642/` (OneDrive may register `http://localhost:8642/`).
 *
 * Defensive contract (scenario → handling → test):
 *
 * | Scenario | Handling | Test |
 * |---|---|---|
 * | start() on a free port | binds 127.0.0.1:port (0 = ephemeral), returns port | port test |
 * | start() called twice | IllegalStateException; first binding untouched | starting-twice test |
 * | Bind fails (port taken, ...) | IOException propagates; UI shows the message | caller side |
 * | GET with a query | record parsed callback ONCE + 200 success page | capture tests |
 * | Non-GET method | 405 response; NOTHING recorded | 405 test |
 * | Malformed line / no query | 400 response; NOTHING recorded | 400 tests |
 * | Head over 8 KB | stop at the cap; 400; NOTHING recorded | oversized test |
 * | Request after first callback | 200 "used" page; callback never overwritten | consumed test |
 * | Connection breaks mid-read | abandoned silently (commented catch) | review only |
 * | close() (twice, any thread) | idempotent; loop ends ~500 ms; awaiters wake null | close test |
 * | awaitCallback, no request | blocks up to timeoutMs, then null | timeout test |
 *
 * Parse rules live in [parseCallback]: UTF-8 with '+' as space, first
 * occurrence wins, unknown params ignored, empty value → null.
 *
 * Threading: one daemon accept thread (never leaks — exit within
 * ~[SOCKET_TIMEOUT_MS] of [close], and callers MUST close in a finally /
 * onDestroy guard). Connections are handled sequentially on that thread; a
 * read stall is bounded by the per-socket [SOCKET_TIMEOUT_MS] timeout.
 */
class LoopbackRedirectServer(
    private val port: Int = 0,
) {
    companion object {
        /**
         * Suggested pre-registered port so users can register the exact
         * redirect URI `http://127.0.0.1:8642/` at providers that forbid
         * variable loopback ports (Dropbox, OneDrive).
         */
        const val PREFERRED_PORT = 8642
        const val DEFAULT_TIMEOUT_MS = 300_000L

        /** Accept-loop poll granularity and per-connection read timeout. */
        const val SOCKET_TIMEOUT_MS = 500
        private const val BIND_HOST = "127.0.0.1"
        private const val MAX_HEADER_BYTES = 8_192
        private const val DRAIN_MAX_BYTES = 65_536

        private const val STATUS_OK = "HTTP/1.1 200 OK"
        private const val STATUS_BAD_REQUEST = "HTTP/1.1 400 Bad Request"
        private const val STATUS_METHOD_NOT_ALLOWED = "HTTP/1.1 405 Method Not Allowed"

        // Static escaped pages; user data (the code!) is never reflected.
        private const val PAGE_SUCCESS =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Rock Edit</title></head>" +
                "<body><h1>Authorization received</h1>" +
                "<p>You can close this tab and return to Rock Edit.</p></body></html>"
        private const val PAGE_CONSUMED =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Rock Edit</title></head>" +
                "<body><h1>Link already used</h1>" +
                "<p>This authorization link was already used. " +
                "Return to Rock Edit.</p></body></html>"
        private const val PAGE_BAD_REQUEST =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Rock Edit</title></head>" +
                "<body><h1>Bad request</h1>" +
                "<p>Rock Edit expected an OAuth redirect here.</p></body></html>"
        private const val PAGE_METHOD_NOT_ALLOWED =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Rock Edit</title></head>" +
                "<body><h1>Method not allowed</h1>" +
                "<p>Rock Edit only accepts GET redirects.</p></body></html>"
    }

    private val lifecycleLock = Any()
    private val callbackLock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int? = null

    @Volatile
    private var closed = false

    @Volatile
    private var callback: OAuthCallback? = null

    @Volatile
    private var latch = CountDownLatch(1)

    /**
     * Binds 127.0.0.1:[port] (port 0 = ephemeral) and starts the accept loop.
     * Returns the actual bound port. IllegalStateException when already
     * started; bind problems propagate as IOException for the caller to show.
     */
    fun start(): Int {
        synchronized(lifecycleLock) {
            if (boundPort != null) {
                throw IllegalStateException("loopback redirect server is already started")
            }
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName(BIND_HOST), port))
            server.soTimeout = SOCKET_TIMEOUT_MS
            serverSocket = server
            boundPort = server.localPort
            closed = false
            callback = null
            latch = CountDownLatch(1)
            thread(name = "rockedit-loopback-oauth", isDaemon = true) { acceptLoop(server) }
            return server.localPort
        }
    }

    /** The bound port, or null before [start] and after [close]. */
    fun portOrNull(): Int? =
        synchronized(lifecycleLock) {
            if (closed) null else boundPort
        }

    /** Non-blocking: the latest recorded callback or null. */
    fun pollCallback(): OAuthCallback? = callback

    /**
     * Blocking with timeout; null on timeout (or after [close]). Idempotent
     * when a callback is already available — returns immediately.
     */
    fun awaitCallback(timeoutMs: Long = DEFAULT_TIMEOUT_MS): OAuthCallback? {
        pollCallback()?.let { return it }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return pollCallback()
    }

    /**
     * Idempotent, safe from any thread: stops the accept loop (within
     * ~[SOCKET_TIMEOUT_MS]) and releases blocked [awaitCallback] callers.
     * Does NOT join the daemon thread; a recorded callback stays readable.
     * Callers must close in a finally / onDestroy guard so the thread and
     * socket never outlive the flow.
     */
    fun close() {
        synchronized(lifecycleLock) {
            closed = true
            val server = serverSocket
            serverSocket = null
            boundPort = null
            try {
                server?.close()
            } catch (e: IOException) {
                // Double close races the accept loop closing the same socket:
                // there is nothing left to release.
            }
        }
        latch.countDown()
    }

    // ------------------------------------------------------------ accept loop

    private fun acceptLoop(server: ServerSocket) {
        while (!closed) {
            val socket =
                try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    // Periodic wake so close() is honored within ~500 ms.
                    continue
                } catch (e: IOException) {
                    break // Server socket closed by close() — normal shutdown.
                }
            handle(socket)
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            handleRequest(socket)
        } catch (e: IOException) {
            // Abandoned connection (peer closed or read timed out mid-request):
            // nothing to record and nothing to answer — log-free FOSS build.
        } finally {
            try {
                socket.close()
            } catch (e: IOException) {
                // Closing an already-dead socket is best effort only.
            }
        }
    }

    private fun handleRequest(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val head = readHead(input)
        if (pollCallback() != null) {
            // Anything arriving after the recorded callback (favicon, stray
            // reload) gets the consumed page; the callback is never replaced.
            writeResponse(output, STATUS_OK, PAGE_CONSUMED)
            return
        }
        if (head == null) {
            writeResponse(output, STATUS_BAD_REQUEST, PAGE_BAD_REQUEST)
            drainQuietly(input)
            return
        }
        val requestLine = head.lineSequence().firstOrNull().orEmpty()
        val tokens = requestLine.split(' ')
        if (tokens.size < 3) {
            writeResponse(output, STATUS_BAD_REQUEST, PAGE_BAD_REQUEST)
            return
        }
        if (!tokens[0].equals("GET", ignoreCase = true)) {
            writeResponse(output, STATUS_METHOD_NOT_ALLOWED, PAGE_METHOD_NOT_ALLOWED)
            return
        }
        if (!tokens[2].regionMatches(0, "HTTP/", 0, 5, ignoreCase = true)) {
            writeResponse(output, STATUS_BAD_REQUEST, PAGE_BAD_REQUEST)
            return
        }
        val queryStart = tokens[1].indexOf('?')
        if (queryStart < 0) {
            // Only redirect targets with a query are meaningful here.
            writeResponse(output, STATUS_BAD_REQUEST, PAGE_BAD_REQUEST)
            return
        }
        record(parseCallback(tokens[1].substring(queryStart + 1)))
        writeResponse(output, STATUS_OK, PAGE_SUCCESS)
    }

    /** Reads until the blank line ending the request head, capped at 8 KB. */
    private fun readHead(input: InputStream): String? {
        val head = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return null
            if (head.length >= MAX_HEADER_BYTES) return null
            head.append(byte.toChar()) // ISO-8859-1 style byte→char is lossless.
            val len = head.length
            val crlfCrlf =
                len >= 4 &&
                    head[len - 4] == '\r' &&
                    head[len - 3] == '\n' &&
                    head[len - 2] == '\r' &&
                    head[len - 1] == '\n'
            val lfLf = len >= 2 && head[len - 2] == '\n' && head[len - 1] == '\n'
            if (crlfCrlf || lfLf) return head.toString()
        }
    }

    /**
     * Best-effort drain after refusing an oversized request so the socket can
     * close cleanly (FIN, not RST) and the peer still receives the 400.
     */
    private fun drainQuietly(input: InputStream) {
        try {
            val scratch = ByteArray(1024)
            var drained = 0
            while (drained < DRAIN_MAX_BYTES && input.read(scratch) != -1) {
                drained += scratch.size // Discarded: the request is refused.
            }
        } catch (e: IOException) {
            // Drain is best effort only; the 400 response was already sent.
        }
    }

    // --------------------------------------------------------------- parsing

    /**
     * Parses an x-www-form-urlencoded query into an [OAuthCallback]: UTF-8
     * decoding with '+' as space, FIRST occurrence wins on duplicate names,
     * unknown params ignored, and empty values normalized to null (documented
     * decision: an empty code/error/state is treated as missing). A malformed
     * percent escape keeps the raw value for that field (never throws into
     * the accept loop).
     */
    private fun parseCallback(query: String): OAuthCallback {
        var code: String? = null
        var error: String? = null
        var errorDescription: String? = null
        var state: String? = null
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val equals = pair.indexOf('=')
            val rawName = if (equals < 0) pair else pair.substring(0, equals)
            val rawValue = if (equals < 0) "" else pair.substring(equals + 1)
            when (decodeFormValue(rawName)) {
                "code" -> if (code == null) code = decodeFormValue(rawValue)
                "error" -> if (error == null) error = decodeFormValue(rawValue)
                "error_description" ->
                    if (errorDescription == null) errorDescription = decodeFormValue(rawValue)
                "state" -> if (state == null) state = decodeFormValue(rawValue)
                else -> {} // Unknown params are ignored by design.
            }
        }
        return OAuthCallback(
            code = normalize(code),
            error = normalize(error),
            errorDescription = normalize(errorDescription),
            state = normalize(state),
        )
    }

    /** Empty values are normalized to null (documented decision). */
    private fun normalize(value: String?): String? = if (value.isNullOrEmpty()) null else value

    /**
     * URLDecoder semantics ('+' → space, %XX UTF-8). URLDecoder throws
     * IllegalArgumentException on illegal/incomplete escapes; keep the raw
     * text then, so the callback still records exactly what was sent.
     */
    private fun decodeFormValue(raw: String): String =
        try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (e: IllegalArgumentException) {
            // Malformed percent escape: record the raw value instead of
            // dropping the whole callback.
            raw
        }

    private fun record(newCallback: OAuthCallback) {
        synchronized(callbackLock) {
            if (callback != null) return // First callback wins; never replaced.
            callback = newCallback
        }
        latch.countDown()
    }

    // -------------------------------------------------------------- responses

    private fun writeResponse(
        output: OutputStream,
        statusLine: String,
        body: String,
    ) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers =
            "$statusLine\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-store\r\n" +
                "\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }
}
