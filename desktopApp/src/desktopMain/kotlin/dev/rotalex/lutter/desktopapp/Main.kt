package dev.rotalex.lutter.desktopapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import java.io.File

/**
 * The document the window opens on when it is given no path.
 *
 * Relative to the **module directory**, which is the working directory under `gradle run` and
 * under `desktopTest`. It is not relative to anything a packaged build can rely on, which is why
 * a path handed in on the command line wins.
 */
public const val DEFAULT_DOCUMENT: String =
    "../integration/generated-compile/fixtures-navigating/button_navigate.json"

/**
 * [path]'s document, decoded, or a refusal naming the path it tried.
 *
 * No bundled fallback: unlike the sample's read-only preview, an editor with no document has
 * nothing to edit, and silently opening a copy would hide which file the session record points
 * at. The refusal is the honest answer.
 */
public fun readDocument(path: String): UiDocument {
    val file = File(path)
    if (!file.isFile) {
        error("No document at '${file.absolutePath}'. Pass one: LutterEditor <document.json>")
    }
    return JsonDocumentCodec.decode(file.readText().encodeToByteArray()).document
}

public fun main(args: Array<String>): Unit = application {
    val path: String = args.firstOrNull() ?: DEFAULT_DOCUMENT
    val document: UiDocument = readDocument(path)
    // The schema is what the engine is built from and what the palette and the inspector read;
    // a document does not carry one, so it is built once and shared.
    val schema = BuiltinEngine.schema()
    val runtime = BuiltinEngine.runtime(schema)
    val session: EditorSession = EditorSession.open(document, schema)
    val preview = EditorPreview(
        session = session,
        analyze = { BuiltinEngine.analyze(schema, it) },
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Lutter Editor — ${File(path).name}",
        state = rememberWindowState(size = DpSize(1_280.dp, 800.dp)),
    ) {
        MaterialTheme {
            Surface {
                EditorScreen(session = session, preview = preview, runtime = runtime)
            }
        }
    }
}
