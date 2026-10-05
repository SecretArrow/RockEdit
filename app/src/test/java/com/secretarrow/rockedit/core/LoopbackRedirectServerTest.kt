package com.secretarrow.rockedit.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket

/**
 * Real-socket tests for [LoopbackRedirectServer] (v0.16.0 loopback OAuth):
 * every HTTP scenario runs against a live 127.0.0.1 server with plain
 * java.net.Socket clients, matching the browser traffic the flow expects.
 */
class LoopbackRedirectServerTest {
    private var server: LoopbackRedirectServer? = null

    @After
    fun tearDown() {
        server?.close()
        server = null
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    fun `start on an ephemeral port exposes the bound port`() {
        val loopback = started()
        val port = loopback.portOrNull()
        assertTrue(port != null && port > 0)
    }

    @Test
    fun `portOrNull is null before start`() {
        assertNull(LoopbackRedirectServer().portOrNull())
    }

    @Test
    fun `portOrNull is null after close`() {
        val loopback = started()
        loopback.close()
        assertNull(loopback.portOrNull())
    }

    @Test
    fun `starting twice throws IllegalStateException`() {
        val loopback = started()
        assertThrows(IllegalStateException::class.java) { loopback.start() }
    }

    @Test
    fun `close is idempotent`() {
        val loopback = started()
        loopback.close()
        loopback.close()
    }

    @Test
    fun `close before start is a safe no-op`() {
        val loopback = LoopbackRedirectServer()
        loopback.close()
        assertNull(loopback.portOrNull())
        assertNull(loopback.pollCallback())
    }

    // ------------------------------------------------------------- callbacks

    @Test
    fun `code and state callback is captured`() {
        val loopback = started()
        get(loopback, head("GET /?code=abc&state=xyz HTTP/1.1"))
        assertEquals(OAuthCallback("abc", null, null, "xyz"), loopback.awaitCallback(2000))
    }

    @Test
    fun `success response is 200 text html`() {
        val loopback = started()
        val response = get(loopback, head("GET /?code=abc&state=xyz HTTP/1.1"))
        assertTrue(response.contains(" 200 "))
        assertTrue(response.contains("text/html"))
        assertTrue(response.contains("Authorization received"))
        assertTrue(response.contains("Cache-Control: no-store"))
        assertTrue(response.contains("Connection: close"))
    }

    @Test
    fun `second request gets the consumed page and callback stays`() {
        val loopback = started()
        get(loopback, head("GET /?code=abc&state=xyz HTTP/1.1"))
        val first = loopback.pollCallback()
        val response = get(loopback, head("GET /?code=other&state=xyz HTTP/1.1"))
        assertTrue(response.contains(" 200 "))
        assertTrue(response.contains("already used"))
        assertEquals(first, loopback.pollCallback())
    }

    @Test
    fun `error callback is captured with a null code`() {
        val loopback = started()
        get(loopback, head("GET /?error=access_denied&error_description=Nope&state=s HTTP/1.1"))
        assertEquals(
            OAuthCallback(null, "access_denied", "Nope", "s"),
            loopback.awaitCallback(2000),
        )
    }

    @Test
    fun `pollCallback is null before any request`() {
        val loopback = started()
        assertNull(loopback.pollCallback())
    }

    @Test
    fun `awaitCallback times out with null`() {
        val loopback = started()
        assertNull(loopback.awaitCallback(200))
    }

    @Test
    fun `awaitCallback is idempotent once a callback is recorded`() {
        val loopback = started()
        get(loopback, head("GET /?code=abc&state=xyz HTTP/1.1"))
        val first = loopback.awaitCallback(2000)
        assertEquals(first, loopback.awaitCallback(2000))
    }

    // ---------------------------------------------------------- query rules

    @Test
    fun `percent escapes and plus decode as form values`() {
        val loopback = started()
        get(loopback, head("GET /?code=a%2Fb+c&state=rockedit%20x HTTP/1.1"))
        assertEquals(OAuthCallback("a/b c", null, null, "rockedit x"), loopback.awaitCallback(2000))
    }

    @Test
    fun `empty code normalizes to null`() {
        val loopback = started()
        get(loopback, head("GET /?code=&state=s HTTP/1.1"))
        assertEquals(OAuthCallback(null, null, null, "s"), loopback.awaitCallback(2000))
    }

    @Test
    fun `unknown params are ignored`() {
        val loopback = started()
        get(loopback, head("GET /?foo=bar&code=c&state=s HTTP/1.1"))
        assertEquals(OAuthCallback("c", null, null, "s"), loopback.awaitCallback(2000))
    }

    @Test
    fun `duplicate params keep the first occurrence`() {
        val loopback = started()
        get(loopback, head("GET /?code=first&code=second&state=one&state=two HTTP/1.1"))
        assertEquals(OAuthCallback("first", null, null, "one"), loopback.awaitCallback(2000))
    }

    @Test
    fun `malformed percent escape keeps the raw value`() {
        val loopback = started()
        get(loopback, head("GET /?code=10%0&state=s HTTP/1.1"))
        assertEquals(OAuthCallback("10%0", null, null, "s"), loopback.awaitCallback(2000))
    }

    @Test
    fun `callback without code or error is still recorded`() {
        // Documented decision: a well-formed GET with a query always records;
        // the caller's state check rejects callbacks without usable fields.
        val loopback = started()
        get(loopback, head("GET /?foo=1 HTTP/1.1"))
        assertEquals(OAuthCallback(null, null, null, null), loopback.awaitCallback(2000))
    }

    // -------------------------------------------------------- request lines

    @Test
    fun `post is rejected with 405 and records nothing`() {
        val loopback = started()
        val response = get(loopback, head("POST /?code=abc HTTP/1.1"))
        assertTrue(response.contains(" 405 "))
        assertTrue(response.contains("Method not allowed"))
        assertNull(loopback.pollCallback())
    }

    @Test
    fun `malformed request line is rejected with 400`() {
        val loopback = started()
        val response = get(loopback, head("GARBAGE"))
        assertTrue(response.contains(" 400 "))
        assertNull(loopback.pollCallback())
    }

    @Test
    fun `missing http version is rejected with 400`() {
        val loopback = started()
        val response = get(loopback, head("GET /?code=abc"))
        assertTrue(response.contains(" 400 "))
        assertNull(loopback.pollCallback())
    }

    @Test
    fun `path without a query is rejected with 400`() {
        val loopback = started()
        val response = get(loopback, head("GET / HTTP/1.1"))
        assertTrue(response.contains(" 400 "))
        assertNull(loopback.pollCallback())
    }

    @Test
    fun `oversized request head is rejected with 400`() {
        val loopback = started()
        val pad = "A".repeat(9_000)
        val response = get(loopback, "GET /?code=abc HTTP/1.1\r\nX-Pad: $pad\r\n\r\n")
        assertTrue(response.contains(" 400 "))
        assertNull(loopback.pollCallback())
    }

    @Test
    fun `lowercase get method is accepted`() {
        val loopback = started()
        get(loopback, head("get /?code=abc&state=xyz HTTP/1.1"))
        assertEquals(OAuthCallback("abc", null, null, "xyz"), loopback.awaitCallback(2000))
    }

    // ------------------------------------------------------------ OAuthState

    @Test
    fun `generate produces 32 lowercase hex chars and differs across calls`() {
        val first = OAuthState.generate()
        val second = OAuthState.generate()
        assertEquals(32, first.length)
        assertTrue(first.matches(Regex("^[0-9a-f]{32}$")))
        assertNotEquals(first, second)
        assertEquals(OAuthState.HEX_LENGTH, 32)
    }

    @Test
    fun `matches with a null side is always false`() {
        assertFalse(OAuthState.matches(null, "x"))
        assertFalse(OAuthState.matches("x", null))
        assertFalse(OAuthState.matches(null, null))
    }

    @Test
    fun `matches accepts only identical non-null values`() {
        assertTrue(OAuthState.matches("ab", "ab"))
        assertFalse(OAuthState.matches("ab", "abc"))
        assertFalse(OAuthState.matches("ab", "ba"))
        assertFalse(OAuthState.matches("a b", "a  b"))
        assertTrue(OAuthState.matches("a b", "a b"))
    }

    @Test
    fun `matches is case sensitive`() {
        assertFalse(OAuthState.matches("AB", "ab"))
        assertFalse(OAuthState.matches("ab", "AB"))
        assertTrue(OAuthState.matches("AB", "AB"))
    }

    // --------------------------------------------------------------- helpers

    private fun started(): LoopbackRedirectServer =
        LoopbackRedirectServer().also { created ->
            created.start()
            server = created
        }

    /** A plain request head: [firstLine] plus Host/Connection headers. */
    private fun head(firstLine: String): String = "$firstLine\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"

    /** Sends [raw] verbatim, then reads the full response until EOF. */
    private fun get(
        loopback: LoopbackRedirectServer,
        raw: String,
    ): String {
        val port = loopback.portOrNull() ?: throw AssertionError("server not started")
        val socket = Socket("127.0.0.1", port)
        try {
            socket.soTimeout = 2000
            val output = socket.getOutputStream()
            output.write(raw.toByteArray(Charsets.US_ASCII))
            output.flush()
            socket.shutdownOutput()
            return readAll(socket.getInputStream())
        } finally {
            socket.close()
        }
    }

    private fun readAll(input: java.io.InputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val read = input.read(chunk)
            if (read == -1) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toString("UTF-8")
    }
}
