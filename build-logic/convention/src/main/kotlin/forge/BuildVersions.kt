package forge

import org.gradle.api.Project

/**
 * The build settings a convention plugin needs.
 *
 * These are read with `providers.gradleProperty(...)` from `gradle.properties`, which is
 * where the build declares them. A missing property fails loudly with Gradle's own message
 * naming the key, rather than as a null cast somewhere inside a plugin.
 *
 * A precompiled script plugin cannot use version-catalog accessors: Gradle extracts its
 * `plugins` block into a standalone file and compiles that separately, and the accessors do
 * not exist there either. That is the whole of the "Unresolved reference 'libs'" failure this
 * project hit on its first CI run. Dependency coordinates are unaffected — those are named
 * with `findLibrary` / `findVersion` through the `VersionCatalogsExtension`, which works in
 * any script and is the route the Rotalex convention plugins use.
 */
internal val Project.forgeJvmTarget: String
    get() = providers.gradleProperty("forge.jvmTarget").get()

internal val Project.forgeAndroidCompileSdk: Int
    get() = providers.gradleProperty("forge.androidCompileSdk").get().toInt()

internal val Project.forgeAndroidMinSdk: Int
    get() = providers.gradleProperty("forge.androidMinSdk").get().toInt()
