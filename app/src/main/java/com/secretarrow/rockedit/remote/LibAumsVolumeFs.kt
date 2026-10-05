package com.secretarrow.rockedit.remote

import com.github.mjdev.libaums.fs.FileSystem
import com.github.mjdev.libaums.fs.UsbFile
import com.github.mjdev.libaums.fs.UsbFileInputStream
import com.github.mjdev.libaums.fs.UsbFileOutputStream
import com.secretarrow.rockedit.core.RemoteFile
import com.secretarrow.rockedit.core.RemotePath
import com.secretarrow.rockedit.core.UsbOtgLogic
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/**
 * Binds [UsbVolumeFs] to a libaums FAT [FileSystem]. Only this class (and
 * [UsbOtgSupport]) touches libaums, so hardware-specific behavior is easy to
 * audit. All libaums calls are wrapped: hardware problems surface as
 * IOException/IllegalStateException with an informative message, never as a
 * crash of the editor.
 */
class LibAumsVolumeFs(
    private val fileSystem: FileSystem,
) : UsbVolumeFs {
    override fun listFiles(dirPath: String): List<RemoteFile> {
        val folder = resolve(dirPath)
        if (!folder.isDirectory) throw FileNotFoundException("'$dirPath' is not a folder on the USB volume")
        val children = folder.list() ?: return emptyList()
        val out = ArrayList<RemoteFile>(children.size)
        for (child in children) {
            val name = child.name ?: continue
            if (name.isEmpty()) continue
            out.add(
                RemoteFile(
                    name = name,
                    path = RemotePath.child(dirPath, name),
                    isFolder = child.isDirectory,
                    size = if (child.isDirectory) -1 else child.length,
                ),
            )
        }
        return out
    }

    override fun readFile(path: String): ByteArray {
        val file = resolve(path)
        if (file.isDirectory) throw FileNotFoundException("'$path' is a folder, not a file")
        val stream = UsbFileInputStream(file)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    override fun writeFile(
        path: String,
        data: ByteArray,
    ) {
        val (parentPath, name) = split(path)
        val parent = resolve(parentPath)
        val existing = child(parent, name)
        existing?.delete()
        val file = parent.createFile(name)
        UsbFileOutputStream(file).use { it.write(data) }
    }

    override fun makeDirectory(path: String) {
        val (parentPath, name) = split(path)
        val parent = resolve(parentPath)
        val existing = child(parent, name)
        if (existing != null) return // no-op-safe when it exists
        parent.createDirectory(name)
    }

    override fun deletePath(path: String) {
        val file = resolve(path)
        file.delete()
    }

    override fun close() {
        // Volume lifecycle is managed by UsbOtgSupport (device-level close).
    }

    /** Walks segments from the root; informative error when a part is missing. */
    private fun resolve(path: String): UsbFile {
        var current = fileSystem.rootDirectory
        val segments = path.split('/').filter { it.isNotEmpty() }
        var walked = ""
        for (segment in segments) {
            walked = "$walked/$segment"
            val next = child(current, segment) ?: throw FileNotFoundException("'$walked' does not exist on the USB volume")
            current = next
        }
        return current
    }

    /** Name-based child lookup (avoids libaums path-search semantics). */
    private fun child(
        folder: UsbFile,
        name: String,
    ): UsbFile? {
        val children = folder.list() ?: return null
        for (child in children) {
            if (child.name == name) return child
        }
        return null
    }

    private fun split(path: String): Pair<String, String> {
        val normalized = RemotePath.normalize(path)
        val name = RemotePath.name(normalized)
        if (name.isEmpty()) throw IllegalStateException("path '$normalized' has no file name")
        return UsbOtgLogic.volumePath(RemotePath.parent(normalized)) to name
    }
}
