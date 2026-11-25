package dev.rotalex.lutter.model.plugin

import dev.rotalex.lutter.model.doc.PluginRequirement
import dev.rotalex.lutter.model.ids.PluginId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a plugin's container can carry, per platform the host can execute (ADR-018).
 *
 * Two entries and not a subset of `PlatformTag`: this is a *payload* kind, not a platform the
 * document targets. A desktop host executes a jar; an Android host executes dex; no host executes
 * either for iOS or Web, so a plugin built for them would have nothing to carry.
 */
public enum class PluginPayload {
    /** A jar, for a desktop/JVM host. */
    JvmJar,

    /** A dex, for an Android host. */
    AndroidDex,
}

/**
 * The one build a `.lutterplugin` ships, declared in its manifest (ADR-018).
 *
 * `full` is a single container carrying both payloads — not two artifacts. A plugin's identity
 * must outlive packaging: `id`, `version` and `dependsOn` live in one manifest, and a plugin
 * published twice under one id would make "which one did I load?" unanswerable.
 */
@Serializable
public enum class PluginBuild {
    /** Both payloads. */
    @SerialName("full")
    Full,

    /** Dex only: a host that is not Android refuses it rather than pretending. */
    @SerialName("android-only")
    AndroidOnly,

    /** A jar only. */
    @SerialName("desktop-only")
    DesktopOnly;

    /** The payload kinds this build must actually contain for the container to be valid. */
    public fun requiredPayloads(): Set<PluginPayload> =
        when (this) {
            Full -> setOf(PluginPayload.JvmJar, PluginPayload.AndroidDex)
            AndroidOnly -> setOf(PluginPayload.AndroidDex)
            DesktopOnly -> setOf(PluginPayload.JvmJar)
        }
}

/**
 * Where the container's parts live, in one place (ADR-018).
 *
 * Written as constants rather than as a data class: a container is a file format, and a format
 * has one set of paths. A test asserts them against the writer, so a rename cannot pass silently.
 */
public object PluginContainerLayout {
    /** The manifest's entry inside the container. The only required entry. */
    public const val ManifestPath: String = "META-INF/lutter/plugin.json"

    /** Prefix of the jar payload directory. */
    public const val JvmPayloadPrefix: String = "classes/jvm/"

    /** Prefix of the dex payload directory. */
    public const val AndroidPayloadPrefix: String = "classes/android/"
}

/**
 * The engine's plugin contract version, as of ADR-018.
 *
 * A manifest states the version it was built against; a host compares it with this constant and
 * refuses a mismatch naming both numbers. An `apiVersion` that quietly matched everything would
 * make the field decorative, which is the debt D35 is about.
 */
public object PluginApiVersion {
    /** The version this engine implements. */
    public const val Supported: Int = 1
}

/**
 * A plugin's entry points, named so a host with a classloader can find them (ADR-018).
 *
 * `schema` is a fully-qualified class name implementing `SchemaContribution`. `runtime` is
 * `FQCN#method` because D35's runtime half is a builder-plus-extension — a function, not an
 * interface — so a name is the only thing that can be recorded for it.
 */
@Serializable
public data class PluginEntryPoints(

    /** The `SchemaContribution` implementation, or null when the plugin contributes no schema. */
    public val schema: String? = null,

    /** The runtime registration hook, as `FQCN#method`, or null when there is none. */
    public val runtime: String? = null,
)

/**
 * The manifest inside every `.lutterplugin` container (ADR-018).
 *
 * One record, one identity: `id`, `version`, `apiVersion` and `dependsOn` are the same four fields
 * a plugin's descriptor carries in `:engine:schema`, and a loaded manifest becomes that descriptor.
 * `build` and `entryPoints` are facts only packaging knows, so they live here and not on the
 * descriptor.
 */
@Serializable
public data class PluginManifest(

    /** Which plugin. Namespaced, like every other id in the model. */
    public val id: PluginId,

    /** The plugin's own version. Compared by exact equality with a requirement (ADR-018). */
    public val version: String,

    /** The engine's plugin contract version this build was compiled against. */
    public val apiVersion: Int,

    /** Which payloads this container must contain. */
    public val build: PluginBuild,

    /** The plugins this one needs installed first. */
    public val dependsOn: List<PluginRequirement> = emptyList(),

    /** Where a host finds the code. */
    public val entryPoints: PluginEntryPoints = PluginEntryPoints(),
)

/**
 * A reason a container is not loadable, as a code a host can report (ADR-018; §27.2).
 *
 * An enum rather than a free-form string: the set is closed by the format, each entry has a
 * positive and a negative test, and a host that meets a new problem must add a code here rather
 * than inventing a message at the call site.
 */
public enum class PluginManifestProblem(

    /** The diagnostic code, in the same dotted shape every other code uses. */
    public val code: String,
) {
    /** The manifest's `apiVersion` is not [PluginApiVersion.Supported]. */
    ApiVersionMismatch("plugin.api_version_mismatch"),

    /** The declared build needs a payload kind the container does not contain. */
    PayloadMissing("plugin.payload_missing"),

    /** Neither a schema nor a runtime entry point is named: nothing could be loaded. */
    EntryPointMissing("plugin.manifest_invalid"),
}

/**
 * Everything wrong with this manifest given the payload kinds the container actually contains.
 *
 * Pure by construction: presence is passed in rather than read from a file, so the rule that a
 * declared build must be backed by real payloads is testable in `commonTest` — no ZIP, no
 * classloader, no platform. A host calls this **before** touching a payload, which is the whole
 * point of verifying rather than assuming (ADR-018).
 */
public fun PluginManifest.problems(present: Set<PluginPayload>): List<PluginManifestProblem> {
    val problems: MutableList<PluginManifestProblem> = mutableListOf()
    if (apiVersion != PluginApiVersion.Supported) problems += PluginManifestProblem.ApiVersionMismatch
    if (!present.containsAll(build.requiredPayloads())) problems += PluginManifestProblem.PayloadMissing
    if (entryPoints.schema == null && entryPoints.runtime == null) {
        problems += PluginManifestProblem.EntryPointMissing
    }
    return problems
}
