package dev.rotalex.lutter.samples.desktoppreview

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.runtime.BackStackNavigator
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.UiApp
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a human sees in the window, asserted on a headless runner.
 *
 * The frame itself cannot be, so this asserts the content the window hands Compose — [UiApp]
 * over the document [main] loads, through that same loader. Without it the module would compile
 * and prove nothing, which is what a sample in `check` that renders is for.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopPreviewTest {

    @Test
    fun `the window opens on Welcome and Continue reaches Profile`() = runComposeUiTest {
        val schema = BuiltinEngine.schema()
        val decoded = readDocument(DEFAULT_DOCUMENT)
        val document = BuiltinEngine.resolve(schema, decoded)
        val runtime = BuiltinEngine.runtime(schema)
        val navigator = BackStackNavigator(decoded.app.startPage)

        setContent {
            UiApp(runtime, document, RuntimeEnvironment(navigator = navigator))
        }

        // `fetchSemanticsNode` fails when nothing matches, so it is the assertion.
        onNode(hasText("Welcome")).fetchSemanticsNode()
        onNode(hasText("Continue")).performClick()
        // The press dispatches through a coroutine, so the stack moves after the click returns.
        waitForIdle()

        assertEquals(PageId("p_profile"), navigator.current.page)
    }

    /**
     * The packaged app carries its own document, and it is a **copy**.
     *
     * `readDocument` falls back to `/default-document.json` in the jar when [DEFAULT_DOCUMENT]
     * is not on disk, which is what a user who unzipped the app-image somewhere has. That
     * fallback is why the window opens at all outside a Gradle build, and a copy is what makes
     * two sources of truth — which is a thing that has already bitten this repository three
     * times, in a different module.
     *
     * So the copy is pinned. It is compared as **text against text**, not as one decoded
     * document against another: decoding would make this pass when the two files differ in
     * formatting, in key order or in a comment, and those are exactly the differences a
     * maintainer makes when they edit the original. A red here is not a bug to work around; it
     * is a file to re-copy, and the message says which.
     */
    @Test
    fun `the bundled document is the repository fixture, byte for byte`() {
        val onDisk = java.io.File(DEFAULT_DOCUMENT)
        assertTrue(onDisk.isFile, "the repository fixture is missing at ${onDisk.absolutePath}")

        val bundled =
            checkNotNull(javaClass.getResourceAsStream("/default-document.json")) {
                "/default-document.json is not on the test classpath; the packaged app " +
                    "would open on nothing"
            }

        assertEquals(
            onDisk.readText(),
            bundled.use { it.readBytes().decodeToString() },
            "the bundled copy has drifted from ${DEFAULT_DOCUMENT}; re-copy it into " +
                "src/commonMain/resources/default-document.json",
        )
    }

    /**
     * A path that is not there still opens a document, because that is the packaged case.
     *
     * Before this fallback existed, launching the packaged app on a machine with no repository
     * beside it threw, and the jpackage launcher reported it as "Failed to launch JVM" — the
     * last line it prints after the application's own output, and a sentence about the VM that
     * had nothing to do with the VM.
     */
    @Test
    fun `a path that does not exist falls back to the bundled document`() {
        val bundled =
            checkNotNull(javaClass.getResourceAsStream("/default-document.json")) {
                "/default-document.json is not on the test classpath"
            }
        val expected = JsonDocumentCodec.decode(bundled.use { it.readBytes() }).document

        val read = readDocument("no/such/document.json")

        assertEquals(expected.app.startPage, read.app.startPage)
        assertEquals(expected.pages.keys, read.pages.keys)
    }
}
