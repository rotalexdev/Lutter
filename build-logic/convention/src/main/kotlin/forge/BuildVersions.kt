package forge

import org.gradle.api.Project

/**
 * The versions a convention plugin needs, read from the shared catalog.
 *
 * A precompiled script plugin cannot use version-catalog accessors. Gradle extracts its
 * `plugins` block into a standalone file and compiles that separately, and the accessors do
 * not exist there either — which is the whole of the "Unresolved reference 'libs'" failure
 * this project hit on its first CI run.
 *
 * The root build therefore reads the values from `rootLibs` and publishes them as project
 * extra properties, and these accessors are the other end of that hand-off. The catalog is
 * still the only place a version is written; this is plumbing, not a second source. This is
 * the same `VersionCatalogsExtension` route the Rotalex convention plugins use.
 *
 * `extensions.getExtraProperties()` rather than the `extra` shorthand: `extra` is a
 * Kotlin-DSL extension property that only exists inside a `.gradle.kts` script, and this is
 * a plain `.kt`.
 *
 * Every property is set in the root `build.gradle.kts`, which runs before any child project
 * is evaluated, so a convention plugin never has to guard against a missing value.
 */

/**
 * The one JDK number in this build.
 *
 * It is `jvmTarget` from the shared catalog, the same key the Rotalex convention plugins
 * read, and it is applied to both compilers: Java's source/target compatibility and Kotlin's
 * jvmTarget.
 *
 * There is deliberately no separate toolchain JDK. Two numbers is how you get "Inconsistent
 * JVM-target compatibility detected for tasks 'compileTestJava' (21) and 'compileTestKotlin'
 * (17)": the toolchain supplies one, the catalog supplies the other, and a Java compilation
 * with no explicit target silently falls back to the toolchain. One value applied to both
 * compilers cannot disagree with itself. The JDK that *runs* Gradle is pinned in CI through
 * `setup-java`, which is where that belongs.
 */
internal val Project.forgeJvmTarget: String
    get() = extensions.getExtraProperties().get("forge.jvmTarget") as String

internal val Project.forgeAndroidCompileSdk: Int
    get() = extensions.getExtraProperties().get("forge.androidCompileSdk") as Int

internal val Project.forgeAndroidMinSdk: Int
    get() = extensions.getExtraProperties().get("forge.androidMinSdk") as Int
