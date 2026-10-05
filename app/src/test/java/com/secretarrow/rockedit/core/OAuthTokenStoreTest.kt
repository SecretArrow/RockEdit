package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-branch tests for [OAuthTokenStore] (v0.15.0). */
class OAuthTokenStoreTest {
    private fun store(kv: InMemoryKeyValueStore = InMemoryKeyValueStore()) = OAuthTokenStore(kv, PlainEncryptor)

    @Test
    fun `save and read back roundtrip decrypts tokens`() {
        val kv = InMemoryKeyValueStore()
        val s = store(kv)
        s.saveTokens(7L, OAuthTokens("access-1", "refresh-1", 1000L))
        val tokens = s.tokensFor(7L)
        assertNotNull(tokens)
        assertEquals("access-1", tokens!!.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
        assertEquals(1000L, tokens.expiresAtEpochMs)
    }

    @Test
    fun `access and refresh tokens are encrypted at rest`() {
        val kv = InMemoryKeyValueStore()
        val s = store(kv)
        s.saveTokens(1L, OAuthTokens("SECRET-ACCESS", "SECRET-REFRESH", 0L))
        val raw = kv.getString(OAuthTokenStore.KEY, null).orEmpty()
        assertTrue(raw.contains("plain:SECRET-ACCESS"))
        // Encrypted form only: the unencrypted refresh value never appears raw.
        assertFalse(raw.contains("\"refresh\":\"SECRET-REFRESH\""))
        assertEquals(1, raw.split("SECRET-REFRESH").size - 1)
    }

    @Test
    fun `tokensFor returns null when absent`() {
        assertNull(store().tokensFor(42L))
    }

    @Test
    fun `corrupted store fails safe to null`() {
        val kv = InMemoryKeyValueStore()
        kv.putString(OAuthTokenStore.KEY, "not json at all")
        assertNull(store(kv).tokensFor(1L))
    }

    @Test
    fun `corrupted entry fails safe to null`() {
        val kv = InMemoryKeyValueStore()
        kv.putString(OAuthTokenStore.KEY, """{"9": "not an object"}""")
        assertNull(store(kv).tokensFor(9L))
    }

    @Test
    fun `removeTokens deletes the entry and is safe when absent`() {
        val kv = InMemoryKeyValueStore()
        val s = store(kv)
        s.saveTokens(3L, OAuthTokens("a", "r", 0L))
        s.removeTokens(3L)
        assertNull(s.tokensFor(3L))
        s.removeTokens(3L) // absent again: no throw
    }

    @Test
    fun `invalid connection ids are ignored on save`() {
        val s = store()
        s.saveTokens(0L, OAuthTokens("a", "r", 0L))
        s.saveTokens(-5L, OAuthTokens("a", "r", 0L))
        assertNull(s.tokensFor(0L))
        assertNull(s.tokensFor(-5L))
    }

    @Test
    fun `saveTokens overwrites existing entry`() {
        val s = store()
        s.saveTokens(2L, OAuthTokens("old", "r", 0L))
        s.saveTokens(2L, OAuthTokens("new", "r2", 5L))
        assertEquals("new", s.tokensFor(2L)!!.accessToken)
        assertEquals(5L, s.tokensFor(2L)!!.expiresAtEpochMs)
    }

    @Test
    fun `expiry margin makes soon-expiring tokens unusable`() {
        val now = 1_000_000L
        val soon = OAuthTokens("a", "r", now + OAuthTokens.EXPIRY_MARGIN_MS)
        assertFalse(soon.isUsable(now))
        val comfortable = OAuthTokens("a", "r", now + OAuthTokens.EXPIRY_MARGIN_MS * 2)
        assertTrue(comfortable.isUsable(now))
        val noExpiry = OAuthTokens("a", "r", 0L)
        assertTrue(noExpiry.isUsable(now))
        val emptyAccess = OAuthTokens("", "r", 0L)
        assertFalse(emptyAccess.isUsable(now))
    }
}
