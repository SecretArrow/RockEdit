package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.CloudAuth
import com.secretarrow.rockedit.core.CloudHttp
import com.secretarrow.rockedit.core.CloudResponse
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.RemoteFile
import com.secretarrow.rockedit.core.RemotePath
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Microsoft OneDrive client over Microsoft Graph v1.0 (path-native).
 *
 * Defensive behavior: `/content` downloads may answer 302 with a
 * pre-authenticated Location — the client follows redirects explicitly
 * (max 2 hops, Authorization dropped on the redirect per OAuth rules);
 * 409 nameAlreadyExists on mkdir is no-op-safe; 404 on delete is lenient;
 * paging via @odata.nextLink is capped ([MAX_PAGES]).
 */
class OneDriveRemoteClient(
    connection: RemoteConnection,
    http: CloudHttp,
    auth: CloudAuth,
) : CloudRemoteClient(connection, http, auth) {
    override fun list(path: String): List<RemoteFile> {
        val dir = RemotePath.normalize(path)
        val files = ArrayList<RemoteFile>()
        var url: String? = childrenUrl(dir)
        var pages = 0
        while (url != null && pages < MAX_PAGES) {
            val response = getFollowingRedirects(url, "OneDrive list of $dir")
            require2xx(response, "OneDrive list of $dir")
            val json = JSONObject(response.text())
            val value = json.optJSONArray("value") ?: org.json.JSONArray()
            for (i in 0 until value.length()) {
                val o = value.getJSONObject(i)
                val name = o.optString("name", "")
                if (name.isEmpty()) continue
                val isFolder = o.optJSONObject("folder") != null
                files.add(
                    RemoteFile(
                        name = name,
                        path = RemotePath.child(dir, name),
                        isFolder = isFolder,
                        size = if (isFolder) -1 else o.optLong("size", -1L),
                    ),
                )
            }
            url = json.optString("@odata.nextLink", "").ifEmpty { null }
            pages++
        }
        return files
    }

    override fun read(path: String): ByteArray {
        val response = getFollowingRedirects(contentUrl(path), "OneDrive read of $path")
        require2xx(response, "OneDrive read of $path")
        return response.body
    }

    override fun write(
        path: String,
        data: ByteArray,
    ) {
        val response =
            http.request("PUT", contentUrl(path), authHeaders(mapOf("Content-Type" to OCTET_STREAM)), data)
        require2xx(response, "OneDrive upload of $path")
    }

    override fun mkdir(path: String) {
        val dir = RemotePath.normalize(path)
        val name = RemotePath.name(dir)
        if (name.isEmpty()) return // root exists by definition
        val metadata = JSONObject().put("name", name).put("folder", JSONObject())
        val response =
            http.request(
                "POST",
                childrenUrl(RemotePath.parent(dir)),
                authHeaders(mapOf("Content-Type" to JSON_TYPE)),
                jsonBytes(metadata),
            )
        if (response.code == 409 && response.text().contains("nameAlreadyExists")) return
        require2xx(response, "OneDrive mkdir of $dir")
    }

    override fun delete(path: String) {
        val response = http.request("DELETE", itemUrl(path), authHeaders(), null)
        if (response.code == 404) return // already gone
        require2xx(response, "OneDrive delete of $path")
    }

    /** GET with explicit 30x handling for pre-authenticated download URLs. */
    private fun getFollowingRedirects(
        url: String,
        operation: String,
    ): CloudResponse {
        var current = url
        var hops = 0
        while (hops < MAX_REDIRECTS) {
            // The Authorization header is only sent to the Graph host; the
            // pre-authenticated redirect target must never receive the token.
            val headers =
                if (hops == 0) {
                    authHeaders()
                } else {
                    mapOf("User-Agent" to USER_AGENT)
                }
            val response = http.request("GET", current, headers, null)
            if (response.code in 300..399) {
                val location = response.header("Location")
                if (location.isEmpty()) {
                    throw IllegalStateException("$operation failed: redirect without Location header (HTTP ${response.code})")
                }
                current = location
                hops++
                continue
            }
            return response
        }
        throw IllegalStateException("$operation failed: too many redirects")
    }

    private fun childrenUrl(dir: String): String = if (dir == "/") "$BASE/root/children" else "$BASE/root:/${encodedPath(dir)}:/children"

    private fun contentUrl(path: String): String = "$BASE/root:/${encodedPath(RemotePath.normalize(path))}:/content"

    private fun itemUrl(path: String): String = "$BASE/root:/${encodedPath(RemotePath.normalize(path))}:"

    /** Encodes every segment, keeping `/` separators intact. */
    private fun encodedPath(path: String): String {
        val trimmed = path.trim('/')
        if (trimmed.isEmpty()) throw IllegalStateException("cannot operate on the OneDrive root itself — select a folder or file")
        return trimmed.split('/').filter { it.isNotEmpty() }.joinToString("/") { URLEncoder.encode(it, "UTF-8") }
    }

    companion object {
        const val BASE = "https://graph.microsoft.com/v1.0/me/drive"
        const val MAX_REDIRECTS = 3
    }

    override fun close() {
        // Stateless transport: nothing to release per connection instance.
    }
}
