package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-branch tests for [OAuthTokenExchanger] (v0.15.0). */
class OAuthTokenExchangerTest {
    private val fixedNow = 5_000_000L

    private fun exchanger(fake: FakeCloudHttp) = OAuthTokenExchanger(fake, clock = { fixedNow })

    private fun fakeResponding(code: Int, body: String) = FakeCloudHttp { _, _, _, _ -> CloudResponse(code, emptyMap(), body.toByteArray()) }

    @Test
    fun `exchange code success parses all standard fields`() {
        val fake =
            fakeResponding(
                200,
                """{"access_token":"AT","refresh_token":"RT","expires_in":3600,"account_email":"me@example.com"}""",
            )
        val result = exchanger(fake).exchangeCode(CloudProvider.GOOGLE_DRIVE, "cid", "", "CODE-1", "http://127.0.0.1")
        assertTrue(result is OAuthExchangeResult.Success)
        val success = result as OAuthExchangeResult.Success
        assertEquals("AT", success.tokens.accessToken)
        assertEquals("RT", success.tokens.refreshToken)
        assertEquals(fixedNow + 3600_000L, success.tokens.expiresAtEpochMs)
        assertEquals("me@example.com", success.accountHint)
        // Form fields are all present on the wire.
        val body = fake.calls.single().bodyText()
        assertTrue(body.contains("grant_type=authorization_code"))
        assertTrue(body.contains("code=CODE-1"))
        assertTrue(body.contains("redirect_uri=http%3A%2F%2F127.0.0.1"))
        assertTrue(body.contains("client_id=cid"))
        // Drive needs no secret: it is not sent.
        assertTrue(!body.contains("client_secret"))
    }

    @Test
    fun `dropbox always sends the client secret`() {
        val fake = fakeResponding(200, """{"access_token":"AT"}""")
        val result =
            exchanger(fake).refreshTokens(CloudProvider.DROPBOX, "cid", "SECRET", "RT-1")
        assertTrue(result is OAuthExchangeResult.Success)
        assertTrue(fake.calls.single().bodyText().contains("client_secret=SECRET"))
    }

    @Test
    fun `optional secret is sent when provided even for public clients`() {
        val fake = fakeResponding(200, """{"access_token":"AT"}""")
        val result = exchanger(fake).refreshTokens(CloudProvider.ONEDRIVE, "cid", "OPT", "RT-1")
        assertTrue(result is OAuthExchangeResult.Success)
        assertTrue(fake.calls.single().bodyText().contains("client_secret=OPT"))
    }

    @Test
    fun `empty client id is rejected before any network call`() {
        val fake = fakeResponding(200, """{"access_token":"AT"}""")
        val result = exchanger(fake).exchangeCode(CloudProvider.DROPBOX, "  ", "s", "c", "r")
        assertTrue(result is OAuthExchangeResult.Failure)
        assertTrue((result as OAuthExchangeResult.Failure).reason.contains("client id"))
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `empty authorization code is rejected before any network call`() {
        val fake = fakeResponding(200, """{"access_token":"AT"}""")
        val result = exchanger(fake).exchangeCode(CloudProvider.DROPBOX, "cid", "s", "   ", "r")
        assertTrue(result is OAuthExchangeResult.Failure)
        assertTrue((result as OAuthExchangeResult.Failure).reason.contains("authorization code"))
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `empty refresh token is rejected before any network call`() {
        val fake = fakeResponding(200, """{"access_token":"AT"}""")
        val result = exchanger(fake).refreshTokens(CloudProvider.DROPBOX, "cid", "s", "")
        assertTrue(result is OAuthExchangeResult.Failure)
        assertTrue((result as OAuthExchangeResult.Failure).reason.contains("refresh"))
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `http error surfaces code and body snippet`() {
        val fake = fakeResponding(400, """{"error":"invalid_grant","error_description":"bad code"}""")
        val result = exchanger(fake).exchangeCode(CloudProvider.DROPBOX, "cid", "s", "c", "r")
        assertTrue(result is OAuthExchangeResult.Failure)
        val failure = result as OAuthExchangeResult.Failure
        assertEquals(400, failure.httpCode)
        assertTrue(failure.reason.contains("HTTP 400"))
        assertTrue(failure.reason.contains("invalid_grant"))
    }

    @Test
    fun `network exception maps to a network failure`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> throw java.net.SocketTimeoutException("timed out") }
        val result = exchanger(fake).exchangeCode(CloudProvider.GOOGLE_DRIVE, "cid", "", "c", "r")
        assertTrue(result is OAuthExchangeResult.Failure)
        assertTrue((result as OAuthExchangeResult.Failure).reason.contains("network request"))
    }

    @Test
    fun `invalid json maps to a parse failure`() {
        val fake = fakeResponding(200, "<html>not json</html>")
        val result = exchanger(fake).exchangeCode(CloudProvider.GOOGLE_DRIVE, "cid", "", "c", "r")
        assertTrue(result is OAuthExchangeResult.Failure)
        assertTrue((result as OAuthExchangeResult.Failure).reason.contains("invalid JSON"))
    }

    @Test
    fun `missing access_token maps to an explicit failure`() {
        val fake = fakeResponding(200, """{"token_type":"bearer"}""")
        val result = exchanger(fake).exchangeCode(CloudProvider.GOOGLE_DRIVE, "cid", "", "c", "r")
        assertTrue(result is OAuthExchangeResult.Failure)
        assertTrue((result as OAuthExchangeResult.Failure).reason.contains("access_token"))
    }

    @Test
    fun `missing expires_in yields non-expiring token`() {
        val fake = fakeResponding(200, """{"access_token":"AT"}""")
        val result = exchanger(fake).refreshTokens(CloudProvider.ONEDRIVE, "cid", "", "RT")
        assertTrue(result is OAuthExchangeResult.Success)
        assertEquals(0L, (result as OAuthExchangeResult.Success).tokens.expiresAtEpochMs)
    }

    @Test
    fun `missing refresh_token in refresh response keeps empty refresh for merging`() {
        val fake = fakeResponding(200, """{"access_token":"NEW","expires_in":600}""")
        val result = exchanger(fake).refreshTokens(CloudProvider.GOOGLE_DRIVE, "cid", "", "OLD")
        assertTrue(result is OAuthExchangeResult.Success)
        assertEquals("", (result as OAuthExchangeResult.Success).tokens.refreshToken)
    }
}
