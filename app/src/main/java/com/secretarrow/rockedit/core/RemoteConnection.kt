package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject

/** Transport protocols supported by the Storage Manager. */
enum class RemoteType(val defaultPort: Int, val displayName: String) {
    FTP(21, "FTP"),
    FTPS(21, "FTPS (explicit TLS)"),
    SFTP(22, "SFTP (SSH)"),
    WEBDAV(80, "WebDAV")
}

/**
 * One saved remote connection. The password is stored as an encrypted blob
 * (see [RemoteConnectionStore]); this class always carries it decrypted.
 * Pure JVM class.
 */
data class RemoteConnection(
    val id: Long,
    val name: String,
    val type: RemoteType,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val initialPath: String = "/"
) {
    companion object {
        fun newId(): Long = System.currentTimeMillis()
    }
}

/**
 * Pure helpers for remote paths (SFTP/FTP/WebDAV style POSIX-ish paths) and
 * connection URI building. Fully unit tested.
 */
object RemotePath {

    /** Joins a directory path and a child name, keeping the path normalized. */
    fun child(dir: String, name: String): String {
        val base = if (dir.isEmpty() || dir == "/") "" else dir.trimEnd('/')
        return "$base/$name"
    }

    /** Parent of [path]; "/" at the root. */
    fun parent(path: String): String {
        if (path.isEmpty() || path == "/") return "/"
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx <= 0) "/" else trimmed.substring(0, idx)
    }

    /** Last segment of [path]; "" at the root. */
    fun name(path: String): String {
        if (path.isEmpty() || path == "/") return ""
        val trimmed = path.trimEnd('/')
        return trimmed.substringAfterLast('/')
    }

    /**
     * Normalizes a user-typed initial path: empty -> "/", collapses duplicate
     * slashes, keeps a trailing-slash-free form.
     */
    fun normalize(path: String): String {
        var p = path.trim().ifEmpty { "/" }
        if (!p.startsWith("/")) p = "/$p"
        while (p.contains("//")) p = p.replace("//", "/")
        if (p.length > 1 && p.endsWith("/")) p = p.trimEnd('/')
        return p.ifEmpty { "/" }
    }

    /**
     * Resolves the effective port: explicit (positive) wins, else the type
     * default; WebDAV over https prefixes are not guessed here.
     */
    fun resolvePort(type: RemoteType, explicit: Int): Int =
        if (explicit > 0) explicit else type.defaultPort

    /**
     * Builds the WebDAV base URL for a connection. [https] upgrades the
     * scheme (user choice stored in the port semantics: ports 443/8443 imply
     * https when the user leaves the default).
     */
    fun webDavUrl(host: String, port: Int, path: String, https: Boolean): String {
        val scheme = if (https) "https" else "http"
        val suffix = if (path.startsWith("/")) path else "/$path"
        return "$scheme://${host.trim()}:$port${normalize(suffix)}"
    }
}

/**
 * Persists remote connections in [KeyValueStore]. Passwords are encrypted
 * with the injected [RemoteEncryptor] before storage (Android side uses
 * Android Keystore AES-GCM); the pure layer is testable with fakes.
 */
class RemoteConnectionStore(
    private val kv: KeyValueStore,
    private val encryptor: RemoteEncryptor,
    private val capacity: Int = MAX_CONNECTIONS
) {

    interface RemoteEncryptor {
        fun encrypt(plain: String): String
        fun decrypt(cipher: String): String
    }

    fun list(): List<RemoteConnection> = parse(kv.getString(KEY, null) ?: "[]")

    /** Adds or updates (by id) a connection. Returns the new list. */
    fun save(connection: RemoteConnection): List<RemoteConnection> {
        val encrypted = connection.copy(
            password = if (connection.password.isEmpty()) "" else encryptor.encrypt(connection.password)
        )
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.id == connection.id }
        if (idx >= 0) current[idx] = encrypted else current.add(encrypted)
        val capped = current.takeLast(capacity)
        persist(capped)
        return list()
    }

    fun remove(id: Long): List<RemoteConnection> {
        val updated = list().filterNot { it.id == id }
        persist(updated)
        return list()
    }

    /** Returns the decrypted connection or null. */
    fun find(id: Long): RemoteConnection? =
        list().firstOrNull { it.id == id }?.let { it.copy(password = decryptPassword(it)) }

    private fun decryptPassword(encryptedConnection: RemoteConnection): String =
        if (encryptedConnection.password.isEmpty()) ""
        else try {
            encryptor.decrypt(encryptedConnection.password)
        } catch (_: Exception) {
            ""
        }

    private fun persist(connections: List<RemoteConnection>) {
        val arr = JSONArray()
        for (c in connections) {
            arr.put(
                JSONObject()
                    .put(F_ID, c.id)
                    .put(F_NAME, c.name)
                    .put(F_TYPE, c.type.name)
                    .put(F_HOST, c.host)
                    .put(F_PORT, c.port)
                    .put(F_USER, c.user)
                    .put(F_PASSWORD, c.password)
                    .put(F_PATH, c.initialPath)
            )
        }
        kv.putString(KEY, arr.toString())
    }

    private fun parse(raw: String): List<RemoteConnection> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val type = RemoteType.entries.firstOrNull { it.name == o.optString(F_TYPE) }
                ?: return@mapNotNull null
            RemoteConnection(
                id = o.getLong(F_ID),
                name = o.optString(F_NAME),
                type = type,
                host = o.optString(F_HOST),
                port = o.optInt(F_PORT, type.defaultPort),
                user = o.optString(F_USER),
                password = o.optString(F_PASSWORD),
                initialPath = RemotePath.normalize(o.optString(F_PATH, "/"))
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val KEY = "remote_connections"
        const val MAX_CONNECTIONS = 20
        private const val F_ID = "id"
        private const val F_NAME = "name"
        private const val F_TYPE = "type"
        private const val F_HOST = "host"
        private const val F_PORT = "port"
        private const val F_USER = "user"
        private const val F_PASSWORD = "password"
        private const val F_PATH = "path"
    }
}

/** One entry in a remote directory listing. */
data class RemoteFile(
    val name: String,
    val path: String,
    val isFolder: Boolean,
    val size: Long = -1,
    val lastModified: Long = 0
)

/** Interface implemented by the transport clients (FTP/FTPS/SFTP/WebDAV). */
interface RemoteClient : AutoCloseable {
    /** Lists [path]; folders first is handled by the caller. */
    fun list(path: String): List<RemoteFile>

    /** Reads a whole file. */
    fun read(path: String): ByteArray

    /** Writes a whole file. */
    fun write(path: String, data: ByteArray)

    /** Creates a directory (no-op-safe when it exists). */
    fun mkdir(path: String)

    /** Deletes a file or an empty folder. */
    fun delete(path: String)
}
