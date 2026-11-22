package dev.rotalex.lutter.gradle

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes one `.lutterplugin` container (ADR-018, phase 16 S9).
 *
 * ### Why this file repeats the format
 *
 * The format lives in `:engine:model` (`PluginContainerLayout`) and its writer in
 * `:tools:plugin-container`, and an included build cannot depend on either: the main build applies
 * this plugin, so this build is configured first, and a dependency back into the main build is a
 * cycle. The alternatives were worse — packaging through the CLI puts a JVM launch inside a
 * packaging step, and not packaging at all leaves the plan's own entry point unimplemented.
 *
 * The duplication is therefore deliberate and **checked**, not hoped: `PluginContainerRoundTrip` in
 * `:integration:fixture-plugin` reads this task's output with the real `PluginContainerReader`, so a
 * change to either side that the other does not understand fails that test rather than producing
 * containers a host refuses at load time.
 *
 * ### Determinism
 *
 * Entries are STORED (a plugin jar is already compressed; storing keeps the container's bytes a
 * function of its inputs rather than of the JDK's deflater) with a fixed timestamp and an explicit
 * CRC32, in a fixed order: manifest first, then the payload. Two builds of the same inputs produce
 * byte-identical containers, which is what makes the output cacheable.
 */
@CacheableTask
public abstract class LutterPluginContainer : DefaultTask() {

    /** Which plugin. Becomes the manifest's `id` and the payload's file name. */
    @get:Input
    public abstract val pluginId: Property<String>

    /** The plugin's own version. */
    @get:Input
    public abstract val pluginVersion: Property<String>

    /** The engine plugin API this plugin was compiled against. */
    @get:Input
    public abstract val apiVersion: Property<Int>

    /** The build this container claims. Only `desktop-only` is producible here. */
    @get:Input
    public abstract val build: Property<String>

    /**
     * `SchemaContribution` FQCN, or absent.
     *
     * `@get:Optional` because a plugin may carry only one of the two entry points, and Gradle
     * refuses to run a task with an unset non-optional `@Input` before the action ever sees it.
     * "At least one" is the rule, and it is checked in the action where it can name both fields.
     */
    @get:Input
    @get:Optional
    public abstract val schemaEntryPoint: Property<String>

    /** `FQCN#method` runtime hook, or absent; `@get:Optional` for the same reason as above. */
    @get:Input
    @get:Optional
    public abstract val runtimeEntryPoint: Property<String>

    /**
     * Requirement id → version, in declaration order.
     *
     * Named `requirements` rather than `dependsOn`: `Task` already has a `getDependsOn()` returning
     * a `TaskDependency`, and a property of the same name would collide with it at the JVM
     * signature level rather than shadow it usefully.
     */
    @get:Input
    public abstract val requirements: MapProperty<String, String>

    /** The single file that becomes `classes/jvm/<id>.jar`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val payload: ConfigurableFileCollection

    /** The container to write. */
    @get:OutputFile
    public abstract val outputFile: RegularFileProperty

    @TaskAction
    public fun pack() {
        val id = required("id", pluginId.orNull)
        val version = required("version", pluginVersion.orNull)
        val declaredBuild = required("build", build.orNull)
        val schema = schemaEntryPoint.orNull
        val runtime = runtimeEntryPoint.orNull

        // S9's refusal, before any bytes are written. Producing a dex needs D8/R8, which this
        // repository does not carry; a container that claimed a build its payloads do not back is
        // exactly the failure mode `PluginManifest.problems` exists to catch, so the packager must
        // not be the thing that manufactures it.
        if (declaredBuild != DESKTOP_ONLY) {
            throw GradleException(
                "lutter-plugin packages build '$declaredBuild', but this packager produces only " +
                    "'$DESKTOP_ONLY': producing a dex needs a toolchain this repository does not " +
                    "carry, and a container that ships an empty ${ANDROID_PREFIX} would declare a " +
                    "build its payloads do not back.",
            )
        }
        if (schema == null && runtime == null) {
            throw GradleException(
                "lutter-plugin '$id' names no entry point: set `schemaEntryPoint` or " +
                    "`runtimeEntryPoint`, or a host would have nothing to load.",
            )
        }

        val files = payload.files.sortedBy { it.name }
        if (files.size != 1) {
            throw GradleException(
                "lutter-plugin '$id' expects exactly one payload file, found ${files.size}" +
                    if (files.isEmpty()) "." else ": ${files.joinToString { it.name }}.",
            )
        }

        val manifest = manifestJson(id, version, declaredBuild, schema, runtime).toString()
        val payloadName = "$JVM_PREFIX$id.jar"

        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        ZipOutputStream(out.outputStream()).use { zip ->
            writeStored(zip, MANIFEST_PATH, manifest.encodeToByteArray())
            writeStored(zip, payloadName, files.single().readBytes())
        }
    }

    private fun manifestJson(
        id: String,
        version: String,
        declaredBuild: String,
        schema: String?,
        runtime: String?,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("version", version)
        put("apiVersion", apiVersion.get())
        put("build", declaredBuild)
        put(
            "dependsOn",
            buildJsonArray {
                requirements.get().forEach { (requirementId, requirementVersion) ->
                    add(
                        buildJsonObject {
                            put("id", requirementId)
                            put("version", requirementVersion)
                        },
                    )
                }
            },
        )
        put(
            "entryPoints",
            buildJsonObject {
                if (schema != null) put("schema", schema)
                if (runtime != null) put("runtime", runtime)
            },
        )
    }

    private fun writeStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
            // 1980-01-01, the earliest DOS timestamp: a constant, so the output bytes do not
            // depend on when the build ran.
            time = FIXED_TIME
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun required(name: String, value: String?): String {
        if (value.isNullOrBlank()) {
            throw GradleException("lutter-plugin is missing `$name`; a container without it would be unloadable.")
        }
        return value
    }

    private companion object {
        /**
         * The format's own paths, repeated from `:engine:model`'s `PluginContainerLayout` for the
         * reason the class documentation gives: this build cannot see that module.
         */
        const val MANIFEST_PATH: String = "META-INF/lutter/plugin.json"
        const val JVM_PREFIX: String = "classes/jvm/"
        const val ANDROID_PREFIX: String = "classes/android/"
        const val DESKTOP_ONLY: String = "desktop-only"
        const val FIXED_TIME: Long = 315_532_800_000L
    }
}
