package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.CloudAuth
import com.secretarrow.rockedit.core.CloudHttp
import com.secretarrow.rockedit.core.RemoteFile
import com.secretarrow.rockedit.core.RemotePath
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.URLEncoder

/**
 * Google Drive v3 client. Drive is id-based, so the client resolves virtual
 * `/` paths to file ids by walking segments (cached per instance) and
 * creates files/folders under the resolved parent.
 *
 * Assumptions documented: a missing parent folder is an error (the browser
 * UI creates folders first); theDrive "root" alias anchors the walk; names
 * containing single quotes are escaped for the `q` grammar.
 */
class GoogleDriveRemoteClient(
    connection: RemoteConnection,
    http: CloudHttp,
    auth: CloudAuth,
) : CloudRemoteClient(connection, http, auth) {
    private val idCache = HashMap<String, String>()

    override fun list(path: String): List<RemoteFile> {
        val dir = RemotePath.normalize(path)
        val parentId = resolveId(dir)
        val files = ArrayList<RemoteFile>()
        var pageToken = ""
        var pages = 0
        do {
            val q = "'${escape(parentId)}' in parents and trashed = false"
            var url =
                "$API/files?q=${enc(q)}&fields=nextPageToken,files(id,name,mimeType,size,modifiedTime)&pageSize=200"
            if (pageToken.isNotEmpty()) url += "&pageToken=${enc(pageToken)}"
            val response = http.request("GET", url, authHeaders(), null)
            require2xx(response, "Google Drive list of $dir")
            val json = JSONObject(response.text())
            val arr = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name", "")
                if (name.isEmpty()) continue
                val isFolder = o.optString("mimeType", "") == FOLDER_MIME
                files.add(
                    RemoteFile(
                        name = name,
                        path = RemotePath.child(dir, name),
                        isFolder = isFolder,
                        size = if (isFolder) -1 else o.optLong("size", -1L),
                        lastModified = parseModified(o.optString("modifiedTime", "")),
                    ),
                )
            }
            pageToken = json.optString("nextPageToken", "")
            pages++
        } while (pageToken.isNotEmpty() && pages < MAX_PAGES)
        return files
    }

    override fun read(path: String): ByteArray {
        val id = resolveId(RemotePath.normalize(path))
        val response = http.request("GET", "$API/files/$id?alt=media", authHeaders(), null)
        require2xx(response, "Google Drive read of $path")
        return response.body
    }

    override fun write(
        path: String,
        data: ByteArray,
    ) {
        val dir = RemotePath.normalize(path)
        val name = RemotePath.name(dir)
        if (name.isEmpty()) throw IllegalStateException("cannot write Google Drive file: path '$dir' has no file name")
        val parentId = resolveId(RemotePath.parent(dir))
        val existingId = lookupChildId(parentId, name)
        if (existingId != null) {
            val response =
                http.request(
                    "PATCH",
                    "$UPLOAD/files/$existingId?uploadType=media",
                    authHeaders(mapOf("Content-Type" to OCTET_STREAM)),
                    data,
                )
            require2xx(response, "Google Drive update of $dir")
        } else {
            val boundary = "rockedit_drive_${System.nanoTime()}"
            val metadata = JSONObject().put("name", name).put("parents", JSONArray().put(parentId))
            val body =
                ByteArrayOutputStream()
                    .apply {
                        write("--$boundary\r\n".toByteArray())
                        write("Content-Type: $JSON_TYPE\r\n\r\n".toByteArray())
                        write(metadata.toString().toByteArray())
                        write("\r\n--$boundary\r\n".toByteArray())
                        write("Content-Type: $OCTET_STREAM\r\n\r\n".toByteArray())
                        write(data)
                        write("\r\n--$boundary--\r\n".toByteArray())
                    }.toByteArray()
            val response =
                http.request(
                    "POST",
                    "$UPLOAD/files?uploadType=multipart&fields=id",
                    authHeaders(
                        mapOf(
                            "Content-Type" to "multipart/related; boundary=$boundary",
                        ),
                    ),
                    body,
                )
            require2xx(response, "Google Drive upload of $dir")
            val newId = JSONObject(response.text()).optString("id", "")
            if (newId.isNotEmpty()) idCache[dir] = newId
        }
    }

    override fun mkdir(path: String) {
        val dir = RemotePath.normalize(path)
        val name = RemotePath.name(dir)
        if (name.isEmpty()) return // root exists by definition
        val parentId = resolveId(RemotePath.parent(dir))
        val existingId = lookupChildId(parentId, name)
        if (existingId != null) return // no-op-safe when it exists
        val metadata =
            JSONObject()
                .put("name", name)
                .put("mimeType", FOLDER_MIME)
                .put("parents", JSONArray().put(parentId))
        val response =
            http.request("POST", "$API/files", authHeaders(mapOf("Content-Type" to JSON_TYPE)), jsonBytes(metadata))
        require2xx(response, "Google Drive mkdir of $dir")
    }

    override fun delete(path: String) {
        val id = resolveId(RemotePath.normalize(path))
        val response = http.request("DELETE", "$API/files/$id", authHeaders(), null)
        // 404 = already gone: mirror the WebDAV client's lenient delete.
        if (response.code == 404) return
        require2xx(response, "Google Drive delete of $path")
        idCache.remove(RemotePath.normalize(path))
    }

    /** Resolves a virtual path to a Drive file id, walking and caching. */
    private fun resolveId(path: String): String {
        if (path == "/" || path.isEmpty()) return ROOT
        idCache[path]?.let { return it }
        var current = ROOT
        var built = ""
        for (segment in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            built = RemotePath.child(built, segment)
            val cached = idCache[built]
            current =
                if (cached != null) {
                    cached
                } else {
                    val id =
                        lookupChildId(current, segment)
                            ?: throw FileNotFoundException("'$built' does not exist on Google Drive — create the folder first")
                    idCache[built] = id
                    id
                }
        }
        return current
    }

    /** Returns the child id or null; throws only on transport/auth errors. */
    private fun lookupChildId(
        parentId: String,
        name: String,
    ): String? {
        val q = "name = '${escape(name)}' and '${escape(parentId)}' in parents and trashed = false"
        val url = "$API/files?q=${enc(q)}&fields=files(id)&pageSize=10"
        val response = http.request("GET", url, authHeaders(), null)
        require2xx(response, "Google Drive lookup of '$name'")
        val arr = JSONObject(response.text()).optJSONArray("files") ?: JSONArray()
        return if (arr.length() > 0) arr.getJSONObject(0).optString("id", "").ifEmpty { null } else null
    }

    private fun parseModified(iso: String): Long =
        try {
            java.time.Instant
                .parse(iso)
                .toEpochMilli()
        } catch (_: Exception) {
            0L
        }

    private fun escape(value: String): String = value.replace("'", "\\'")

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val ROOT = "root"
    }
}
