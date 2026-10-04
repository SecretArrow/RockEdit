package com.secretarrow.rockedit.remote

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.FileNames
import java.io.File

/**
 * Exposes remote files (FTP/FTPS/SFTP/WebDAV) as content URIs so the editor
 * works with them exactly like local SAF files:
 *
 * `content://com.secretarrow.rockedit.remote/<connId>/<abs/path>`
 *
 * Reads copy the file into a cache-backed descriptor on demand; writes go to
 * a temporary file that is uploaded when the descriptor closes.
 */
class RemoteContentProvider : ContentProvider() {
    private fun connectionId(uri: Uri): Long = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: -1L

    private fun remotePath(uri: Uri): String = "/" + uri.pathSegments.drop(1).joinToString("/")

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
        sortOrder: String?,
    ): Cursor? {
        val name = remotePath(uri).trimEnd('/').substringAfterLast('/')
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(name)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val context = context ?: return null
        val connection = App.remoteConnections(context).find(connectionId(uri)) ?: return null
        val client = RemoteClientFactory.create(connection)
        val target = remotePath(uri)
        val writeMode = mode.contains('w')

        val cache = File(context.cacheDir, "remote").apply { mkdirs() }
        val temp = File(cache, "${connectionId(uri)}_${System.currentTimeMillis()}_${target.hashCode()}.tmp")

        return try {
            if (writeMode) {
                temp.createNewFile()
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                ParcelFileDescriptor.open(
                    temp,
                    ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_CREATE,
                    handler,
                ) {
                    // Upload when the writer closes the descriptor.
                    try {
                        client.use { it.write(target, temp.readBytes()) }
                    } catch (_: Exception) {
                        // Network errors surface on next read; avoid crashing callers.
                    } finally {
                        temp.delete()
                    }
                }
            } else {
                client.use { temp.writeBytes(it.read(target)) }
                val descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
                temp.deleteOnExit()
                descriptor
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
