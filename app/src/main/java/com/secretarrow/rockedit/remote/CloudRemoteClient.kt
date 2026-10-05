package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.CloudAuth
import com.secretarrow.rockedit.core.CloudHttp
import com.secretarrow.rockedit.core.CloudResponse
import com.secretarrow.rockedit.core.RemoteClient
import com.secretarrow.rockedit.core.RemoteConnection

/**
 * Shared plumbing for OAuth cloud clients (Drive/Dropbox/OneDrive). Provides
 * Bearer-authenticated headers with an actionable failure message when the
 * account is not authorized, plus response/JSON helpers. Pure JVM.
 */
abstract class CloudRemoteClient(
    protected val connection: RemoteConnection,
    protected val http: CloudHttp,
    protected val auth: CloudAuth,
) : RemoteClient {
    protected fun authHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val token = auth.accessToken()
        val headers = LinkedHashMap<String, String>()
        headers["Authorization"] = "Bearer $token"
        headers["User-Agent"] = USER_AGENT
        headers.putAll(extra)
        return headers
    }

    /** Throws [IllegalStateException] with an informative message on non-2xx. */
    protected fun require2xx(
        response: CloudResponse,
        operation: String,
    ) {
        if (!response.isSuccess) {
            val snippet = response.text().take(200).replace('\n', ' ')
            throw IllegalStateException("$operation failed: HTTP ${response.code}: $snippet")
        }
    }

    protected fun jsonBytes(json: Any): ByteArray = json.toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val USER_AGENT = "RockEdit/0.15"
        const val JSON_TYPE = "application/json; charset=utf-8"
        const val OCTET_STREAM = "application/octet-stream"
        const val MAX_PAGES = 100
    }
}
