package dev.rotalex.lutter.desktopapp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.editor.Autosave
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.EditorSessionRecord
import dev.rotalex.lutter.editor.EditorWorkspace
import dev.rotalex.lutter.editor.Opened
import dev.rotalex.lutter.editor.SaveStatusBar
import dev.rotalex.lutter.editor.WorkspaceSaver
import dev.rotalex.lutter.editor.open
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import java.io.File
import java.time.LocalTime
import kotlinx.coroutines.runBlocking

/**
 * The document the window opens on when it is given no path: the starter preset.
 *
 * It is the **resource file itself**, not a copy of it — `src/commonMain/resources/
 * default-document.json`, module-relative, which is the working directory under `gradle run` and
 * under `desktopTest`. So the file a developer edits and the file a packaged build opens are the
 * same bytes, and there is nothing to drift: `gradle run` reads it from disk, and the launcher
 * reads the identical bytes out of the jar when the path resolves to nothing.
 *
 * Module-relative is exactly why a packaged build cannot use this path: unpack the app-image in
 * `Downloads`, double-click the launcher, and the working directory is wherever the user put it.
 * [readDocument] answers that with the bundled copy, and a path handed in on the command line
 * still wins over both.
 */
public const val DEFAULT_DOCUMENT: String = "src/commonMain/resources/default-document.json"

/** Where the same file lands inside the jar, for a packaged build with no repository around it. */
private const val BUNDLED_DOCUMENT: String = "/default-document.json"

/**
 * The document at [path], decoded, or the bundled one when [path] is not there.
 *
 * The order is deliberate, and it is the sample's order for the sample's reasons. A path the
 * caller named wins first — that is the `--args` case, and it is how a second document gets
 * looked at. A path that exists wins next, which keeps `gradle run` reading the repository's
 * fixture so an edit to it is visible without rebuilding. Only when neither holds does the
 * bundled copy answer, and that is the packaged case, where it is the only document there is.
 *
 * The refusal names both paths it tried, because the useful question when one of them is wrong
 * is which one was consulted.
 *
 * A `UiDocument` is the classloader handle: this file's facade is `MainKt` on the JVM and has no
 * Kotlin name to reference, while `UiDocument` is already imported and its classloader is the
 * same one either way.
 */
public fun readDocument(path: String): UiDocument {
    val file = File(path)
    if (file.isFile) return JsonDocumentCodec.decode(file.readText().encodeToByteArray()).document

    val bundled: ByteArray? =
        UiDocument::class.java.getResourceAsStream(BUNDLED_DOCUMENT)?.use { it.readBytes() }
    if (bundled != null) return JsonDocumentCodec.decode(bundled).document

    error(
        "No document at '${file.absolutePath}' and no '$BUNDLED_DOCUMENT' in the jar. " +
            "Pass one: LutterEditor <document.json>",
    )
}

/**
 * The window, opened on the document the editor owns.
 *
 * ### Two locations, and which one is used when
 *
 * Naming a file on the command line — `LutterEditor my-document.json` — edits **that file in
 * place**. Naming nothing opens the starter, and the starter is a *seed*: it is copied into the
 * editor's own workspace first (`workspaceDirectory()`), so `gradle run` and a packaged launch
 * both write somewhere a user owns instead of into the repository they may not own at all.
 *
 * One document at a time is the whole of the rule. There is no file picker and no Save As,
 * because both are decisions about where a document lives and this editor has one answer.
 *
 * ### The close handler saves, and why it is blocking
 *
 * [Autosave] writes on every change, but the last keystroke before the window closes can still be
 * in flight. A coroutine would be cancelled by `exitApplication()` before it ran, so the final
 * write is synchronous: at that instant the process has nothing better to do than finish one
 * small file, and losing the work would be a worse use of the last ten milliseconds.
 */
public fun main(args: Array<String>): Unit = application {
    // A path the caller named must exist; silently opening something else would be the window
    // answering a question nobody asked.
    val named: String? = args.firstOrNull()
    if (named != null && !File(named).isFile) {
        error(
            "No document at '${File(named).absolutePath}'. Pass an existing file, or no " +
                "argument at all to open the starter document.",
        )
    }

    // The schema is what the engine is built from and what the palette and the inspector read;
    // a document does not carry one, so it is built once and shared.
    val schema = BuiltinEngine.schema()
    val runtime = BuiltinEngine.runtime(schema)

    val seed: UiDocument = readDocument(named ?: DEFAULT_DOCUMENT)
    val directory: File = workspaceDirectory()
    // A named document is edited where it lives; otherwise the workspace holds the working copy.
    val location: String =
        named?.let { File(it).absolutePath } ?: File(directory, WORKING_COPY).path

    // Before the window exists: an editor that paints the starter and swaps it a frame later is
    // an editor that shows the user a document they are not editing.
    val opened: Opened = runBlocking {
        EditorWorkspace.open(
            documentLocation = location,
            recordLocation = File(directory, SESSION_FILE).path,
            seed = seed,
            storage = AtomicDocumentStorage(),
        )
    }
    val session: EditorSession = EditorSession.open(
        document = opened.document,
        schema = schema,
        record = opened.record ?: EditorSessionRecord(documentLocation = location),
    )
    val preview = EditorPreview(
        session = session,
        analyze = { BuiltinEngine.analyze(schema, it) },
    )
    val saver = remember(opened.workspace) {
        WorkspaceSaver(opened.workspace) { LocalTime.now().withNano(0).toString() }
    }

    Window(
        onCloseRequest = {
            runBlocking { saver.save(session.document, session.selection.value) }
            exitApplication()
        },
        title = "Lutter Editor — ${File(location).name}",
        state = rememberWindowState(size = DpSize(1_280.dp, 800.dp)),
    ) {
        MaterialTheme {
            Surface {
                Column(modifier = Modifier.fillMaxSize()) {
                    Autosave(session = session, saver = saver)
                    EditorScreen(
                        session = session,
                        preview = preview,
                        runtime = runtime,
                        modifier = Modifier.weight(1f),
                    )
                    SaveStatusBar(
                        location = opened.workspace.documentLocation,
                        state = saver.state,
                        notice = opened.notice,
                    )
                }
            }
        }
    }
}

/** The file name of the working copy the editor owns inside [workspaceDirectory]. */
private const val WORKING_COPY: String = "document.json"

/** Where the session record lives, beside the working copy and never beside a named document. */
private const val SESSION_FILE: String = "session.json"
