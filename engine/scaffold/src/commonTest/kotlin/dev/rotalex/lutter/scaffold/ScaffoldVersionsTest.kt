package dev.rotalex.lutter.scaffold

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §32 Phase 14: "the version catalog emitted from the build's own catalog so a generated app
 * never pins a version the engine did not test".
 *
 * Provenance is structural — [ScaffoldVersions] is generated from `rootLibs` at build time, so
 * there is no place for a hand-written number to live. What a test can pin is the shape those
 * numbers must have, and the one invariant the catalog itself must satisfy: the Compose
 * compiler plugin is versioned by the Kotlin release it belongs to, so a catalog that let them
 * drift would generate an app that cannot compile.
 */
class ScaffoldVersionsTest {

    private val pluginVersions: Map<String, String> = mapOf(
        "KOTLIN" to ScaffoldVersions.KOTLIN,
        "AGP" to ScaffoldVersions.AGP,
        "COMPOSE" to ScaffoldVersions.COMPOSE,
    )

    @Test
    fun `every emitted plugin version is a dotted version`() {
        pluginVersions.forEach { (name, value) ->
            assertTrue(
                Regex("""^\d+\.\d+(\.\d+)?(-[A-Za-z0-9.\-]+)?$""").matches(value),
                "$name is '$value', which is not a version a generated project can pin",
            )
        }
    }

    /**
     * The `org.jetbrains.kotlin.plugin.compose` artifact is released with Kotlin, not with
     * Compose Multiplatform. A generated `libs.versions.toml` that pinned two different
     * numbers would fail at the first `:shared:compileKotlin`.
     */
    @Test
    fun `the compose compiler is the one of this kotlin`() {
        assertEquals(ScaffoldVersions.KOTLIN, ScaffoldVersions.COMPOSE_COMPILER)
    }

    @Test
    fun `the sdk levels are integers in a range a device has`() {
        assertTrue(
            ScaffoldVersions.ANDROID_MIN_SDK.toInt() in 21..ScaffoldVersions.ANDROID_COMPILE_SDK.toInt(),
            "minSdk ${ScaffoldVersions.ANDROID_MIN_SDK} is not within 21..${ScaffoldVersions.ANDROID_COMPILE_SDK}",
        )
    }

    @Test
    fun `the jvm target is a version a desktop app can run`() {
        assertTrue(
            ScaffoldVersions.JVM_TARGET.toIntOrNull() in setOf(11, 17, 21),
            "jvmTarget '${ScaffoldVersions.JVM_TARGET}' is not one of the LTS targets this build tests",
        )
    }
}
