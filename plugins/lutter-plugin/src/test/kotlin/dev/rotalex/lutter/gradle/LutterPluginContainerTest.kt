package dev.rotalex.lutter.gradle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The packager, driven the way a plugin author drives it: a real Gradle build, a real task, a real
 * file on disk (phase 16 S9).
 *
 * TestKit rather than a unit test of the task object, because two of the three claims are about the
 * *build*: that the plugin applies by id from an included build, and that a build asking for
 * `android-only` or `full` fails with the reason a reader can act on. A unit test could assert the
 * refusal by calling the action directly, but it could not tell whether a plugin project ever
 * reaches that action.
 *
 * The container's bytes are read back with `ZipFile` and the manifest with a plain JSON parser — the
 * engine's own reader is deliberately not on this build's classpath, and `PluginContainerRoundTrip`
 * in `:integration:fixture-plugin` is where the two are compared.
 */
class LutterPluginContainerTest {

    @TempDir
    lateinit var dir: Path

    private fun project(build: String, payloadBytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File {
        val root = dir.resolve("project-${build.replace('-', '_')}").toFile().apply { mkdirs() }
        root.resolve("payload.jar").writeBytes(payloadBytes)
        // A settings file, not merely tolerated: Gradle 9 removed the no-settings-file build, and
        // a fixture that relied on it would fail for a reason unrelated to what it tests.
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"plugin-under-test\"\n")
        root.resolve("build.gradle.kts").writeText(
            """
            plugins { id("dev.rotalex.lutter.plugin") }

            lutterPlugin {
                id.set("forge.fixture")
                version.set("1.0.0")
                apiVersion.set(1)
                pluginBuild.set("$build")
                schemaEntryPoint.set("dev.rotalex.lutter.fixture.plugin.FixturePlugin")
                payload.from(layout.projectDirectory.file("payload.jar"))
            }
            """.trimIndent(),
        )
        return root
    }

    private fun run(root: File): BuildResult =
        GradleRunner.create()
            .withProjectDir(root)
            .withPluginClasspath()
            .withArguments("lutterPluginContainer", "--stacktrace")
            .build()

    private fun container(root: File): File = root.resolve("build/lutter-plugin/forge.fixture-1.0.0.lutterplugin")

    @Test
    fun `a desktop-only plugin becomes a container with a manifest and one jar`() {
        val root = project("desktop-only")
        val result = run(root)

        assertEquals(TaskOutcome.SUCCESS, result.task(":lutterPluginContainer")?.outcome)
        val entries = ZipFile(container(root)).use { zip ->
            zip.entries().toList().map { it.name to zip.getInputStream(it).readBytes() }
        }

        assertEquals(
            listOf("META-INF/lutter/plugin.json", "classes/jvm/forge.fixture.jar"),
            entries.map { it.first },
        )
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), entries[1].second.toList())
    }

    @Test
    fun `the manifest carries the identity, the build and the entry point`() {
        val root = project("desktop-only")
        run(root)

        val manifest = ZipFile(container(root)).use { zip ->
            val entry = zip.getEntry("META-INF/lutter/plugin.json")
            Json.parseToJsonElement(zip.getInputStream(entry).readBytes().decodeToString()).jsonObject
        }

        assertEquals("forge.fixture", manifest["id"]?.jsonPrimitive?.content)
        assertEquals("1.0.0", manifest["version"]?.jsonPrimitive?.content)
        assertEquals("1", manifest["apiVersion"]?.jsonPrimitive?.content)
        assertEquals("desktop-only", manifest["build"]?.jsonPrimitive?.content)
        assertEquals(
            "dev.rotalex.lutter.fixture.plugin.FixturePlugin",
            manifest["entryPoints"]?.jsonObject?.get("schema")?.jsonPrimitive?.content,
        )
        assertTrue(manifest["dependsOn"]!!.jsonArray.isEmpty(), "an undeclared dependency list")
    }

    /** S9's refusal, and the reason has to be in the message: a caller reads the log, not a stack. */
    @Test
    fun `android-only is refused with the reason`() {
        val result = GradleRunner.create()
            .withProjectDir(project("android-only"))
            .withPluginClasspath()
            .withArguments("lutterPluginContainer", "--stacktrace")
            .buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":lutterPluginContainer")?.outcome)
        assertTrue(
            result.output.contains("a dex needs a toolchain this repository does not carry"),
            "the refusal must say why, not only that it refused",
        )
    }

    @Test
    fun `full is refused with the reason`() {
        val result = GradleRunner.create()
            .withProjectDir(project("full"))
            .withPluginClasspath()
            .withArguments("lutterPluginContainer", "--stacktrace")
            .buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":lutterPluginContainer")?.outcome)
        assertTrue(
            result.output.contains("would declare a build its payloads do not back"),
            "the refusal must say why, not only that it refused",
        )
    }

    @Test
    fun `the same inputs produce the same bytes`() {
        val root = project("desktop-only", payloadBytes = byteArrayOf(9, 8, 7))
        run(root)
        val first = container(root).readBytes()
        container(root).delete()
        run(root)

        assertEquals(first.toList(), container(root).readBytes().toList())
    }
}
