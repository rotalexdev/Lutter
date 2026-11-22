package dev.rotalex.lutter.plugin.container

import dev.rotalex.lutter.model.plugin.PluginContainerLayout
import dev.rotalex.lutter.model.plugin.PluginManifest
import dev.rotalex.lutter.model.plugin.PluginPayload
import dev.rotalex.lutter.model.plugin.problems
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/**
 * Writes a `.lutterplugin` (ADR-018).
 *
 * Two properties make this a packager and not a `ZipOutputStream` call site:
 *
 * 1. **It refuses to produce a container its own manifest describes wrongly.** The same
 *    [PluginManifest.problems] the reader applies is applied before anything is written, so
 *    "declares `full`, ships a jar" cannot leave this function as a file. That is what lets the
 *    packaging Gradle plugin honestly refuse `android-only` while no dex step exists: it passes no
 *    android payloads, and the writer names the problem.
 * 2. **It is deterministic.** Entries are sorted, stored rather than deflated, and stamped with
 *    one fixed DOS time, so packaging the same inputs twice produces the same bytes — §36.2's rule
 *    applied to a second kind of artifact.
 */
public object PluginContainerWriter {

    /**
     * A DOS-representable fixed time (1980-01-01T00:00:00Z). Epoch `0` is before the format's
     * floor and makes the JDK add an extended-timestamp field, which would be one more thing whose
     * bytes could vary between JDKs.
     */
    private const val FIXED_TIME: Long = 315_532_800_000L

    private val json: Json = Json { prettyPrint = true }

    /**
     * Writes [manifest] and [jvmPayloads]/[androidPayloads] to [target].
     *
     * Payload maps are entry-name → bytes, where the name is the file's name inside the payload
     * directory (`classes/jvm/<name>`). A name is a plain file name: no separators, no traversal,
     * because the writer builds the entry path and a caller that could supply `../` would make the
     * container a way to write outside its own directory when a host extracted it.
     *
     * @throws PluginContainerException when the manifest declares a payload the arguments do not
     *   provide.
     * @throws IllegalArgumentException when a payload name is not a plain file name.
     */
    public fun write(
        target: File,
        manifest: PluginManifest,
        jvmPayloads: Map<String, ByteArray> = emptyMap(),
        androidPayloads: Map<String, ByteArray> = emptyMap(),
    ) {
        val present: Set<PluginPayload> = buildSet {
            if (jvmPayloads.isNotEmpty()) add(PluginPayload.JvmJar)
            if (androidPayloads.isNotEmpty()) add(PluginPayload.AndroidDex)
        }
        val problems = manifest.problems(present)
        if (problems.isNotEmpty()) {
            throw PluginContainerException(
                problems.map { problem ->
                    PluginContainerProblem(
                        problem.code,
                        "plugin '${manifest.id}' declares build '${manifest.build}' and " +
                            "apiVersion ${manifest.apiVersion}; written payloads: " +
                            present.joinToString(", ") { it.name.lowercase() }.ifEmpty { "none" },
                    )
                },
                "refusing to write a container that will not load: " +
                    problems.joinToString(", ") { it.code },
            )
        }

        // Names are validated before the file is opened: a bad name must not leave a half-written
        // container behind for the next run to read as if it were complete.
        val jvmEntries: List<Pair<String, ByteArray>> =
            jvmPayloads.toSortedMap().map { (name, bytes) -> plainName(name) to bytes }
        val androidEntries: List<Pair<String, ByteArray>> =
            androidPayloads.toSortedMap().map { (name, bytes) -> plainName(name) to bytes }

        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            // The manifest first, so a tool that streams the container finds identity before
            // payloads; the rest in sorted order for determinism.
            zip.putStored(
                PluginContainerLayout.ManifestPath,
                json.encodeToString(PluginManifest.serializer(), manifest).encodeToByteArray(),
            )
            for ((name, bytes) in jvmEntries) {
                zip.putStored(PluginContainerLayout.JvmPayloadPrefix + name, bytes)
            }
            for ((name, bytes) in androidEntries) {
                zip.putStored(PluginContainerLayout.AndroidPayloadPrefix + name, bytes)
            }
        }
    }

    private fun plainName(name: String): String {
        require(name.isNotEmpty()) { "a payload entry needs a name" }
        require(name.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) {
            "'$name' is not a plain file name: a payload entry may not contain a separator or '..'"
        }
        require(name != "." && name != "..") { "'$name' is not a file name" }
        return name
    }

    private fun ZipOutputStream.putStored(name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            time = FIXED_TIME
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }
}
