package com.secretarrow.rockedit.core

/**
 * [CloudAuth] backed by the encrypted [OAuthTokenStore]. On each access:
 *  1. usable stored token -> returned as-is (no network);
 *  2. expired token + refresh token -> provider refresh, store updated,
 *     keeping the OLD refresh token when the provider did not issue a new
 *     one (Google behavior);
 *  3. nothing stored / refresh failed -> IllegalStateException with an
 *     actionable message. Every branch is explicit and tested.
 */
class StoreBackedCloudAuth(
    private val connection: RemoteConnection,
    private val store: OAuthTokenStore,
    private val exchanger: OAuthTokenExchanger,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : CloudAuth {
    override fun accessToken(): String {
        val provider =
            connection.type.cloudProvider()
                ?: throw IllegalStateException(
                    "connection '${connection.name}' is not an OAuth cloud connection",
                )
        val current = store.tokensFor(connection.id)
        if (current != null && current.isUsable(clock())) return current.accessToken

        val refresh = current?.refreshToken.orEmpty()
        if (refresh.isEmpty()) {
            throw IllegalStateException(
                "connection '${connection.name}' is not authorized yet — open the Storage Manager and run the OAuth authorization once",
            )
        }
        val result = exchanger.refreshTokens(provider, connection.clientId, connection.clientSecret, refresh)
        return when (result) {
            is OAuthExchangeResult.Success -> {
                val merged =
                    if (result.tokens.refreshToken.isEmpty()) {
                        result.tokens.copy(refreshToken = refresh)
                    } else {
                        result.tokens
                    }
                store.saveTokens(connection.id, merged)
                merged.accessToken
            }
            is OAuthExchangeResult.Failure -> {
                throw IllegalStateException(
                    "refreshing the access token for '${connection.name}' failed: ${result.reason}",
                )
            }
        }
    }
}
