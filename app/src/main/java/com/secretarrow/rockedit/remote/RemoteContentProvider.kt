package com.secretarrow.rockedit.remote

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.FileNames
import com.secretarrow.rockedit.core.RemoteClientFactory

/**
 * Exposes remote files (FTP/FTPS/SFTP/WebDAV) as content URIs so the editor
 * works with them exactly like local SAF files:
 *
 * `content://com.secretarrow.rockedit.remote/<connId>/<abs/path>`
 *
 * Reads download the file on demand; writes are buffered and uploaded when
 * the stream closes.
 */
class RemoteContentProvider : ContentProvider() {

    private fun connectionId(uri: Uri): Long = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: -1L

    private fun remotePath(uri: Uri): String =
        "/" + uri.pathSegments.drop(1).joinToString("/")

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        val name = remotePath(uri).substringAfterLast('/')
        return if (FileNames.looksLikeTextFile(name)) "text/plain" else "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val name = remotePath(uri).trimEnd('/').substringAfterLast('/')
        val cursor = MatrixCursor(
            projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        )
        val row = cursor.newRow()
        for (column in (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(name)
                OpenableColumns.SIZE -> row.add(null)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun openInputStream(uri: Uri): java.io.InputStream? {
        val context = context ?: return null
        val connection = App.remoteConnections(context).find(connectionId(uri)) ?: return null
        val client = RemoteClientFactory.create(connection)
        client.use { return it.read(remotePath(uri)).inputStream() }
    }

    override fun openOutputStream(uri: Uri, mode: String): java.io.OutputStream? {
        val context = context ?: return null
        val connection = App.remoteConnections(context).find(connectionId(uri)) ?: return null
        val client = RemoteClientFactory.create(connection)
        val target = remotePath(uri)
        return object : java.io.ByteArrayOutputStream() {
            override fun close() {
                super.close()
                client.use { it.write(target, toByteArray()) }
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private inline fun <T> com.secretarrow.rockedit.core.RemoteClient.use(block: (com.secretarrow.rockedit.core.RemoteClient) -> T): T {
        try {
            return block(this)
        } finally {
            try {
                close()
            } catch (_: Exception) {
            }
        }
    }
}
