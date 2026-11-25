package dev.rotalex.lutter.androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.editor.Autosave
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.EditorSessionRecord
import dev.rotalex.lutter.editor.EditorWorkspace
import dev.rotalex.lutter.editor.Opened
import dev.rotalex.lutter.editor.PreviewState
import dev.rotalex.lutter.editor.SaveStatusBar
import dev.rotalex.lutter.editor.WorkspaceSaver
import dev.rotalex.lutter.editor.open
import dev.rotalex.lutter.editor.ui.CanvasPane
import dev.rotalex.lutter.editor.ui.FollowPreview
import dev.rotalex.lutter.editor.ui.InspectorPane
import dev.rotalex.lutter.editor.ui.PalettePane
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.runtime.UiRuntime
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android entry point (PLAN §32 Phase 17 P6): the same three panes as the desktop host, in a
 * narrow column instead of a row.
 *
 * The panes come from `:app:editor` — one implementation, two arrangements — and so does the
 * saving: [EditorWorkspace] decides where the bytes go and [Autosave] decides when, both shared
 * with the desktop window. What this activity owns is the frame, the document's private
 * directory, and the session's lifetime.
 *
 * ### Turning the phone is a reopen, and that is the saving working
 *
 * A configuration change recreates the activity. Before this host saved anything, that rebuilt the
 * session from the bundled asset and threw away every edit — a bug that looks like a rendering
 * glitch and is in fact data loss. Now the activity reads its own workspace on every creation, so
 * a rotation lands on the document as it was last written, selection included: the same path the
 * desktop window takes when it is closed and opened again.
 */
public class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val schema = BuiltinEngine.schema()
        val runtime = BuiltinEngine.runtime(schema)

        // Private storage: no permission, no shared directory, and removed with the app. The
        // document is a working copy, so nobody expects to find it in their Downloads folder.
        val documentLocation = File(filesDir, DOCUMENT_FILE).path
        val recordLocation = File(filesDir, SESSION_FILE).path

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    EditorHost(
                        runtime = runtime,
                        documentLocation = documentLocation,
                        recordLocation = recordLocation,
                        seed = { readDocument() },
                    )
                }
            }
        }
    }

    /**
     * The bundled fixture, decoded — the **seed** only.
     *
     * It is read from the repository's own `fixtures-navigating` directory, packaged by name, so a
     * failure here means the packaging changed rather than the document. What it seeds is the
     * first run and nothing else: after that the workspace holds the document, and this asset is
     * never consulted again.
     */
    private fun readDocument(): UiDocument {
        val bytes = assets.open(DOCUMENT_ASSET).use { it.readBytes() }
        return JsonDocumentCodec.decode(bytes).document
    }

    private companion object {
        const val DOCUMENT_ASSET: String = "button_navigate.json"

        /** The working copy inside [filesDir], and the record beside it. */
        const val DOCUMENT_FILE: String = "document.json"
        const val SESSION_FILE: String = "session.json"
    }
}

/**
 * The activity's content: the workspace as it is on disk, the session over it, and the autosave.
 *
 * A composable rather than fields on the activity because a configuration change must not find a
 * half-built editor: the state it reads is [Opened], and until that exists the screen says so
 * instead of showing a document nobody is editing.
 *
 * The read happens off the main thread — it is disk I/O before the first frame — which is why the
 * screen has a loading branch at all.
 */
@Composable
private fun EditorHost(
    runtime: UiRuntime,
    documentLocation: String,
    recordLocation: String,
    seed: () -> UiDocument,
) {
    // Built here rather than passed in: `analyze` takes the concrete `Schema`, and a parameter
    // typed as the `SchemaView` supertype would erase exactly the thing it is needed for.
    val schema = remember { BuiltinEngine.schema() }

    var opened: Opened? by remember { mutableStateOf(null) }
    var failure: String? by remember { mutableStateOf(null) }

    LaunchedEffect(documentLocation, recordLocation) {
        opened = withContext(Dispatchers.IO) {
            try {
                EditorWorkspace.open(
                    documentLocation = documentLocation,
                    recordLocation = recordLocation,
                    seed = seed(),
                    storage = AtomicFileStorage(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                failure = refused.message ?: (refused::class.simpleName ?: "unknown failure")
                null
            }
        }
    }

    val current: Opened? = opened
    if (current == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = failure?.let { "The editor could not open its document: $it" }
                    ?: "Opening the document…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val session: EditorSession = remember(current) {
        EditorSession.open(
            document = current.document,
            schema = schema,
            record = current.record ?: EditorSessionRecord(documentLocation),
        )
    }
    val preview: EditorPreview = remember(session) {
        EditorPreview(session, analyze = { BuiltinEngine.analyze(schema, it) })
    }
    val saver: WorkspaceSaver = remember(current) {
        WorkspaceSaver(current.workspace) {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Autosave(session = session, saver = saver)
        AndroidEditorScreen(
            session = session,
            preview = preview,
            runtime = runtime,
            modifier = Modifier.weight(1f),
        )
        SaveStatusBar(
            location = current.workspace.documentLocation,
            state = saver.state,
            notice = current.notice,
        )
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
