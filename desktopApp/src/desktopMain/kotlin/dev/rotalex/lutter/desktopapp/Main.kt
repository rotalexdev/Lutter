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
