package com.secretarrow.rockedit.core

import java.net.URLEncoder

/**
 * v0.15.0 OAuth2 cloud providers for the Storage Manager (backlog: Cloud
 * OAuth). No client IDs are shipped with the app — the user registers their
 * own OAuth client per provider (FOSS/privacy requirement: Rock Edit never
 * proxies traffic and never embeds developer credentials).
 *
 * Assumptions documented for the defensive-programming template:
 *  - Google: "installed application" flow, loopback redirect, no secret.
 *  - Dropbox: code flow with `token_access_type=offline`; Dropbox requires
 *    the client secret on both the authorize-exchange and the refresh call.
 *  - OneDrive: public client, `offline_access Files.ReadWrite`, no secret.
 */
enum class CloudProvider(
    val displayName: String,
    val needsClientSecret: Boolean,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val defaultRedirectUri: String,
    val scopes: String,
) {
    GOOGLE_DRIVE(
        "Google Drive",
        false,
        "https://accounts.google.com/o/oauth2/v2/auth",
        "https://oauth2.googleapis.com/token",
        "http://127.0.0.1",
        "https://www.googleapis.com/auth/drive",
    ),
    DROPBOX(
        "Dropbox",
        true,
        "https://www.dropbox.com/oauth2/authorize",
        "https://api.dropboxapi.com/oauth2/token",
        "http://127.0.0.1",
        "files.content.read files.content.write files.metadata.read",
    ),
    ONEDRIVE(
        "OneDrive",
        false,
        "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        "http://localhost",
        "offline_access Files.ReadWrite",
    ),
}

/** Maps a storage connection type onto its OAuth provider, when it is one. */
fun RemoteType.cloudProvider(): CloudProvider? =
    when (this) {
        RemoteType.GOOGLE_DRIVE -> CloudProvider.GOOGLE_DRIVE
        RemoteType.DROPBOX -> CloudProvider.DROPBOX
        RemoteType.ONEDRIVE -> CloudProvider.ONEDRIVE
        else -> null
    }

/** True when the connection type needs an OAuth flow instead of host/port. */
val RemoteType.isCloud: Boolean
    get() = cloudProvider() != null

/** OAuth2 token pair with expiry bookkeeping. Pure value class. */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
) {
    /**
     * Usable when an access token exists and it has not entered the refresh
     * margin (or expiry is unknown, 0). A token without an access token is
     * never usable; the caller decides what to do about that.
     */
    fun isUsable(nowEpochMs: Long): Boolean {
        if (accessToken.isEmpty()) return false
        if (expiresAtEpochMs <= 0) return true
        return nowEpochMs < expiresAtEpochMs - EXPIRY_MARGIN_MS
    }

    companion object {
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}

/** Supplies valid access tokens to cloud transport clients. */
interface CloudAuth {
    /** Returns a valid access token, refreshing it when necessary. */
    fun accessToken(): String
}

/**
 * Builds the provider authorization URL the user opens in a browser. All
 * parameters are URL-encoded; invalid client ids are rejected up-front so
 * the UI can show an actionable message instead of a broken link.
 */
object CloudAuthUrls {
    fun build(
        provider: CloudProvider,
        clientId: String,
        redirectUri: String = provider.defaultRedirectUri,
        state: String = "rockedit",
    ): String {
        val id = clientId.trim()
        if (id.isEmpty()) {
            throw IllegalArgumentException("client id is required to build the authorization URL")
        }
        val params =
            linkedMapOf(
                "response_type" to "code",
                "client_id" to id,
                "redirect_uri" to redirectUri,
                "state" to state,
            )
        when (provider) {
            CloudProvider.GOOGLE_DRIVE -> {
                params["scope"] = provider.scopes
                params["access_type"] = "offline"
                params["prompt"] = "consent"
            }
            CloudProvider.DROPBOX -> {
                params["token_access_type"] = "offline"
                params["scope"] = provider.scopes
            }
            CloudProvider.ONEDRIVE -> {
                params["scope"] = provider.scopes
                params["response_mode"] = "query"
            }
        }
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        return "${provider.authorizationEndpoint}?$query"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
