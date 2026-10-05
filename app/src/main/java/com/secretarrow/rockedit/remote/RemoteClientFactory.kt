package com.secretarrow.rockedit.remote

import android.content.Context
import com.secretarrow.rockedit.core.CloudHttp
import com.secretarrow.rockedit.core.KeystoreEncryptor
import com.secretarrow.rockedit.core.OAuthTokenExchanger
import com.secretarrow.rockedit.core.OAuthTokenStore
import com.secretarrow.rockedit.core.RemoteClient
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.StoreBackedCloudAuth

/**
 * Builds the transport client for a saved connection.
 *
 * v0.15.0: OAuth cloud connections (Drive/Dropbox/OneDrive) share one
 * [HttpUrlCloudHttp] singleton and get a [StoreBackedCloudAuth] backed by
 * the encrypted token store. USB OTG is NOT a saved connection — it is
 * opened through [UsbOtgSupport] and reaches callers via [RemoteClients],
 * so this factory rejects it with an actionable message.
 */
object RemoteClientFactory {
    fun create(
        context: Context,
        connection: RemoteConnection,
    ): RemoteClient =
        when (connection.type) {
            RemoteType.FTP, RemoteType.FTPS -> FtpRemoteClient(connection)
            RemoteType.SFTP -> SftpRemoteClient(connection)
            RemoteType.WEBDAV -> WebDavRemoteClient(connection, https = connection.port == 443)
            RemoteType.GITHUB, RemoteType.GITLAB -> GitRemoteClient(connection)
            RemoteType.GOOGLE_DRIVE ->
                com.secretarrow.rockedit.remote.GoogleDriveRemoteClient(
                    connection,
                    CloudRuntime.http,
                    cloudAuth(context, connection),
                )
            RemoteType.DROPBOX ->
                DropboxRemoteClient(connection, CloudRuntime.http, cloudAuth(context, connection))
            RemoteType.ONEDRIVE ->
                OneDriveRemoteClient(connection, CloudRuntime.http, cloudAuth(context, connection))
            RemoteType.USB_OTG ->
                throw IllegalStateException(
                    "USB OTG is opened from the Storage Manager, not through a saved connection",
                )
        }

    private fun cloudAuth(
        context: Context,
        connection: RemoteConnection,
    ): StoreBackedCloudAuth {
        val store =
            OAuthTokenStore(
                com.secretarrow.rockedit.core.App
                    .keyValueStore(context),
                KeystoreEncryptor,
            )
        return StoreBackedCloudAuth(connection, store, OAuthTokenExchanger(CloudRuntime.http))
    }
}

/** Lazily created shared HTTP transport for OAuth exchanges and cloud APIs. */
object CloudRuntime {
    val http: CloudHttp by lazy { HttpUrlCloudHttp() }
}
