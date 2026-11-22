package dev.rotalex.lutter.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * What a plugin project declares: its identity, the build it ships, and where it keeps the code.
 *
 * Every field is a Gradle `Property` rather than a plain field, so a build script can set it with a
 * lazy provider (`version.set(providers.gradleProperty("pluginVersion"))`) without this extension
 * having to know the answer at configuration time. `payload` is a file collection with no type of
 * its own because the packager does not care whether it came from a `jar` task, an `assemble` of a
 * Java module, or a file someone already built.
 */
public abstract class LutterPluginExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * The plugin's id, in the same namespaced shape a manifest carries (`forge.fixture`).
     *
     * Validated when the container is written, not here: an `install` in a script that only lists
     * tasks should not fail because an id is not set yet.
     */
    public val id: Property<String> = objects.property(String::class.java)

    /** The plugin's own version. A document compares it by exact string equality (ADR-018). */
    public val version: Property<String> = objects.property(String::class.java)

    /** The engine plugin API this plugin was compiled against; defaults to the current one. */
    public val apiVersion: Property<Int> = objects.property(Int::class.java).convention(1)

    /**
     * The build this container ships.
     *
     * Named `pluginBuild` rather than `build`: inside a build script, `build` is already the
     * project's convention property and the `build` task, and a DSL that shadowed either would
     * produce the kind of ambiguity a reader has to resolve from memory. The manifest field it
     * becomes is still `build`.
     *
     * Defaults to `desktop-only`, which is the only value this packager accepts. The others are
     * named in the refusal rather than silently mapped, because a build name that does not match
     * what the container carries is the exact lie `PluginManifest.problems` exists to catch.
     */
    public val pluginBuild: Property<String> = objects.property(String::class.java).convention("desktop-only")

    /** The `SchemaContribution` fully-qualified class name a host loads, if this plugin has one. */
    public val schemaEntryPoint: Property<String> = objects.property(String::class.java)

    /** The runtime registration hook as `FQCN#method`, if this plugin has one. */
    public val runtimeEntryPoint: Property<String> = objects.property(String::class.java)

    /**
     * Plugins this one needs installed first, as id → version.
     *
     * A map rather than a list of formatted strings: the manifest carries two fields per entry and
     * a `"id:version"` string would be a grammar this build has to parse and validate, which is a
     * second version syntax beside the one ADR-018 says does not exist.
     */
    public val dependsOn: MapProperty<String, String> = objects.mapProperty(String::class.java, String::class.java)

    /** The files that become the container's `classes/jvm/` payload. Exactly one. */
    public val payload: ConfigurableFileCollection = objects.fileCollection()

    /** Where the container is written. Defaults to `build/lutter-plugin/<id>-<version>.lutterplugin`. */
    public val outputFile: RegularFileProperty = objects.fileProperty()
}
