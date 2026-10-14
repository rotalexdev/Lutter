package dev.rotalex.lutter.samples.desktoppreview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.runtime.BackStackNavigator
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.UiApp
import dev.rotalex.lutter.runtime.UiRuntime

/**
 * The window's content: whatever the loop last loaded, and whatever it last had to say about it.
 *
 * ### Re-render is a value swap, and nothing else
 *
 * PLAN's rule that *"preview always renders a `ResolvedDocument` snapshot"* is the whole rule
 * here, and it is a constraint rather than a permission. There is no patching here, no diff against
 * what is on screen, and no attempt to carry state from the old document into the new one. A
 * reload produces one new immutable `LoadedDocument`, the composition reads it, and every screen
 * reads its snapshot. That is why this takes a `State` rather than a callback: `UiApp` already
 * takes the document as a plain parameter, so a composition that reads a snapshot-state value
 * there recomposes when the value changes, with no engine-side seam added for the preview.
 *
 * ### Two pieces of engine state have to go with the document
 *
 * Neither of them is reset by handing `UiApp` a different document, and both are correct to
 * reset, because both belong to a document rather than to the window.
 *
 * **The navigator.** `BackStackNavigator` holds page ids and nothing else, and it was built from
 * a start page that came out of the previous file. A stack of ids from a document that is no
 * longer the one on screen resolves against the new document's page map by coincidence at best,
 * and a reloaded preview that stays on the page the user had navigated to — showing a page the
 * new document may not even have — is not what "the document changed" means. Rebuilt per
 * document, so a reload opens the new start page.
 *
 * **The page store.** `UiScreen` holds it in `remember(page.id)`, and page ids are usually the
 * same across a reload, so without [key] the new document would draw with the previous one's
 * page state — including a state slot it no longer declares, whose declared type and initial
 * value it never agreed to. `key` throws the subtree away so `SnapshotStateStore.seeded` runs
 * again from the new document's declarations.
 *
 * Both are here rather than in `:engine:runtime` because the answer is a preview's answer. The
 * engine's contract is that a host decides what its own state does when the document changes,
 * and a host that patched a live screen would be a different host with a different contract.
 *
 * @param runtime the engine, built once by the caller. It does not change with the document:
 *   it is assembled from the schema, which a document does not carry.
 * @param loaded the current snapshot, written by the reload loop.
 * @param status what the loop last saw, which is the only thing that puts a line on screen.
 */
@Composable
internal fun PreviewContent(
    runtime: UiRuntime,
    loaded: State<LoadedDocument>,
    status: State<PreviewStatus>,
    modifier: Modifier = Modifier,
) {
    val document: LoadedDocument = loaded.value
    val navigator = remember(document) { BackStackNavigator(document.decoded.app.startPage) }

    Column(modifier.fillMaxSize()) {
        PreviewBanner(status.value)
        // The weight sits on the box rather than on `UiApp` so the document keeps filling the
        // window with or without a line above it: `key` takes a `@Composable () -> Unit`, and
        // `weight` is `ColumnScope`'s, so it does not reach inside.
        Box(Modifier.weight(1f)) {
            key(document) {
                UiApp(runtime, document.resolved, RuntimeEnvironment(navigator = navigator))
            }
        }
    }
}

/**
 * The one line above the document, drawn only when there is one.
 *
 * Silent when the preview is working, which is the point: this window's job is to draw the
 * document, and a permanent strip explaining that it is doing so would be in every screenshot
 * this module publishes and in nothing a user needed.
 *
 * The two colours are the two readings. A refusal is the error container because someone has to
 * go and fix a file; [PreviewStatus.Bundled] is the secondary container because nobody has done
 * anything wrong and the only thing to say is that editing the file will not do what they
 * expect.
 */
@Composable
private fun PreviewBanner(status: PreviewStatus) {
    val message: String = status.message ?: return
    val container = if (status.isFailure) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = if (status.isFailure) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(color = container, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = onContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
