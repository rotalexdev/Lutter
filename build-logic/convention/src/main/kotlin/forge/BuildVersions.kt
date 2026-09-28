package forge

import org.gradle.api.Project

/**
 * The versions a convention plugin needs, read from the shared catalog.
 *
 * A precompiled script plugin cannot use version-catalog accessors. Gradle extracts its
 * `plugins` block into a standalone file and compiles that separately, and the accessors do
 * not exist in either context — which is the whole of the "Unresolved reference 'libs'"
 * failure this project hit on its first CI run.
 *
 * The root build therefore reads the values from `rootLibs` and publishes them as project
 * extra properties, and these accessors are the other end of that hand-off. The catalog is
 * still the only place a version is written; this is plumbing, not a second source.
 *
 * `extensions.getExtraProperties()` rather than the `extra` shorthand: `extra` is a
 * Kotlin-DSL extension property that only exists inside a `.gradle.kts` script, and this is
 * a plain `.kt`.
 *
 * Every property is set in the root `build.gradle.kts`, which runs before any child project
 * is evaluated, so a convention plugin never has to guard against a missing value.
 */
internal val Project.forgeJvmTarget: String
    get() = extensions.getExtraProperties().get("forge.jvmTarget") as String

internal val Project.forgeAndroidCompileSdk: Int
    get() = extensions.getExtraProperties().get("forge.androidCompileSdk") as Int

internal val Project.forgeAndroidMinSdk: Int
    get() = extensions.getExtraProperties().get("forge.androidMinSdk") as Int

internal val Project.forgeJavaVersion: Int
    get() = extensions.getExtraProperties().get("forge.javaVersion") as Int
