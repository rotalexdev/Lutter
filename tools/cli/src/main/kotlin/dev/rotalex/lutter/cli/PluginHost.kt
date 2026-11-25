package dev.rotalex.lutter.cli

import dev.rotalex.lutter.model.plugin.PluginBuild
import dev.rotalex.lutter.model.plugin.PluginContainerLayout
import dev.rotalex.lutter.model.plugin.PluginManifest
import dev.rotalex.lutter.model.plugin.PluginPayload
import dev.rotalex.lutter.plugin.container.PluginContainer
import dev.rotalex.lutter.plugin.container.PluginContainerException
import dev.rotalex.lutter.plugin.container.PluginContainerReader
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.plugin.SchemaContribution
import dev.rotalex.lutter.schema.types.TypeSpec
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile
import kotlin.reflect.KClass

/**
 * The host half of ADR-018: the CLI reads containers and opens the classloader, and no
 * `:engine:*` module ever does (phase 16 S8).
 *
 * ### What "host" means here, precisely
 *
 * `:tools:plugin-container` reads the format and verifies the manifest against the payloads it
 * carries, and it deliberately never names `ClassLoader`. This object is the one place in the
 * repository that does. That split is the whole of ADR-018: a document, a schema and a generator
 * stay plain data and plain functions, so a plugin can be inspected, refused or packaged by
 * something that cannot execute it.
 *
 * ### The three refusals before a class is loaded
 *
 * A container is read and checked first, then this host asks whether *it* can execute what the
 * container carries: a jar payload exists, and a schema entry point is named. A dex-only
 * container is refused with the build it declares, rather than with a missing-jar message that
 * would read like a corrupt file. Only then is the jar extracted and a class loaded — three
 * facts established in the order their consequences matter.
 */
internal object PluginHost {

    /** A container this host read and a contribution it loaded from it. */
    class Loaded(
        /** The manifest, already verified against the payloads the container carries. */
        val manifest: PluginManifest,

        /** The contribution, bound to the spec types this CLI's schema uses. */
        val contribution: SchemaContribution<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
    )

    /**
     * Every container in [files], in the order given, or a [CliFailure] naming the first one that
     * cannot be loaded by this host.
     *
     * @param command the command's name, so a refusal reads `forge check: …`.
     */
    fun load(files: List<File>, command: String): List<Loaded> = files.map { loadOne(it, command) }

    /**
     * One container. Public within the module because a test loads a single file, and because
     * `plugin-loading` is one operation whose loop is not the interesting part.
     */
    fun loadOne(file: File, command: String): Loaded {
        val container: PluginContainer = try {
            PluginContainerReader.read(file)
        } catch (failure: PluginContainerException) {
            throw CliFailure(
                ExitCode.Unusable,
                "forge $command: cannot load plugin '${file.path}': ${failure.message} " +
                    "(${failure.codes.joinToString()})",
            )
        }

        val jvmPayloads = container.payloadsFor(PluginPayload.JvmJar)
        if (jvmPayloads.isEmpty()) {
            // The container itself is valid — its build and payloads agree — and it is still not
            // loadable here. Saying which build it declares tells the reader what to ask for.
            throw CliFailure(
                ExitCode.Unusable,
                "forge $command: plugin '${container.manifest.id}' is " +
                    "${container.manifest.build.spelling()} and carries no jar; this host executes a jar " +
                    "and can only load a desktop-only (or full) container",
            )
        }
        if (jvmPayloads.size > 1) {
            throw CliFailure(
                ExitCode.Unusable,
                "forge $command: plugin '${container.manifest.id}' carries ${jvmPayloads.size} jars " +
                    "(${jvmPayloads.joinToString()}); a host cannot choose one for the author",
            )
        }

        val entryPoint = container.manifest.entryPoints.schema
            ?: throw CliFailure(
                ExitCode.Unusable,
                "forge $command: plugin '${container.manifest.id}' names no schema entry point; " +
                    "this host installs a SchemaContribution and has nothing else to load",
            )

        val contribution = instantiate(entryPoint, container, file, command)
        return Loaded(container.manifest, contribution)
    }

    /**
     * The contribution behind [entryPoint]: its class, its `INSTANCE` field when it is an
     * `object`, a no-argument constructor otherwise.
     *
     * `INSTANCE` rather than `KClass.objectInstance` because that property lives in
     * `kotlin-reflect`, which this CLI does not ship — so a loader that needed it would work in a
     * test and fail in the artifact. A `Jar`-per-plugin classloader, whose parent is this class's
     * own, is what lets a plugin's `:engine:schema` types be the same classes the host compares
     * against.
     */
    private fun instantiate(
        entryPoint: String,
        container: PluginContainer,
        file: File,
        command: String,
    ): SchemaContribution<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
        val jar = container.payloadsFor(PluginPayload.JvmJar).single()
        val extracted = extract(container, file, jar, command)
        val loader = URLClassLoader(arrayOf(extracted.toURI().toURL()), PluginHost::class.java.classLoader)
        val type: KClass<*> = try {
            Class.forName(entryPoint, true, loader).kotlin
        } catch (failure: ClassNotFoundException) {
            throw CliFailure(
                ExitCode.Unusable,
                "forge $command: plugin '${container.manifest.id}' names '$entryPoint', " +
                    "which is not in '$jar': ${failure.message}",
            )
        } catch (failure: LinkageError) {
            throw CliFailure(
                ExitCode.Unusable,
                "forge $command: plugin '${container.manifest.id}' class '$entryPoint' cannot be " +
                    "linked: ${failure.message}",
            )
        }

        val singleton: Any? = try {
            type.java.getField("INSTANCE").get(null)
        } catch (notAnObject: NoSuchFieldException) {
            null
        }
        val instance: Any = singleton ?: try {
            type.java.getDeclaredConstructor().newInstance()
        } catch (failure: ReflectiveOperationException) {
            throw CliFailure(
                ExitCode.Unusable,
                "forge $command: plugin '${container.manifest.id}' entry point '$entryPoint' is " +
                    "neither an object nor constructible with no arguments: ${failure.message}",
            )
        }

        if (instance !is SchemaContribution<*, *, *, *, *>) {
            throw CliFailure(
                ExitCode.Unusable,
                "forge $command: plugin '${container.manifest.id}' entry point '$entryPoint' is " +
                    "not a SchemaContribution; a manifest's schema entry point must be one",
            )
        }
        @Suppress("UNCHECKED_CAST")
        return instance as SchemaContribution<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>
    }

    /**
     * The jar inside [container], written to a temporary file and marked for deletion at exit.
     *
     * A URL needs a file or a directory, and a classloader over a `byte[]` would mean a custom
     * loader with a map of names to bytes — a second, unreviewed implementation of what the JDK
     * already does. `deleteOnExit` rather than a `finally`: the loader keeps reading the file while
     * the process runs, so the file has to outlive this call.
     */
    private fun extract(container: PluginContainer, file: File, jar: String, command: String): File {
        val bytes = readEntry(file, jar, container, command)
        val extracted = File.createTempFile("lutter-plugin-", ".jar")
        extracted.writeBytes(bytes)
        extracted.deleteOnExit()
        return extracted
    }

    private fun readEntry(file: File, jar: String, container: PluginContainer, command: String): ByteArray =
        try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("${PluginContainerLayout.JvmPayloadPrefix}$jar")
                    ?: throw CliFailure(
                        ExitCode.Unusable,
                        "forge $command: plugin '${container.manifest.id}' lists '$jar' and the " +
                            "container does not carry it",
                    )
                zip.getInputStream(entry).use { it.readBytes() }
            }
        } catch (failure: java.io.IOException) {
            throw CliFailure(
                ExitCode.Unusable,
                "forge $command: cannot read plugin '${container.manifest.id}' payload '$jar': ${failure.message}",
            )
        }

    /** One line per loaded plugin, for the command that installed it. */
    fun describe(loaded: Loaded): String =
        "plugin ${loaded.manifest.id} ${loaded.manifest.version} " +
            "(api ${loaded.manifest.apiVersion}, ${loaded.manifest.build.spelling()})"

    /** Installs every loaded contribution into [builder], in the order the caller loaded them. */
    fun install(
        builder: SchemaBuilder<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
        loaded: List<Loaded>,
    ): Unit = loaded.forEach { builder.install(it.contribution) }

    private fun PluginBuild.spelling(): String = when (this) {
        PluginBuild.Full -> "full"
        PluginBuild.AndroidOnly -> "android-only"
        PluginBuild.DesktopOnly -> "desktop-only"
    }
}
