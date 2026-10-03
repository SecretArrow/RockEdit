package com.secretarrow.rockedit.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Resolves friendly display names for content URIs. */
object DisplayNames {

    fun resolve(context: Context, uri: Uri): String {
        var name: String = uri.lastPathSegment ?: "file.txt"
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx)?.let { name = it }
                    }
                }
        } catch (_: Exception) {
            // Keep the path-derived fallback.
        }
        return name
    }
}
