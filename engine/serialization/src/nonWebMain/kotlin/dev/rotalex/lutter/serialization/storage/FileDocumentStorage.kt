package dev.rotalex.lutter.serialization.storage

import kotlinx.io.buffered
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * [DocumentStorage] over the system filesystem (PLAN §21.2, §33.3).
 *
 * Off-web only: `SystemFileSystem` throws in a browser, so this lives in `nonWebMain`,
 * never in `commonMain`. The filesystem is a parameter so tests can substitute it; calls
 * stay unscheduled because callers own dispatch and this module has no coroutines dep.
 */
public class FileDocumentStorage(
    private val fileSystem: FileSystem = SystemFileSystem,
) : DocumentStorage {
    /**
     * Bytes at [location].
     *
     * Missing locations fail naming the location, not with the platform default.
     */
    override suspend fun read(location: String): ByteArray {
        val path = Path(location)
        if (!fileSystem.exists(path)) throw FileNotFoundException("no document at '$location'")
        val source = fileSystem.source(path).buffered()
        try {
            return source.readByteArray()
        } finally {
            source.close()
        }
    }

    /**
     * [bytes] at [location], replacing whatever was there.
     *
     * Missing parents are created: a document path names its own directories.
     */
    override suspend fun write(location: String, bytes: ByteArray) {
        val path = Path(location)
        val parent = path.parent
        if (parent != null && !fileSystem.exists(parent)) fileSystem.createDirectories(parent)
        val sink = fileSystem.sink(path).buffered()
        try {
            sink.write(bytes)
            sink.flush()
        } finally {
            sink.close()
        }
    }
}
