package dev.rotalex.lutter.androidapp

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.LooperMode

/**
 * The activity's own wiring: its private workspace, and what a recreated activity reads.
 *
 * ### Why a recreation, and not just a launch
 *
 * Before this host saved, turning the phone rebuilt the session from the bundled asset and threw
 * away every edit. The bug looked like a rendering glitch and was data loss. This test is the one
 * that would have caught it: it changes the bytes on disk behind the running activity, recreates
 * the activity the way a rotation does, and asserts the screen shows the **new** document. A host
 * that read the asset again would pass a launch test and fail this one.
 *
 * `recreate()` is the platform's own, so what this exercises is the real `onCreate` → open the
 * workspace path, not a test-only shortcut.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityWorkspaceTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val filesDir: File
        get() = RuntimeEnvironment.getApplication().filesDir

    @Test
    fun the_activity_creates_its_working_copy_in_private_storage() {
        awaitFirstSave()

        assertTrue(
            File(filesDir, "document.json").isFile,
            "the status bar says it saved but there is no working copy: a host that only read " +
                "the asset writes nothing",
        )
    }

    @Test
    fun a_recreated_activity_reads_the_working_copy_rather_than_the_asset() {
        val document = awaitFirstSave()
        markTheLeafText(document, MARKER)

        compose.activityRule.scenario.recreate()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText(MARKER)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Waits for the first save and returns the working copy.
     *
     * The wait is on the **status bar**, not on the file, because the file appears before the
     * first autosave and a test that raced it would rewrite the document a moment later. "Saved"
     * on screen means the write finished, which is exactly the moment the disk is ours to change.
     */
    private fun awaitFirstSave(): File {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText("Saved", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        return File(filesDir, "document.json")
    }

    /** Rewrites the first `m3.Text` leaf's text, on disk, to [text]. */
    private fun markTheLeafText(document: File, text: String) {
        val decoded: UiDocument = JsonDocumentCodec.decode(document.readBytes()).document
        val leaf: NodeId? = decoded.nodes.ids().firstOrNull {
            decoded.nodes.require(it).type.value == "m3.Text"
        }
        val node = decoded.nodes.require(assertNotNull(leaf, "the fixture has no 'm3.Text' leaf"))
        val marked = decoded.copy(
            nodes = decoded.nodes.with(
                node.copy(props = node.props + (TEXT to PropertyValue.Const(Value.Str(text)))),
            ),
        )

        runBlocking { AtomicFileStorage().write(document.path, JsonDocumentCodec.encode(marked)) }
    }

    private companion object {
        /** A word the bundled fixture cannot contain, so a match cannot come from the asset. */
        const val MARKER: String = "Survived a rotation"

        const val TIMEOUT_MS: Long = 10_000

        val TEXT: PropertyKey = PropertyKey("text")
    }
}
