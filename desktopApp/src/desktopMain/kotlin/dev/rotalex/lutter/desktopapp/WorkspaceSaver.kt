package dev.rotalex.lutter.desktopapp

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
import dev.rotalex.lutter.editor.EditorSelection
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.EditorSessionRecord
import dev.rotalex.lutter.editor.toRecord
import dev.rotalex.lutter.model.doc.UiDocument
import java.time.LocalTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

/** What the window last did with the document, which is a thing the person editing has to know. */
internal sealed interface SaveState {

    /** Nothing has been written yet in this window's life; only true for the first frame. */
    data object Idle : SaveState

    /** The document on disk is the document on screen, as of [at]. */
    data class Saved(val at: String) : SaveState

    /** The document on screen is **not** on disk, and [reason] is why. */
    data class Failed(val at: String, val reason: String) : SaveState
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
 * [EditorSession] holds its document in a `StateFlow` and its selection in another, and neither
 * is Compose snapshot state, so `snapshotFlow` would observe nothing here. `combine` over the two
 * flows is the honest subscription: every applied command emits on the first, every click on the
 * second, and the pair is what the record needs. Emissions are collected sequentially, so a slow
 * write delays the next one rather than racing it.
 *
 * ### Failure is a state, not an exception
 *
 * A write that fails leaves the old bytes in place (see [AtomicDocumentStorage]) and the window
 * saying so, with the reason. Swallowing it would produce the exact situation this class exists
 * to end: a person editing a document that exists nowhere but in memory.
 */
internal class WorkspaceSaver(private val workspace: EditorWorkspace) {

    var state: SaveState by mutableStateOf(SaveState.Idle)
        private set

    /** Writes [document] and where [selection] points, then reports how that went. */
    suspend fun save(document: UiDocument, selection: EditorSelection) {
        state = try {
            workspace.save(
                document = document,
                record = EditorSessionRecord(
                    documentLocation = workspace.documentLocation,
                    selection = selection.toRecord(),
                ),
            )
            SaveState.Saved(at = LocalTime.now().withNano(0).toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SaveState.Failed(
                at = LocalTime.now().withNano(0).toString(),
                reason = failure.message ?: (failure::class.simpleName ?: "unknown failure"),
            )
        }
    }
}

/**
 * Follows [session] and saves it, so the window's content does not have to.
 *
 * A composable rather than a `LaunchedEffect` in the caller because there are two of them
 * (document and selection), they belong to the same subscription, and a host that forgot one
 * would save the document without the cursor.
 */
@Composable
internal fun Autosave(session: EditorSession, saver: WorkspaceSaver) {
    LaunchedEffect(session, saver) {
        combine(session.controller.state, session.selection) { controllerState, selection ->
            controllerState.document to selection
        }.collect { (document, selection) -> saver.save(document, selection) }
    }
}

/**
 * The line under the panes: where the document is, and whether what is on screen is what is on
 * disk.
 *
 * At the bottom and always present, because the question it answers — "did that save?" — is
 * asked at the moment somebody stops typing, and a status that appears only while saving is
 * absent exactly then. Colour and text both carry the answer: a red line that only says "saved"
 * in a different attribute is a line somebody will misread.
 */
@Composable
internal fun SaveStatusBar(workspace: EditorWorkspace, state: SaveState, notice: String?) {
    val theme = MaterialTheme.colorScheme
    val colour = if (state is SaveState.Failed) theme.error else theme.onSurfaceVariant
    val text: String = when (state) {
        SaveState.Idle -> "Not written yet — ${workspace.documentLocation}"
        is SaveState.Saved -> "Saved ${state.at} — ${workspace.documentLocation}"
        is SaveState.Failed -> "NOT SAVED ${state.at}: ${state.reason}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (state is SaveState.Failed) theme.errorContainer else theme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("save-status"),
    ) {
        Text(
            text = notice?.let { "$it  ·  $text" } ?: text,
            color = if (state is SaveState.Failed) theme.onErrorContainer else colour,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
