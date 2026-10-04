package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.RemoteClient
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.RemoteFile
import com.secretarrow.rockedit.core.RemotePath
import com.secretarrow.rockedit.core.WebDavParser
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dependency-free WebDAV client (PROPFIND/GET/PUT/MKCOL/DELETE) over
 * [HttpURLConnection]. Depth-1 PROPFIND lists directories.
 */
class WebDavRemoteClient(
    private val connection: RemoteConnection,
    private val https: Boolean = connection.port == 443,
) : RemoteClient {
    private fun url(path: String): String =
        RemotePath.webDavUrl(connection.host, RemotePath.resolvePort(connection.type, connection.port), path, https)

    private fun open(
        method: String,
        path: String,
    ): HttpURLConnection {
        val conn = URL(url(path)).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        val user = connection.user.ifEmpty { "anonymous" }
        val auth = "$user:${connection.password}"
        val encoded =
            android.util.Base64.encodeToString(
                auth.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
        conn.setRequestProperty("Authorization", "Basic $encoded")
        conn.setRequestProperty("User-Agent", "RockEdit/0.6")
        return conn
    }

    override fun list(path: String): List<RemoteFile> {
        val dir = RemotePath.normalize(path)
        val conn = open("PROPFIND", dir)
        conn.setRequestProperty("Depth", "1")
        try {
            val code = conn.responseCode
            if (code < 200 || code >= 300) {
                throw IllegalStateException("WebDAV PROPFIND failed: HTTP $code")
            }
            val xml = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val self = dir.trimEnd('/')
            return WebDavParser
                .parsePropfind(xml)
                .map { resource ->
                    val resPath = WebDavParser.hrefToPath(resource.href)
                    val name = WebDavParser.nameFromPath(resPath)
                    RemoteFile(
                        name = name,
                        path = resPath,
                        isFolder = resource.isCollection,
                        size = resource.size,
                        lastModified = resource.lastModified,
                    )
                }.filter { it.path != RemotePath.normalize(self) && it.name.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }

    override fun read(path: String): ByteArray {
        val conn = open("GET", path)
        try {
            val code = conn.responseCode
            if (code < 200 || code >= 300) {
                throw IllegalStateException("WebDAV GET failed: HTTP $code")
            }
            return conn.inputStream.use { stream ->
                val out = ByteArrayOutputStream()
                stream.copyTo(out)
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    override fun write(
        path: String,
        data: ByteArray,
    ) {
        val conn = open("PUT", path)
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(data.size)
        try {
            conn.outputStream.use { it.write(data) }
            val code = conn.responseCode
            if (code !in 200..299 && code != 204) {
                throw IllegalStateException("WebDAV PUT failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    override fun mkdir(path: String) {
        val conn = open("MKCOL", path)
        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 405) { // 405 = exists already
                throw IllegalStateException("WebDAV MKCOL failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    override fun delete(path: String) {
        val conn = open("DELETE", path)
        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 404) {
                throw IllegalStateException("WebDAV DELETE failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    override fun close() {
        // Stateless.
    }

    companion object {
        private const val TIMEOUT_MS = 20_000
    }
}
