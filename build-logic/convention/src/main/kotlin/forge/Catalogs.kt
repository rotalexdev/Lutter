package forge

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * The shared catalog, read from a precompiled script plugin.
 *
 * A precompiled script plugin is compiled separately from version-catalog accessor
 * generation, so the `rootLibs` name does not resolve there — in its body any more than in
 * its `plugins` block, which Gradle extracts into a standalone file and compiles alone. CI
 * proved that twice with "Unresolved reference 'rootLibs'".
 *
 * The `VersionCatalogsExtension` is the route that works in any script, and it is how the
 * Rotalex convention plugins read their own versions. Ordinary `.gradle.kts` files — the
 * module build scripts, and `build-logic/convention/build.gradle.kts` — keep using the
 * `rootLibs` accessors by name, because those are ordinary build scripts and the accessors
 * do resolve there.
 */
internal val Project.rootLibs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("rootLibs")

/** The version bound to [alias] in the shared catalog, e.g. `jvmTarget`. */
internal fun Project.catalogVersion(alias: String): String =
    rootLibs.findVersion(alias).get().requiredVersion
