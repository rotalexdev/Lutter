package dev.rotalex.lutter.samples.desktoppreview

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import dev.rotalex.lutter.serialization.storage.FileDocumentStorage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The document the window opens on when it is given no path.
 *
 * Relative to the **module directory**, which is the working directory under `gradle run` and
 * under `desktopTest`. It is not relative to anything a packaged build can rely on: unzip the
 * app-image in `Downloads` and double-click the launcher, the working directory is wherever the
 * user extracted it, this path resolves to nothing, and the window never opens.
 *
 * That failure was reported as "Failed to launch JVM" — which is the last line jpackage's
 * launcher prints *after* the application's own output, not a statement about the VM. The real
 * message was the `IllegalStateException` above it.
 *
 * So this is a preference, not a promise: [readDocument] falls back to the copy bundled in the
 * jar, and a path handed in on the command line still wins over both.
 */
public const val DEFAULT_DOCUMENT: String =
    "../../integration/generated-compile/fixtures-navigating/button_navigate.json"

/** The copy shipped inside the jar, for a packaged build that has no repository around it. */
private const val BUNDLED_DOCUMENT: String = "/default-document.json"

/**
 * The document at [path], decoded, or the bundled one when [path] is not there.
 *
 * The order is deliberate. A path the caller named always wins — that is the `--args` case and
 * it is how a second document gets looked at. A path that exists wins next, which keeps
 * `gradle run` reading the repository's fixture so an edit to it is visible without rebuilding
 * anything. Only when neither holds does the bundled copy answer, and that is the packaged
 * case, where it is the only document there is.
 *
 * The refusal names both paths it tried, because the useful question when one of them is wrong
 * is which one was consulted.
 */
public fun readDocument(path: String): UiDocument {
    val file = File(path)
    if (file.isFile) return JsonDocumentCodec.decode(file.readText().encodeToByteArray()).document

    val bundled = BundledDocument.bytes(BUNDLED_DOCUMENT)
    if (bundled != null) {
        return JsonDocumentCodec.decode(bundled).document
    }

    error(
        "No document at '${file.absolutePath}' and no '$BUNDLED_DOCUMENT' in the jar. " +
            "Pass one: gradle :samples:desktop-preview:run --args=<path>",
    )
}

/**
 * Reads a document out of this jar.
 *
 * An object rather than a line in [readDocument] because the classloader call needs a class to
 * be reached through, and the obvious one does not exist: this file's own facade is `MainKt` on
 * the JVM and has no Kotlin name to be referenced by. [UiDocument] is a real class that is
 * already imported, and its classloader is the same one either way.
 */
private object BundledDocument {

    /** @return the bytes of [path] inside the jar, or null when it is not there. */
    fun bytes(path: String): ByteArray? =
        UiDocument::class.java.getResourceAsStream(path)?.use { it.readBytes() }
}

/**
 * The window: the document's own start page, rendered by the interpreter and by nothing else,
 * and re-rendered when the file behind it changes.
 *
 * A `RuntimeEnvironment` is all this document asks for — no host function, no state, no editor
 * hooks, so every one of those seams stays at its default. `:integration:generated-compile`
 * proves the generated path draws the same pixels as this one, so a second window here would show
 * what CI already asserts rather than what only a human can check.
 *
 * This is the composition root and stays one: it decides *what* is watched and hands the window
 * two values to read. What it watches with is [DocumentReloader], and what the window does with
 * a change is [PreviewContent]; neither belongs here, because both are answers to questions this
 * function does not ask.
 *
 * A file is watched only when the path given resolves to one. That is the packaged case the
 * fallback above exists for: with nothing on disk, a poller would read a location that never
 * exists and report it every tick, and the window would say it could not read a file the user
 * never named.
 */
public fun main(args: Array<String>): Unit {
    val schema = BuiltinEngine.schema()
    val path = args.firstOrNull() ?: DEFAULT_DOCUMENT
    val watched: String? = path.takeIf { File(it).isFile }

    // The document, in both forms, held together because the window needs one and the other: the
    // start page is a document fact that lowering drops, so it is taken from what was decoded
    // rather than from the resolved form that no longer carries it. A reload produces the same
    // pair, which is what `LoadedDocument` is.
    val decoded = readDocument(path)
    val loaded = mutableStateOf(LoadedDocument(decoded, BuiltinEngine.resolve(schema, decoded)))
    val status = mutableStateOf(
        watched?.let { PreviewStatus.Watching(it) } ?: PreviewStatus.Bundled,
    )
    // Built here rather than in the window's content: construction is what runs §4.5's coverage
    // check, and that content recomposes on every navigation. It survives a reload unchanged,
    // because it is assembled from the schema and a document carries no schema.
    val runtime = BuiltinEngine.runtime(schema)

    val polling = CoroutineScope(Dispatchers.IO + SupervisorJob())
    try {
        if (watched != null) {
            polling.launch {
                DocumentReloader(
                    storage = FileDocumentStorage(),
                    location = watched,
                    schema = schema,
                    onLoaded = { loaded.value = it },
                    onStatus = { status.value = it },
                ).run()
            }
        }

        application {
            Window(onCloseRequest = ::exitApplication, title = "Lutter desktop preview") {
                PreviewContent(runtime, loaded, status)
            }
        }
    } finally {
        // The last window is closed, so there is nothing left to report to. `application` returns
        // here, and a loop still running would keep reading a file for a process on its way out.
        polling.cancel()
    }
}
