package com.secretarrow.rockedit.core

import org.json.JSONObject

/** Sealed result of an OAuth code/refresh exchange. Callers handle both. */
sealed interface OAuthExchangeResult {
    /** Exchange worked; [accountHint] may be empty when the provider omits it. */
    data class Success(
        val tokens: OAuthTokens,
        val accountHint: String,
    ) : OAuthExchangeResult

    /** Exchange failed; [reason] explains what and why, [httpCode] when known. */
    data class Failure(
        val reason: String,
        val httpCode: Int = -1,
    ) : OAuthExchangeResult
}

/**
 * Exchanges OAuth authorization codes and refresh tokens at the provider
 * token endpoint. Pure JVM (transport injected via [CloudHttp]) and fully
 * branch tested: HTTP errors, invalid JSON, missing fields and invalid input
 * all map to [OAuthExchangeResult.Failure] with an informative message —
 * nothing is ever swallowed silently.
 *
 * All three supported providers use the standard JSON fields
 * (access_token / refresh_token / expires_in), so one parser covers them.
 */
class OAuthTokenExchanger(
    private val http: CloudHttp,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Exchange an authorization [code] for tokens. [clientSecret] is sent
     * only when the provider requires it (Dropbox) or when one was entered.
     */
    fun exchangeCode(
        provider: CloudProvider,
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String,
    ): OAuthExchangeResult {
        val id = clientId.trim()
        if (id.isEmpty()) return OAuthExchangeResult.Failure("client id is empty — fill in your OAuth client id first")
        val c = code.trim()
        if (c.isEmpty()) return OAuthExchangeResult.Failure("authorization code is empty — paste the code from the browser first")
        val form =
            linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to c,
                "redirect_uri" to redirectUri.ifBlank { provider.defaultRedirectUri },
                "client_id" to id,
            )
        if (provider.needsClientSecret || clientSecret.isNotBlank()) {
            form["client_secret"] = clientSecret.trim()
        }
        return tokenRequest(provider, form)
    }

    /** Refresh the access token with [refreshToken]. */
    fun refreshTokens(
        provider: CloudProvider,
        clientId: String,
        clientSecret: String,
        refreshToken: String,
    ): OAuthExchangeResult {
        val id = clientId.trim()
        if (id.isEmpty()) return OAuthExchangeResult.Failure("client id is empty — cannot refresh the token")
        val rt = refreshToken.trim()
        if (rt.isEmpty()) return OAuthExchangeResult.Failure("no refresh token stored — run the OAuth authorization once")
        val form =
            linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to rt,
                "client_id" to id,
            )
        if (provider.needsClientSecret || clientSecret.isNotBlank()) {
            form["client_secret"] = clientSecret.trim()
        }
        return tokenRequest(provider, form)
    }

    private fun tokenRequest(
        provider: CloudProvider,
        form: Map<String, String>,
    ): OAuthExchangeResult {
        val body =
            form.entries.joinToString("&") { (k, v) ->
                "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }
        val response =
            try {
                http.request(
                    "POST",
                    provider.tokenEndpoint,
                    mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Accept" to "application/json",
                        "User-Agent" to USER_AGENT,
                    ),
                    body.toByteArray(Charsets.UTF_8),
                )
            } catch (e: Exception) {
                return OAuthExchangeResult.Failure("network request to the token endpoint failed: ${e.message ?: e.javaClass.simpleName}")
            }
        if (!response.isSuccess) {
            val snippet = response.text().take(MAX_ERROR_SNIPPET).replace('\n', ' ')
            return OAuthExchangeResult.Failure("token endpoint returned HTTP ${response.code}: $snippet", response.code)
        }
        val json =
            try {
                JSONObject(response.text())
            } catch (e: Exception) {
                return OAuthExchangeResult.Failure("token endpoint returned invalid JSON: ${e.message ?: "parse error"}", response.code)
            }
        val access = json.optString("access_token", "")
        if (access.isEmpty()) {
            return OAuthExchangeResult.Failure("token endpoint response has no access_token field", response.code)
        }
        val refresh = json.optString("refresh_token", "")
        val expiresIn = json.optLong("expires_in", 0L)
        val expiresAt = if (expiresIn > 0) clock() + expiresIn * 1000L else 0L
        val hint = json.optString("account_email", "").ifEmpty { json.optString("email", "") }
        return OAuthExchangeResult.Success(OAuthTokens(access, refresh, expiresAt), hint)
    }

    companion object {
        const val USER_AGENT = "RockEdit/0.15"
        const val MAX_ERROR_SNIPPET = 300
    }
}
