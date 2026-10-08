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
 * The document the window opens on when it is given no path: the fixture whose button navigates.
 *
 * Read from the module that owns it, so this repository holds one document that answers "does a
 * press reach the next page" rather than two that answer it separately. The path is relative to
 * the working directory, which is this module's own directory under both `run` and `desktopTest`.
 */
public const val DEFAULT_DOCUMENT: String =
    "../../integration/generated-compile/fixtures-navigating/button_navigate.json"

/** The document at [path], decoded. The refusal names the absolute path, which is the one to fix. */
public fun readDocument(path: String): UiDocument {
    val file = File(path)
    check(file.isFile) {
        "No document at '${file.absolutePath}'. Pass one: gradle :samples:desktop-preview:run --args=<path>"
    }
    return JsonDocumentCodec.decode(file.readText().encodeToByteArray()).document
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
