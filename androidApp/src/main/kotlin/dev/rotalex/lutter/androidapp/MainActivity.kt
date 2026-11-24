package dev.rotalex.lutter.androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.PreviewState
import dev.rotalex.lutter.editor.ui.CanvasPane
import dev.rotalex.lutter.editor.ui.FollowPreview
import dev.rotalex.lutter.editor.ui.InspectorPane
import dev.rotalex.lutter.editor.ui.PalettePane
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.runtime.UiRuntime
import dev.rotalex.lutter.serialization.JsonDocumentCodec

/**
 * The Android entry point (PLAN §32 Phase 17 P6): the same three panes as the desktop host, in a
 * narrow column instead of a row.
 *
 * The panes come from `:app:editor` — one implementation, two arrangements. This activity owns
 * the frame, the document and the session's lifetime; nothing here draws a pane of its own.
 */
public class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val schema = BuiltinEngine.schema()
        val session = EditorSession.open(readDocument(), schema)
        val preview = EditorPreview(session, analyze = { BuiltinEngine.analyze(schema, it) })
        val runtime = BuiltinEngine.runtime(schema)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AndroidEditorScreen(session, preview, runtime)
                }
            }
        }
    }

    /**
     * The bundled fixture, decoded.
     *
     * The asset is the repository's `fixtures-navigating` directory packaged by name, so a
     * failure here means the packaging changed rather than the document — which is the failure
     * worth seeing loudly.
     */
    private fun readDocument(): UiDocument {
        val bytes = assets.open(DOCUMENT_ASSET).use { it.readBytes() }
        return JsonDocumentCodec.decode(bytes).document
    }

    private companion object {
        const val DOCUMENT_ASSET: String = "button_navigate.json"
    }
}

/** The narrow arrangement: palette, canvas, inspector, stacked. */
@Composable
public fun AndroidEditorScreen(
    session: EditorSession,
    preview: EditorPreview,
    runtime: UiRuntime,
    modifier: Modifier = Modifier,
) {
    val rendered: PreviewState by preview.state.collectAsState()
    val selection by session.selection.collectAsState()

    FollowPreview(preview)

    Column(modifier.fillMaxSize()) {
        PalettePane(
            sections = session.palette(),
            onInsert = { entry ->
                val target = session.dropTargets(entry.type).firstOrNull()
                if (target != null) session.insert(entry.type, target)
            },
            modifier = Modifier.fillMaxWidth().height(160.dp).testTag("palette"),
        )
        CanvasPane(
            runtime = runtime,
            state = rendered,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("canvas"),
        )
        InspectorPane(
            session = session,
            selection = selection,
            modifier = Modifier.fillMaxWidth().height(200.dp).testTag("inspector"),
        )
    }
}
