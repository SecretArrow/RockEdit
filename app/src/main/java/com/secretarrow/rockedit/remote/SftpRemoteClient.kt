package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.RemoteClient
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.RemoteFile
import com.secretarrow.rockedit.core.RemotePath
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.xfer.FileSystemFile
import java.io.File

/**
 * SFTP transport backed by sshj. Host keys are accepted on first use
 * (TOFU-free for simplicity); credentials are user-managed in the app.
 */
class SftpRemoteClient(
    private val connection: RemoteConnection,
) : RemoteClient {
    private fun connect(): Pair<SSHClient, SFTPClient> {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connectTimeout = TIMEOUT_MS
        ssh.timeout = TIMEOUT_MS
        ssh.connect(connection.host, RemotePath.resolvePort(connection.type, connection.port))
        ssh.authPassword(connection.user.ifEmpty { "anonymous" }, connection.password)
        val sftp = ssh.newSFTPClient()
        return ssh to sftp
    }

    override fun list(path: String): List<RemoteFile> {
        val dir = RemotePath.normalize(path)
        val (ssh, sftp) = connect()
        try {
            return sftp
                .ls(dir)
                .filter { it.name != "." && it.name != ".." }
                .map { entry ->
                    RemoteFile(
                        name = entry.name,
                        path = RemotePath.child(dir, entry.name),
                        isFolder = entry.isDirectory,
                        size = if (entry.isDirectory) -1 else entry.attributes.size,
                        lastModified = entry.attributes.mtime * 1000L,
                    )
                }
        } finally {
            closeQuietly(ssh, sftp)
        }
    }

    override fun read(path: String): ByteArray {
        val (ssh, sftp) = connect()
        try {
            val temp = File.createTempFile("rockedit-dl", ".tmp")
            try {
                sftp.get(RemotePath.normalize(path), FileSystemFile(temp))
                return temp.readBytes()
            } finally {
                temp.delete()
            }
        } finally {
            closeQuietly(ssh, sftp)
        }
    }

    override fun write(
        path: String,
        data: ByteArray,
    ) {
        val (ssh, sftp) = connect()
        try {
            val temp = File.createTempFile("rockedit-up", ".tmp")
            try {
                temp.writeBytes(data)
                sftp.put(FileSystemFile(temp), RemotePath.normalize(path))
            } finally {
                temp.delete()
            }
        } finally {
            closeQuietly(ssh, sftp)
        }
    }

    override fun mkdir(path: String) {
        val (ssh, sftp) = connect()
        try {
            try {
                sftp.mkdir(RemotePath.normalize(path))
            } catch (_: java.io.IOException) {
                // Likely exists already.
            }
        } finally {
            closeQuietly(ssh, sftp)
        }
    }

    override fun delete(path: String) {
        val (ssh, sftp) = connect()
        try {
            val target = RemotePath.normalize(path)
            try {
                sftp.rm(target)
            } catch (_: java.io.IOException) {
                sftp.rmdir(target)
            }
        } finally {
            closeQuietly(ssh, sftp)
        }
    }

    override fun close() {
        // Stateless: per-operation connections are closed after each call.
    }

    private fun closeQuietly(
        ssh: SSHClient,
        sftp: SFTPClient,
    ) {
        try {
            sftp.close()
        } catch (_: Exception) {
        }
        try {
            ssh.disconnect()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TIMEOUT_MS = 20_000
    }
}
