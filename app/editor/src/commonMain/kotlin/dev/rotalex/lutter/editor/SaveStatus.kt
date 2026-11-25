package dev.rotalex.lutter.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.model.doc.UiDocument
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.combine

/** What the editor last did with the document, which is a thing the person editing has to know. */
public sealed interface SaveState {

    /** Nothing has been written yet in this session's life; only true for the first frame. */
    public data object Idle : SaveState

    /** The document on disk is the document on screen, as of [at]. */
    public data class Saved(public val at: String) : SaveState

    /** The document on screen is **not** on disk, and [reason] is why. */
    public data class Failed(public val at: String, public val reason: String) : SaveState
}

/**
 * Autosave, and the one place a failed write becomes visible.
 *
 * ### Autosave rather than a Save button
 *
 * A Save button is a promise that somebody will press it, and the failure it produces is the
 * oldest one in editors: work that was never saved because saving was a step. The editor has
 * nothing to choose — one document, one location, decided at open — so there is no decision a
 * button could express and no reason to make the document depend on one.
 *
 * ### One flow, two triggers
 *
 * [EditorSession] holds its document in a `StateFlow` and its selection in another, and neither is
 * Compose snapshot state, so `snapshotFlow` would observe nothing here. `combine` over the two
 * flows is the honest subscription: every applied command emits on the first, every click on the
 * second, and the pair is what the record needs. Emissions are collected sequentially, so a slow
 * write delays the next one rather than racing it.
 *
 * ### Failure is a state, not an exception
 *
 * A write that fails leaves the previous bytes in place (that is the storage seam's promise) and
 * the window saying so, with the reason. Swallowing it would produce exactly the situation this
 * class exists to end: a person editing a document that exists nowhere but in memory.
 *
 * [now] is the host's clock, as a string, because "12 seconds ago" needs a time zone and a
 * formatter and this class has no business knowing either.
 */
public class WorkspaceSaver(
    private val workspace: EditorWorkspace,
    private val now: () -> String,
) {

    public var state: SaveState by mutableStateOf(SaveState.Idle)
        private set

    /** Writes [document] and where [selection] points, then reports how that went. */
    public suspend fun save(document: UiDocument, selection: EditorSelection): Unit {
        state = try {
            workspace.save(
                document = document,
                record = EditorSessionRecord(
                    documentLocation = workspace.documentLocation,
                    selection = selection.toRecord(),
                ),
            )
            SaveState.Saved(at = now())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SaveState.Failed(
                at = now(),
                reason = failure.message ?: (failure::class.simpleName ?: "unknown failure"),
            )
        }
    }
}

/**
 * Follows [session] and saves it, so a host's content does not have to.
 *
 * A composable rather than a `LaunchedEffect` in the caller because there are two flows
 * (document and selection), they belong to the same subscription, and a host that forgot one
 * would save the document without the cursor.
 */
@Composable
public fun Autosave(session: EditorSession, saver: WorkspaceSaver) {
    LaunchedEffect(session, saver) {
        combine(session.controller.state, session.selection) { controllerState, selection ->
            controllerState.document to selection
        }.collect { (document, selection) -> saver.save(document, selection) }
    }
}

/**
 * The line under the panes: where the document is, and whether what is on screen is what is on disk.
 *
 * At the bottom and always present, because the question it answers — "did that save?" — is asked
 * at the moment somebody stops typing, and a status that appears only while saving is absent
 * exactly then. Colour and text both carry the answer: a red line whose only difference is a
 * colour is a line somebody will misread.
 */
@Composable
public fun SaveStatusBar(location: String, state: SaveState, notice: String?) {
    val theme = MaterialTheme.colorScheme
    val failed: Boolean = state is SaveState.Failed
    val text: String = when (state) {
        SaveState.Idle -> "Not written yet — $location"
        is SaveState.Saved -> "Saved ${state.at} — $location"
        is SaveState.Failed -> "NOT SAVED ${state.at}: ${state.reason}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (failed) theme.errorContainer else theme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("save-status"),
    ) {
        Text(
            text = notice?.let { "$it  ·  $text" } ?: text,
            color = if (failed) theme.onErrorContainer else theme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
