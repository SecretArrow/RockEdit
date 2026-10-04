package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.RemoteClient
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.RemoteType

/** Builds the transport client for a saved connection. */
object RemoteClientFactory {

    fun create(connection: RemoteConnection): RemoteClient = when (connection.type) {
        RemoteType.FTP, RemoteType.FTPS -> FtpRemoteClient(connection)
        RemoteType.SFTP -> SftpRemoteClient(connection)
        RemoteType.WEBDAV -> WebDavRemoteClient(connection, https = connection.port == 443)
        RemoteType.GITHUB, RemoteType.GITLAB -> GitRemoteClient(connection)
    }
}
