package dev.rotalex.lutter.samples.desktoppreview

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.runtime.BackStackNavigator
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.UiApp
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The window's content as PNGs, so a run of this build leaves something a human can look at.
 *
 * A compiled module and a green test say the engine runs; they do not say what it draws. These
 * two files are what the maintainer downloads to see it, which is the whole reason this exists.
 *
 * **What is rendered is `UiApp` — the exact composable `main` hands to `Window`.** Not a
 * stand-in for it and not a mock: if this renders wrongly, the window renders wrongly.
 *
 * **Two states, driven through the navigator rather than through a press.** `DesktopPreviewTest`
 * already proves a real click moves the stack, so repeating it here would prove the same thing
 * twice while making a pixel difference look like a navigation bug. Driving `BackStackNavigator`
 * directly keeps each failure legible: a wrong image means the wrong pixels, not a broken press.
 *
 * **Headless.** `renderComposeScene` rasterises through Skia into an image and never opens a
 * window, so this needs no display — the same property `:integration:generated-compile` already
 * relies on for its pixel comparison.
 *
 * Files land in `build/screenshots`, relative to this module's directory, which is the Gradle
 * test working directory. That is the same assumption `DEFAULT_DOCUMENT` already makes, and the
 * workflow uploads the path this produces.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopPreviewScreenshotTest {

    @Test
    fun `the start page and the page a press reaches are both captured`() {
        val output = File(OUTPUT_DIR).apply { mkdirs() }

        val schema = BuiltinEngine.schema()
        // Read from what was decoded, not from the resolved form: lowering drops the start page,
        // so `ResolvedDocument` cannot answer where the window opens.
        val decoded = readDocument(DEFAULT_DOCUMENT)
        val document = BuiltinEngine.resolve(schema, decoded)
        // Built once and outside any composition: construction is what runs §4.5's coverage check,
        // and a fresh runtime per frame would report the same thing twice.
        val runtime = BuiltinEngine.runtime(schema)

        val navigator = BackStackNavigator(decoded.app.startPage)

        // Each frame is rendered where it is used rather than through a shared helper: naming
        // `ResolvedDocument` and `UiRuntime` as parameter types would make this file an import of
        // `:engine:analysis`, which the sample does not declare and would have to declare.
        val start = renderComposeScene(width = WIDTH, height = HEIGHT) {
            UiApp(runtime, document, RuntimeEnvironment(navigator = navigator))
        }
        write(start, output.resolve("01-start.png"))

        // The same act the document's `nav.navigate` performs, through the same public entry point.
        navigator.navigate(PageId(PROFILE), emptyMap())
        val after = renderComposeScene(width = WIDTH, height = HEIGHT) {
            UiApp(runtime, document, RuntimeEnvironment(navigator = navigator))
        }
        write(after, output.resolve("02-profile.png"))

        // A blank or zero-size frame encodes to a valid PNG, so nothing above would fail on an
        // empty render. The size is the only cheap assertion that the scene actually drew.
        assertTrue(start.width > 0 && start.height > 0, "the start page rendered empty")
        assertTrue(after.width > 0 && after.height > 0, "the profile page rendered empty")
    }

    /**
     * `encodeToData` returns null when Skia cannot encode, and a null there would otherwise be
     * an NPE three frames from the real cause. The absolute path is in the message because that
     * is what a maintainer needs when a download comes back short.
     */
    private fun write(image: Image, target: File) {
        val encoded = image.encodeToData(EncodedImageFormat.PNG)
        checkNotNull(encoded) { "Skia could not encode '${target.absolutePath}' as PNG" }
        target.writeBytes(encoded.bytes)
        check(target.length() > 0) { "'${target.absolutePath}' was written empty" }
    }

    private companion object {
        /** Where the workflow looks. Module-relative, like `DEFAULT_DOCUMENT`. */
        const val OUTPUT_DIR: String = "build/screenshots"

        /** The page `button_navigate.json` navigates to on the press. */
        const val PROFILE: String = "p_profile"

        /**
         * A window big enough for the Material 3 button and its label at a readable size, and
         * small enough that two of these are a sensible download.
         */
        const val WIDTH: Int = 900
        const val HEIGHT: Int = 600
    }
}
