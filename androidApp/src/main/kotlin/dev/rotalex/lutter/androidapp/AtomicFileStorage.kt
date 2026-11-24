package dev.rotalex.lutter.androidapp

import dev.rotalex.lutter.serialization.storage.DocumentStorage
import java.io.File

/**
 * [DocumentStorage] over a real file, replacing the target in one step.
 *
 * Android has no `java.nio.file` below API 26, and this app supports lower, so the atomic move the
 * desktop host uses is not available here. [File.renameTo] is the portable equivalent: on every
 * filesystem Android ships, it is a `rename(2)`, which replaces the destination atomically. The
 * fallback — delete, then rename — is reached only on a filesystem where replace-rename is
 * unsupported (FAT-formatted external storage, which this host does not use); it is not atomic,
 * and it is still better than leaving a truncated half-written document, which is what an in-place
 * write leaves.
 *
 * The temporary file gets a **fixed** name beside the target rather than one from
 * `File.createTempFile`: a retry must reuse the same path, and the name is deterministic enough to
 * explain in a bug report.
 */
internal class AtomicFileStorage : DocumentStorage {

    override suspend fun read(location: String): ByteArray {
        val file = File(location)
        if (!file.isFile) throw IllegalStateException("no document at '$location'")
        return file.readBytes()
    }

    override suspend fun write(location: String, bytes: ByteArray) {
        val target = File(location)
        target.parentFile?.mkdirs()

        val temporary = File(target.path + TEMPORARY_SUFFIX)
        temporary.writeBytes(bytes)

        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "could not replace '$location'" }
        }
    }

    private companion object {
        /** The sibling an interrupted write leaves behind; [EditorWorkspace] overwrites it. */
        const val TEMPORARY_SUFFIX: String = ".tmp"
    }
}
