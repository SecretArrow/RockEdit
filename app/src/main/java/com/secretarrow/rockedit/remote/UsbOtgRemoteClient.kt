package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.RemoteClient
import com.secretarrow.rockedit.core.RemoteFile
import com.secretarrow.rockedit.core.RemotePath
import com.secretarrow.rockedit.core.UsbOtgLogic
import java.io.FileNotFoundException

/**
 * Volume abstraction so [UsbOtgRemoteClient] is pure JVM and testable; the
 * production adapter [LibAumsVolumeFs] binds it to a libaums FAT volume.
 */
interface UsbVolumeFs : AutoCloseable {
    fun listFiles(dirPath: String): List<RemoteFile>

    fun readFile(path: String): ByteArray

    fun writeFile(
        path: String,
        data: ByteArray,
    )

    fun makeDirectory(path: String)

    fun deletePath(path: String)

    override fun close()
}

/**
 * [RemoteClient] over an attached USB OTG mass-storage volume.
 *
 * Path model: the volume root is `/`; names are validated before delegating.
 * `close()` is intentionally a no-op because the underlying device session
 * is process-lifetime ([UsbOtgSupport.Session]) and reused across the
 * per-operation `use {}` pattern of the remote browser.
 */
class UsbOtgRemoteClient(
    private val volume: UsbVolumeFs,
    private val label: String,
) : RemoteClient {
    override fun list(path: String): List<RemoteFile> = volume.listFiles(UsbOtgLogic.volumePath(path))

    override fun read(path: String): ByteArray {
        val target = UsbOtgLogic.volumePath(path)
        if (target.isEmpty()) throw FileNotFoundException("cannot read the USB root as a file")
        return volume.readFile(target)
    }

    override fun write(
        path: String,
        data: ByteArray,
    ) {
        val target = UsbOtgLogic.volumePath(path)
        if (target.isEmpty()) throw IllegalStateException("cannot write the USB root as a file")
        volume.writeFile(target, data)
    }

    override fun mkdir(path: String) {
        val target = UsbOtgLogic.volumePath(path)
        if (target.isEmpty()) return // root exists by definition
        volume.makeDirectory(target)
    }

    override fun delete(path: String) {
        val target = UsbOtgLogic.volumePath(path)
        if (target.isEmpty()) throw IllegalStateException("cannot delete the USB root itself")
        volume.deletePath(target)
    }

    /** Volume stays open for the whole session (see class KDoc). */
    override fun close() {
        // Intentionally empty: session-scoped lifecycle.
    }

    fun displayLabel(): String = label

    /** Guarded path/name helper shared by the volume adapter. */
    internal fun splitParentChild(path: String): Pair<String, String> {
        val normalized = RemotePath.normalize(path)
        val name = RemotePath.name(normalized)
        if (name.isEmpty()) throw IllegalStateException("path '$normalized' has no file name")
        val parent = UsbOtgLogic.volumePath(RemotePath.parent(normalized))
        return parent to name
    }
}
