package com.secretarrow.rockedit.core

import com.secretarrow.rockedit.remote.FakeCloudHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-branch tests for [StoreBackedCloudAuth] (v0.15.0). */
class StoreBackedCloudAuthTest {
    private val now = 1_000_000L

    private fun connection(
        type: RemoteType = RemoteType.GOOGLE_DRIVE,
        clientId: String = "cid",
        clientSecret: String = "",
    ): RemoteConnection =
        RemoteConnection(
            id = 11L,
            name = "My Cloud",
            type = type,
            host = "",
            port = 0,
            user = "",
            password = "",
            clientId = clientId,
            clientSecret = clientSecret,
        )

    private fun harness(
        connection: RemoteConnection,
        tokens: OAuthTokens?,
        exchanger: OAuthTokenExchanger =
            OAuthTokenExchanger(FakeCloudHttp { _, _, _, _ -> throw IllegalStateException("network not expected") }),
    ): StoreBackedCloudAuth {
        val kv = InMemoryKeyValueStore()
        val store = OAuthTokenStore(kv, PlainEncryptor)
        if (tokens != null) store.saveTokens(connection.id, tokens)
        return StoreBackedCloudAuth(connection, store, exchanger, clock = { now })
    }

    @Test
    fun `usable token is returned without network`() {
        val auth = harness(connection(), OAuthTokens("GOOD", "R", now + 10 * 60_000L))
        assertEquals("GOOD", auth.accessToken())
    }

    @Test
    fun `non cloud connection type is rejected`() {
        val auth = harness(connection(type = RemoteType.FTP), null)
        val error =
            assertThrows(IllegalStateException::class.java) { auth.accessToken() }
        assertTrue(error.message!!.contains("not an OAuth cloud connection"))
    }

    @Test
    fun `missing tokens produce an actionable error`() {
        val auth = harness(connection(), null)
        val error =
            assertThrows(IllegalStateException::class.java) { auth.accessToken() }
        assertTrue(error.message!!.contains("not authorized yet"))
        assertTrue(error.message!!.contains("My Cloud"))
    }

    @Test
    fun `expired token is refreshed and stored`() {
        val kv = InMemoryKeyValueStore()
        val store = OAuthTokenStore(kv, PlainEncryptor)
        store.saveTokens(11L, OAuthTokens("OLD", "R-OLD", now - 1000L))
        val refreshCalls = mutableListOf<String>()
        val exchanger =
            OAuthTokenExchanger(
                FakeCloudHttp { method, url, _, body ->
                    refreshCalls.add("$method $url ${body?.toString(Charsets.UTF_8)}")
                    CloudResponse(
                        200,
                        emptyMap(),
                        """{"access_token":"FRESH","refresh_token":"R-NEW","expires_in":3600}""".toByteArray(),
                    )
                },
                clock = { now },
            )
        val auth = StoreBackedCloudAuth(connection(), store, exchanger, clock = { now })
        assertEquals("FRESH", auth.accessToken())
        assertEquals(1, refreshCalls.size)
        assertTrue(refreshCalls[0].contains("refresh_token=R-OLD"))
        val stored = store.tokensFor(11L)!!
        assertEquals("FRESH", stored.accessToken)
        assertEquals("R-NEW", stored.refreshToken)
    }

    @Test
    fun `refresh without a new refresh token keeps the old one`() {
        val kv = InMemoryKeyValueStore()
        val store = OAuthTokenStore(kv, PlainEncryptor)
        store.saveTokens(11L, OAuthTokens("OLD", "R-KEEP", now - 1000L))
        val exchanger =
            OAuthTokenExchanger(
                FakeCloudHttp { _, _, _, _ ->
                    CloudResponse(200, emptyMap(), """{"access_token":"FRESH"}""".toByteArray())
                },
                clock = { now },
            )
        val auth = StoreBackedCloudAuth(connection(), store, exchanger, clock = { now })
        assertEquals("FRESH", auth.accessToken())
        assertEquals("R-KEEP", store.tokensFor(11L)!!.refreshToken)
    }

    @Test
    fun `failed refresh surfaces the provider reason`() {
        val store = OAuthTokenStore(InMemoryKeyValueStore(), PlainEncryptor)
        store.saveTokens(11L, OAuthTokens("OLD", "R-OLD", now - 1000L))
        val exchanger =
            OAuthTokenExchanger(
                FakeCloudHttp { _, _, _, _ ->
                    CloudResponse(400, emptyMap(), """{"error":"invalid_grant"}""".toByteArray())
                },
                clock = { now },
            )
        val auth = StoreBackedCloudAuth(connection(), store, exchanger, clock = { now })
        val error =
            assertThrows(IllegalStateException::class.java) { auth.accessToken() }
        assertTrue(error.message!!.contains("refreshing the access token"))
        assertTrue(error.message!!.contains("invalid_grant"))
    }

    @Test
    fun `stored token without access value falls through to the refresh branch`() {
        val store = OAuthTokenStore(InMemoryKeyValueStore(), PlainEncryptor)
        store.saveTokens(11L, OAuthTokens("", "R-OLD", 0L))
        val exchanger =
            OAuthTokenExchanger(
                FakeCloudHttp { _, _, _, _ ->
                    CloudResponse(200, emptyMap(), """{"access_token":"FRESH"}""".toByteArray())
                },
                clock = { now },
            )
        val auth = StoreBackedCloudAuth(connection(), store, exchanger, clock = { now })
        assertEquals("FRESH", auth.accessToken())
    }
}
