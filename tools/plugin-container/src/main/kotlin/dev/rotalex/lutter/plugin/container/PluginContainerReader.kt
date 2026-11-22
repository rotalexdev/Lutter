package dev.rotalex.lutter.plugin.container

import dev.rotalex.lutter.model.plugin.PluginContainerLayout
import dev.rotalex.lutter.model.plugin.PluginManifest
import dev.rotalex.lutter.model.plugin.PluginManifestProblem
import dev.rotalex.lutter.model.plugin.PluginPayload
import dev.rotalex.lutter.model.plugin.problems
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The codes a container can fail with that are not about the manifest itself (ADR-018).
 *
 * `plugin.manifest_invalid` is shared with [PluginManifestProblem.EntryPointMissing] on purpose:
 * both mean "this manifest cannot be used", and a host that switched on the code would treat them
 * identically. The detail string is what tells a person which one happened.
 */
public object PluginContainerProblemCodes {
    /** The file cannot be read as a ZIP at all. */
    public const val Unreadable: String = "plugin.container_unreadable"

    /** The ZIP has no entry at [PluginContainerLayout.ManifestPath]. */
    public const val ManifestMissing: String = "plugin.manifest_missing"

    /** The manifest entry exists and is not a valid manifest. */
    public const val ManifestInvalid: String = "plugin.manifest_invalid"
}

/** One reason a container cannot be opened, with enough detail to fix it without a debugger. */
public class PluginContainerProblem(

    /** The diagnostic code, in the dotted shape every other code uses. */
    public val code: String,

    /** What exactly was wrong. Prose, for a person; no machine reads it. */
    public val detail: String,
)

/**
 * A container that is present but not loadable (ADR-018).
 *
 * Carries **every** problem found rather than the first: a manifest can declare a build its
 * payloads do not back *and* an API version this engine does not implement, and a host that
 * reported one would make the other a discovery for the next run.
 */
public class PluginContainerException(
    /** Everything wrong, in the order the reader found it. */
    public val problems: List<PluginContainerProblem>,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The codes, for a host that reports rather than prints. */
    public val codes: List<String> get() = problems.map { it.code }

    public companion object {
        private fun of(problem: PluginContainerProblem, cause: Throwable? = null): PluginContainerException =
            PluginContainerException(listOf(problem), "${problem.code}: ${problem.detail}", cause)

        internal fun unreadable(detail: String, cause: Throwable? = null): PluginContainerException =
            of(PluginContainerProblem(PluginContainerProblemCodes.Unreadable, detail), cause)

        internal fun manifestMissing(detail: String): PluginContainerException =
            of(PluginContainerProblem(PluginContainerProblemCodes.ManifestMissing, detail))

        internal fun manifestInvalid(detail: String): PluginContainerException =
            of(PluginContainerProblem(PluginContainerProblemCodes.ManifestInvalid, detail))
    }
}

/**
 * An opened container: its manifest and the payload entries it actually carries (ADR-018).
 *
 * Deliberately *not* holding open resources — a ZIP that stayed open would be a file handle a
 * caller has to remember to close, and a plugin read is a short operation whose result is data.
 */
public class PluginContainer(
    /** The manifest, already verified against [PluginPayload]. */
    public val manifest: PluginManifest,

    /** Entry names under `classes/jvm/`, sorted. Empty for a dex-only build. */
    public val jvmPayloads: List<String>,

    /** Entry names under `classes/android/`, sorted. Empty for a jar-only build. */
    public val androidPayloads: List<String>,
) {
    /** Whether this container carries a payload of the given kind. */
    public fun has(payload: PluginPayload): Boolean = payloadsFor(payload).isNotEmpty()

    /** The payload entry names of the given kind, sorted; empty when there are none. */
    public fun payloadsFor(payload: PluginPayload): List<String> =
        when (payload) {
            PluginPayload.JvmJar -> jvmPayloads
            PluginPayload.AndroidDex -> androidPayloads
        }
}

/**
 * Reads a `.lutterplugin` (ADR-018).
 *
 * The order of the checks is the order of the consequences: an unreadable file, then a missing
 * manifest, then a manifest that does not decode, and only then the semantic checks
 * [PluginManifest.problems] performs. Nothing is loaded here — this module never names a
 * `ClassLoader`, and the host that owns one does the loading.
 */
public object PluginContainerReader {

    /**
     * The codec. `ignoreUnknownKeys` is the format's forward compatibility: a container written
     * by a newer packager that adds a field must stay readable, because the alternative is every
     * plugin in the wild breaking on an engine upgrade.
     */
    private val json: Json = Json { ignoreUnknownKeys = true }

    /** Opens [file] and returns its manifest and payloads, or throws [PluginContainerException]. */
    public fun read(file: File): PluginContainer {
        val entries: Map<String, ByteArray> = entriesOf(file)
        val manifestBytes: ByteArray = entries[PluginContainerLayout.ManifestPath]
            ?: throw PluginContainerException.manifestMissing(
                "'${file.name}' has no ${PluginContainerLayout.ManifestPath}",
            )
        val manifest: PluginManifest = decode(manifestBytes, file)
        return verify(manifest, entries.keys, file)
    }

    private fun entriesOf(file: File): Map<String, ByteArray> {
        if (!file.isFile) {
            throw PluginContainerException.unreadable("'${file.path}' is not a file")
        }
        val entries: MutableMap<String, ByteArray> = linkedMapOf()
        try {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // A directory entry carries no payload; it is recorded as nothing rather than
                    // as an empty byte array, so `has` does not lie about a build's payloads.
                    if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
        } catch (error: IOException) {
            throw PluginContainerException.unreadable("'${file.name}' is not a readable ZIP: ${error.message}", error)
        }
        return entries
    }

    private fun decode(bytes: ByteArray, file: File): PluginManifest =
        try {
            // The serializer is named rather than inferred: `:engine:model` generates it and every
            // other call site in this repository passes it explicitly.
            json.decodeFromString(PluginManifest.serializer(), bytes.decodeToString())
        } catch (error: SerializationException) {
            throw PluginContainerException.manifestInvalid(
                "'${file.name}' carries a manifest that does not decode: ${error.message}",
            )
        } catch (error: IllegalArgumentException) {
            // A value-class `init` (`PluginId`, say) refuses a malformed id with `require`, and
            // that is the same kind of failure: the manifest is present and unusable.
            throw PluginContainerException.manifestInvalid(
                "'${file.name}' carries a manifest with an invalid value: ${error.message}",
            )
        }

    private fun verify(
        manifest: PluginManifest,
        entryNames: Set<String>,
        file: File,
    ): PluginContainer {
        val jvm: List<String> = payloadNames(entryNames, PluginContainerLayout.JvmPayloadPrefix)
        val android: List<String> = payloadNames(entryNames, PluginContainerLayout.AndroidPayloadPrefix)
        val present: Set<PluginPayload> = buildSet {
            if (jvm.isNotEmpty()) add(PluginPayload.JvmJar)
            if (android.isNotEmpty()) add(PluginPayload.AndroidDex)
        }
        val problems: List<PluginManifestProblem> = manifest.problems(present)
        if (problems.isNotEmpty()) {
            throw PluginContainerException(
                problems.map { problem ->
                    PluginContainerProblem(
                        problem.code,
                        "plugin '${manifest.id}' declares build '${manifest.build}' and " +
                            "apiVersion ${manifest.apiVersion}; present payloads: " +
                            present.joinToString(", ") { it.name.lowercase() }.ifEmpty { "none" },
                    )
                },
                "'${file.name}' is not loadable: ${problems.joinToString(", ") { it.code }}",
            )
        }
        return PluginContainer(manifest, jvm, android)
    }

    private fun payloadNames(entryNames: Set<String>, prefix: String): List<String> =
        entryNames
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotEmpty() }
            .sorted()
}
