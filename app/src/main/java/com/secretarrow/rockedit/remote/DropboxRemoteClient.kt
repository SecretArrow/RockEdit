package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.CloudAuth
import com.secretarrow.rockedit.core.CloudHttp
import com.secretarrow.rockedit.core.CloudResponse
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.RemoteFile
import com.secretarrow.rockedit.core.RemotePath
import org.json.JSONObject
import java.io.FileNotFoundException

/**
 * Dropbox API v2 client (path-native). Uses three hosts:
 *  - api.dropboxapi.com  (RPC: list_folder, create_folder_v2, delete_v2)
 *  - content.dropboxapi.com (download / upload with Dropbox-API-Arg header)
 *
 * Defensive behavior: list paging is capped ([MAX_PAGES]); 409 conflict on
 * mkdir is a no-op-safe success; 409 lookup-not-found on delete mirrors the
 * lenient delete of the other transports; every other non-2xx throws an
 * informative IllegalStateException.
 */
class DropboxRemoteClient(
    connection: RemoteConnection,
    http: CloudHttp,
    auth: CloudAuth,
) : CloudRemoteClient(connection, http, auth) {
    override fun list(path: String): List<RemoteFile> {
        val dir = RemotePath.normalize(path)
        val files = ArrayList<RemoteFile>()
        var cursor = ""
        var pages = 0
        var hasMore = true
        while (hasMore && pages < MAX_PAGES) {
            val first = pages == 0
            val url = if (first) "$RPC/list_folder" else "$RPC/list_folder/continue"
            val bodyJson =
                if (first) {
                    JSONObject().put("path", dropboxPath(dir)).put("include_non_files", true)
                } else {
                    JSONObject().put("cursor", cursor)
                }
            val response = http.request("POST", url, authHeaders(mapOf("Content-Type" to JSON_TYPE)), jsonBytes(bodyJson))
            require2xx(response, "Dropbox list of $dir")
            val json = JSONObject(response.text())
            val entries = json.optJSONArray("entries") ?: org.json.JSONArray()
            for (i in 0 until entries.length()) {
                val o = entries.getJSONObject(i)
                val tag = o.optString(".tag", "")
                if (tag == "deleted") continue
                val name = o.optString("name", "")
                if (name.isEmpty()) continue
                val childPath = o.optString("path_display", "").ifEmpty { RemotePath.child(dir, name) }
                files.add(
                    RemoteFile(
                        name = name,
                        path = childPath,
                        isFolder = tag == "folder",
                        size = if (tag == "folder") -1 else o.optLong("size", -1L),
                    ),
                )
            }
            cursor = json.optString("cursor", "")
            hasMore = json.optString("has_more", "false") == "true" && cursor.isNotEmpty()
            pages++
        }
        return files
    }

    override fun read(path: String): ByteArray {
        val arg = JSONObject().put("path", dropboxPath(RemotePath.normalize(path)))
        val response =
            http.request(
                "POST",
                "$CONTENT/download",
                authHeaders(mapOf("Dropbox-API-Arg" to arg.toString())),
                null,
            )
        if (response.code == 409) throw notFound(response, path)
        require2xx(response, "Dropbox read of $path")
        return response.body
    }

    override fun write(
        path: String,
        data: ByteArray,
    ) {
        val arg =
            JSONObject()
                .put("path", dropboxPath(RemotePath.normalize(path)))
                .put("mode", JSONObject().put(".tag", "overwrite"))
                .put("mute", true)
        val response =
            http.request(
                "POST",
                "$CONTENT/upload",
                authHeaders(
                    mapOf(
                        "Dropbox-API-Arg" to arg.toString(),
                        "Content-Type" to OCTET_STREAM,
                    ),
                ),
                data,
            )
        require2xx(response, "Dropbox upload of $path")
    }

    override fun mkdir(path: String) {
        val body = JSONObject().put("path", dropboxPath(RemotePath.normalize(path))).put("autoretry", false)
        val response = http.request("POST", "$RPC/create_folder_v2", authHeaders(mapOf("Content-Type" to JSON_TYPE)), jsonBytes(body))
        if (response.code == 409) return // already exists — no-op-safe
        require2xx(response, "Dropbox mkdir of $path")
    }

    override fun delete(path: String) {
        val body = JSONObject().put("path", dropboxPath(RemotePath.normalize(path)))
        val response = http.request("POST", "$RPC/delete_v2", authHeaders(mapOf("Content-Type" to JSON_TYPE)), jsonBytes(body))
        if (response.code == 409) {
            val text = response.text()
            if (text.contains("lookup_not_found") || text.contains("not_found")) return // already gone
        }
        require2xx(response, "Dropbox delete of $path")
    }

    /** Dropbox root is the empty string; everything else keeps its slashes. */
    private fun dropboxPath(path: String): String = if (path == "/") "" else path.trimEnd('/')

    private fun notFound(
        response: CloudResponse,
        path: String,
    ): FileNotFoundException {
        val detail = response.text().take(200)
        return FileNotFoundException("'$path' does not exist on Dropbox (HTTP 409: $detail)")
    }

    companion object {
        const val RPC = "https://api.dropboxapi.com/2/files"
        const val CONTENT = "https://content.dropboxapi.com/2/files"
    }
    override fun close() {
        // Stateless transport: nothing to release per connection instance.
    }
}
