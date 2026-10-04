package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.FtpListParser
import com.secretarrow.rockedit.core.RemoteClient
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.RemoteFile
import com.secretarrow.rockedit.core.RemotePath
import com.secretarrow.rockedit.core.RemoteType
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient

/**
 * FTP/FTPS transport backed by Apache Commons Net. Connections are opened
 * per operation (simple and robust against flaky servers); timeouts keep
 * UI-driven usage responsive.
 */
class FtpRemoteClient(private val connection: RemoteConnection) : RemoteClient {

    private val secure = connection.type == RemoteType.FTPS

    private fun connect(): FTPClient {
        val client = if (secure) FTPSClient("TLS") else FTPClient()
        client.connectTimeout = TIMEOUT_MS
        client.defaultTimeout = TIMEOUT_MS
        client.connect(connection.host, RemotePath.resolvePort(connection.type, connection.port))
        client.enterLocalPassiveMode()
        if (!client.login(connection.user.ifEmpty { "anonymous" }, connection.password)) {
            client.disconnect()
            throw IllegalStateException("FTP login failed for ${connection.host}")
        }
        client.setFileType(FTP.BINARY_FILE_TYPE)
        client.setControlEncoding("UTF-8")
        return client
    }

    override fun list(path: String): List<RemoteFile> {
        val dir = RemotePath.normalize(path)
        withFtp { client ->
            val files = client.listFiles(dir).filterNotNull()
            val parsed = FtpListParser.parseUnix(files.map { it.rawListing })
            if (parsed.isNotEmpty()) return parsed
            return files.map { f ->
                RemoteFile(
                    name = f.name,
                    path = RemotePath.child(dir, f.name),
                    isFolder = f.isDirectory,
                    size = f.size,
                    lastModified = f.timestamp?.timeInMillis ?: 0L
                )
            }
        }
    }

    override fun read(path: String): ByteArray = withFtp { client ->
        val stream = client.retrieveFileStream(RemotePath.normalize(path))
            ?: throw IllegalStateException("FTP open failed: $path (${client.replyString})")
        val bytes = stream.use { it.readBytes() }
        client.completePendingCommand()
        bytes
    }

    override fun write(path: String, data: ByteArray) = withFtp { client ->
        val stream = client.storeFileStream(RemotePath.normalize(path))
            ?: throw IllegalStateException("FTP write failed: $path (${client.replyString})")
        stream.use { it.write(data) }
        if (!client.completePendingCommand()) {
            throw IllegalStateException("FTP write incomplete: $path")
        }
    }

    override fun mkdir(path: String) {
        withFtp { client -> client.makeDirectory(RemotePath.normalize(path)) }
    }

    override fun delete(path: String) = withFtp { client ->
        val target = RemotePath.normalize(path)
        // Try file first, then directory (a plain rmdir works on empty dirs).
        if (!client.deleteFile(target)) {
            client.removeDirectory(target)
        }
    }

    override fun close() {
        // Stateless: per-operation connections are closed after each call.
    }

    private inline fun <T> withFtp(block: (FTPClient) -> T): T {
        val client = connect()
        try {
            return block(client)
        } finally {
            try {
                if (client.isConnected) {
                    client.logout()
                    client.disconnect()
                }
            } catch (_: Exception) {
                // Ignore shutdown issues.
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 15_000
    }
}
