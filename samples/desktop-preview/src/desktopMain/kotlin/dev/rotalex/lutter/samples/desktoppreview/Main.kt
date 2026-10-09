package dev.rotalex.lutter.samples.desktoppreview

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.runtime.BackStackNavigator
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.UiApp
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import java.io.File

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

    val bundled = MainKt::class.java.getResourceAsStream(BUNDLED_DOCUMENT)
    if (bundled != null) {
        return bundled.use { JsonDocumentCodec.decode(it.readBytes()).document }
    }

    error(
        "No document at '${file.absolutePath}' and no '$BUNDLED_DOCUMENT' in the jar. " +
            "Pass one: gradle :samples:desktop-preview:run --args=<path>",
    )
}

/**
 * The window: the document's own start page, rendered by the interpreter and by nothing else.
 *
 * [RuntimeEnvironment] is all this document asks for — no host function, no state, no editor
 * hooks, so every one of those seams stays at its default. `:integration:generated-compile`
 * proves the generated path draws the same pixels as this one, so a second window here would show
 * what CI already asserts rather than what only a human can check.
 */
public fun main(args: Array<String>): Unit {
    val schema = BuiltinEngine.schema()
    // Read twice over, once each: the start page is a document fact that lowering drops, so it is
    // taken from what was decoded rather than from the resolved form that no longer carries it.
    val decoded = readDocument(args.firstOrNull() ?: DEFAULT_DOCUMENT)
    val document = BuiltinEngine.resolve(schema, decoded)
    // Built here rather than in the window's content: construction is what runs §4.5's coverage
    // check, and that content recomposes on every navigation.
    val runtime = BuiltinEngine.runtime(schema)
    val navigator = BackStackNavigator(decoded.app.startPage)

    application {
        Window(onCloseRequest = ::exitApplication, title = "Lutter desktop preview") {
            UiApp(runtime, document, RuntimeEnvironment(navigator = navigator))
        }
    }
}
