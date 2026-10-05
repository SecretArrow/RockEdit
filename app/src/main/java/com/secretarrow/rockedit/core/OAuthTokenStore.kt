package com.secretarrow.rockedit.core

import org.json.JSONObject

/**
 * Persists OAuth tokens per connection id in [KeyValueStore]. Access and
 * refresh tokens are encrypted with the injected encryptor (the same
 * Android Keystore AES-GCM encryptor used for connection passwords);
 * corrupted payloads fail safe to `null` instead of throwing.
 */
class OAuthTokenStore(
    private val kv: KeyValueStore,
    private val encryptor: RemoteConnectionStore.RemoteEncryptor,
) {
    /** Returns the stored tokens for [connectionId] or null when absent/corrupt. */
    fun tokensFor(connectionId: Long): OAuthTokens? =
        try {
            val root = JSONObject(kv.getString(KEY, null) ?: return null)
            val entry = root.optJSONObject(connectionId.toString()) ?: return null
            OAuthTokens(
                accessToken = decrypt(entry.optString(F_ACCESS)),
                refreshToken = decrypt(entry.optString(F_REFRESH)),
                expiresAtEpochMs = entry.optLong(F_EXPIRES, 0L),
            )
        } catch (_: Exception) {
            // Corrupted store must not break opening the browser; the user
            // simply re-authorizes the connection.
            null
        }

    /** Adds or replaces tokens for [connectionId]. Invalid ids are ignored. */
    fun saveTokens(
        connectionId: Long,
        tokens: OAuthTokens,
    ) {
        if (connectionId <= 0) return
        val root =
            try {
                JSONObject(kv.getString(KEY, null) ?: "{}")
            } catch (_: Exception) {
                JSONObject()
            }
        val entry =
            JSONObject()
                .put(F_ACCESS, encrypt(tokens.accessToken))
                .put(F_REFRESH, encrypt(tokens.refreshToken))
                .put(F_EXPIRES, tokens.expiresAtEpochMs)
        root.put(connectionId.toString(), entry)
        kv.putString(KEY, root.toString())
    }

    fun removeTokens(connectionId: Long) {
        val root =
            try {
                JSONObject(kv.getString(KEY, null) ?: return)
            } catch (_: Exception) {
                return
            }
        root.remove(connectionId.toString())
        kv.putString(KEY, root.toString())
    }

    private fun encrypt(plain: String): String = if (plain.isEmpty()) "" else encryptor.encrypt(plain)

    private fun decrypt(cipher: String): String =
        if (cipher.isEmpty()) {
            ""
        } else {
            try {
                encryptor.decrypt(cipher)
            } catch (_: Exception) {
                ""
            }
        }

    companion object {
        const val KEY = "oauth_tokens"
        private const val F_ACCESS = "access"
        private const val F_REFRESH = "refresh"
        private const val F_EXPIRES = "expires"
    }
}
