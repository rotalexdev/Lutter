package dev.rotalex.lutter.desktopapp

import dev.rotalex.lutter.serialization.storage.DocumentStorage
import dev.rotalex.lutter.serialization.storage.FileDocumentStorage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [DocumentStorage] whose writes cannot be observed half-done.
 *
 * A temporary sibling and `ATOMIC_MOVE`, because the alternative — [FileDocumentStorage]'s
 * in-place overwrite — is a truncated document if the process dies mid-write, and this window
 * writes on every keystroke that reaches the document.
 *
 * The temporary file is a sibling with a fixed name rather than `createTempFile`: the move must
 * stay inside one filesystem to be atomic, and a stale `.tmp` from a previous crash is simply
 * overwritten, which is the behaviour that leaves no litter to clean up.
 */
internal class AtomicDocumentStorage(
    private val delegate: DocumentStorage = FileDocumentStorage(),
) : DocumentStorage {

    override suspend fun read(location: String): ByteArray = delegate.read(location)

    override suspend fun write(location: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val target: Path = File(location).toPath()
        target.parent?.let { parent -> Files.createDirectories(parent) }
        val temporary: Path = target.resolveSibling(target.fileName.toString() + TEMPORARY_SUFFIX)
        Files.write(temporary, bytes)
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (atomic: IOException) {
            // A filesystem that cannot do it atomically (some network mounts) still gets a
            // replace, which is what the old writer did; refusing to save would be worse.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val TEMPORARY_SUFFIX: String = ".tmp"
    }
}

/**
 * Where the editor keeps its working copy and its session record.
 *
 * `LUTTER_EDITOR_HOME` wins over everything, which is what lets a test point the editor at a
 * throw-away directory instead of the developer's home — and what lets a person run two editors
 * on two documents without either of them overwriting the other's working copy.
 *
 * Otherwise the platform's own place for application data: `%APPDATA%` on Windows,
 * `~/Library/Application Support` on macOS, `$XDG_DATA_HOME` (or `~/.local/share`) everywhere
 * else. Creating directories in `~/Downloads` or beside a jar is the behaviour this avoids.
 */
internal fun workspaceDirectory(): File {
    val override: String? = System.getenv("LUTTER_EDITOR_HOME")
    if (!override.isNullOrBlank()) return File(override)

    val home: String = System.getProperty("user.home")
    val os: String = System.getProperty("os.name").orEmpty()
    return when {
        os.startsWith("Windows", ignoreCase = true) ->
            File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "LutterEditor")
        os.startsWith("Mac", ignoreCase = true) ->
            File(home, "Library/Application Support/LutterEditor")
        else ->
            File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "lutter-editor")
    }
}
