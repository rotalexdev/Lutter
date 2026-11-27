package dev.rotalex.lutter.androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.EditorSessionRecord
import dev.rotalex.lutter.editor.EditorWorkspace
import dev.rotalex.lutter.editor.Opened
import dev.rotalex.lutter.editor.WorkspaceSaver
import dev.rotalex.lutter.editor.open
import dev.rotalex.lutter.editor.settings.EditorSettings
import dev.rotalex.lutter.editor.settings.SettingsStore
import dev.rotalex.lutter.editor.ui.CrashReportScreen
import dev.rotalex.lutter.editor.ui.EditorShell
import dev.rotalex.lutter.editor.ui.EditorShellSave
import dev.rotalex.lutter.editor.ui.theme.LutterTheme
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
 * The Android entry point (PLAN §32 Phase 17 P6): a window around the shared editor frame.
 *
 * The frame itself — app bar, the two screens as tabs, the palette and the inspector as sheets
 * at this width — is `EditorShell` in `:app:editor`, the same composable the desktop window
 * draws. What this activity owns is the document's private directory, the session's lifetime,
 * and the insets; the arrangement is not a second implementation to keep in step.
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
        // The window is drawn edge to edge at every SDK this app targets, so the app owns the
        // insets: the palette used to render *under* the status bar, which is why its first
        // line arrived mixed with the clock, and the status bar at the bottom sat under the
        // gesture handle. `EditorShell` applies them; the surface behind it reaches the edge.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val schema = BuiltinEngine.schema()
        val runtime = BuiltinEngine.runtime(schema)

        // Private storage: no permission, no shared directory, and removed with the app. The
        // document is a working copy, so nobody expects to find it in their Downloads folder.
        val documentLocation = File(filesDir, DOCUMENT_FILE).path
        val recordLocation = File(filesDir, SESSION_FILE).path

        // One store for the process, created before the first frame: the theme is read from it
        // at the root, so a store built inside the composition would make the window start in
        // the wrong scheme and correct itself a frame later.
        val settings: SettingsStore = AndroidSettings.inDirectory(filesDir)

        // Installed before the first frame, so a crash during the first composition is recorded
        // too. The file is read here as well: the launch after a crash shows the trace instead of
        // the editor, and one tap on "Continue" clears it.
        val crashLog = File(filesDir, CRASH_FILE)
        installCrashLog(crashLog)

        setContent {
            val preferences: EditorSettings by settings.settings.collectAsState()
            var crash: String? by remember { mutableStateOf(readCrashLog(crashLog)) }
            LutterTheme(theme = preferences.theme) {
                Surface(Modifier.fillMaxSize()) {
                    val trace: String? = crash
                    if (trace != null) {
                        CrashReportScreen(
                            trace = trace,
                            onContinue = {
                                clearCrashLog(crashLog)
                                crash = null
                            },
                        )
                    } else {
                        EditorHost(
                            runtime = runtime,
                            documentLocation = documentLocation,
                            recordLocation = recordLocation,
                            settings = settings,
                            seed = { readDocument() },
                        )
                    }
                }
            }
        }
    }

    /**
     * The bundled starter, decoded — the **seed** only.
     *
     * It is the same file the desktop app opens on, packaged straight out of that module's
     * resources, so a failure here means the packaging changed rather than the document. What it
     * seeds is the first run and nothing else: after that the workspace holds the document, and
     * this asset is never consulted again.
     */
    private fun readDocument(): UiDocument {
        val bytes = assets.open(DOCUMENT_ASSET).use { it.readBytes() }
        return JsonDocumentCodec.decode(bytes).document
    }

    private companion object {
        const val DOCUMENT_ASSET: String = "default-document.json"

        /** The working copy inside [filesDir], and the record beside it. */
        const val DOCUMENT_FILE: String = "document.json"
        const val SESSION_FILE: String = "session.json"

        /** The trace of the last uncaught exception, read on the launch after it. */
        const val CRASH_FILE: String = "crash.txt"
    }
}

/**
 * The activity's content: the workspace as it is on disk, the session over it, and the frame.
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
    settings: SettingsStore,
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
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
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
    // Keyed on the workspace it writes to, so a reopened document gets a saver for the new
    // directory rather than one that keeps answering for the old one.
    val save: EditorShellSave = remember(current, saver) {
        EditorShellSave(
            saver = saver,
            location = current.workspace.documentLocation,
            notice = current.notice,
        )
    }

    EditorShell(
        session = session,
        preview = preview,
        runtime = runtime,
        save = save,
        modifier = Modifier.fillMaxSize(),
        settings = settings,
    )
}
