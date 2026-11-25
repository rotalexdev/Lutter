package dev.rotalex.lutter.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSelection
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.InspectorModel
import dev.rotalex.lutter.editor.PaletteEntry
import dev.rotalex.lutter.editor.PaletteSection
import dev.rotalex.lutter.editor.PreviewState
import dev.rotalex.lutter.editor.PropertyEdit
import dev.rotalex.lutter.editor.PropertyRow
import dev.rotalex.lutter.editor.PropertyText
import dev.rotalex.lutter.editor.PropertyWrite
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.runtime.BackStackNavigator
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.UiApp
import dev.rotalex.lutter.runtime.UiRuntime

/**
 * The three panes (PLAN §32 Phase 17 P5, P6): what may be added, what it looks like, what it
 * holds.
 *
 * ### Here, not in a host
 *
 * These live in `:app:editor` because two hosts draw them: the desktop window in a row and the
 * Android activity in a narrow column. A host owns the frame, the window or the activity, and
 * the arrangement; the panes themselves are the editor's, and a second copy in the second host
 * would be the copy that goes stale.
 *
 * ### One source of truth per question
 *
 * The palette is the schema's metadata, the canvas is the preview's last render, and the
 * inspector is the session's selection. None of them keeps a copy: the palette asks
 * [EditorSession.palette], the inspector asks [EditorSession.inspector], and the canvas draws
 * [PreviewState.Rendered] — the session's document lowered, not a second reading of it.
 *
 * ### Insertion without drag
 *
 * A palette click asks [EditorSession.dropTargets] for the first place the type fits and inserts
 * there. Dragging is the better gesture and the one this pane will grow; the click is what makes
 * the pane usable and testable today, and it goes through the same `dispatch` either way.
 *
 * Each pane carries a `testTag` so a host's UI test can find it: `palette`, `canvas`,
 * `inspector`, `palette:<type>`, `inspector:<key>`.
 */

/**
 * The editor's two screens, in the order a host lists them.
 *
 * Two, and both read the same document: [Editor] changes it, [Code] shows what it becomes. Kept
 * here rather than in each host so the phone and the desktop cannot disagree about what the editor
 * has — a host chooses how to draw the choice (tabs, a bottom bar), never what the choices are.
 */
public enum class EditorDestination(
    /** What the host's tab says. */
    public val label: String,
) {
    Editor("Editor"),
    Code("Code"),
}

/** Keeps the preview following [preview]'s session for the life of the composition. */
@Composable
public fun FollowPreview(preview: EditorPreview) {
    LaunchedEffect(preview) {
        // Suspends for the life of the composition and is cancelled with it.
        preview.follow()
    }
}

@Composable
public fun PalettePane(
    sections: List<PaletteSection>,
    onInsert: (PaletteEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.colorScheme
    LazyColumn(
        modifier
            .background(theme.surface)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "hint") {
            Text(
                text = "Tap a component to add it",
                style = MaterialTheme.typography.labelMedium,
                color = theme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        sections.forEach { section ->
            item(key = "section:${section.category.name}") {
                Text(
                    text = section.category.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = theme.primary,
                    modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
                )
            }
            items(section.entries, key = { it.type.value }) { entry ->
                // Left-aligned and two-column: a centred `TextButton` reads as a menu, and the
                // type on the right is the one piece of information that tells `m3.Text` from
                // `m3.TextField` in a list of forty near-identical names.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onInsert(entry) }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .testTag("palette:${entry.type.value}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = entry.type.value,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * [state] as it stands, and nothing derived from it.
 *
 * `UiApp` is keyed on the document: a new document means a new page stack and a new page store,
 * which is the argument the desktop sample's `PreviewContent` makes at length.
 *
 * ### The page is a sheet on a desk
 *
 * The document renders inside a card on a tinted backdrop. That is not decoration: without it a
 * page with nothing on it is a blank lavender rectangle the same colour as the chrome around it,
 * and the reasonable reading of a blank rectangle is that the app broke. A white sheet with edges
 * on a grey desk is a page with nothing on it, which is what it is.
 */
@Composable
public fun CanvasPane(
    runtime: UiRuntime,
    state: PreviewState,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.colorScheme
    Column(modifier.background(theme.surfaceVariant).padding(12.dp)) {
        when (state) {
            PreviewState.Empty -> PaneMessage("Nothing resolved yet.", isError = false)
            is PreviewState.Refused -> PaneMessage(
                text = state.reason
                    ?: state.diagnostics.joinToString("\n") { "${it.code.value}: ${it.message}" },
                isError = true,
            )
            is PreviewState.Rendered -> {
                val document: UiDocument = state.document
                if (document.nodes.size == 0) {
                    PaneMessage(
                        text = "This page has nothing on it yet — tap a component in the palette.",
                        isError = false,
                    )
                } else {
                    val navigator = remember(document) { BackStackNavigator(document.app.startPage) }
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = theme.surface,
                        shape = RoundedCornerShape(12.dp),
                        shadowElevation = 1.dp,
                    ) {
                        key(document) {
                            UiApp(runtime, state.resolved, RuntimeEnvironment(navigator = navigator))
                        }
                    }
                }
            }
        }
    }
}

/** A line in the canvas when there is nothing to draw: what is missing, and in what colour. */
@Composable
private fun PaneMessage(text: String, isError: Boolean) {
    Text(
        text = text,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
    )
}

/**
 * Whether [draft] is something this field has not handed to the document yet.
 *
 * [sent] is the text the field last handed to the document, not the document's canonical form
 * of the value: those differ (`1` typed prints back as `1`, an expression prints back
 * normalised), and comparing against the canonical form would commit a value nobody touched.
 * What the rule protects is history — a field that gains and loses focus without being typed in
 * adds no command.
 */
internal fun isNewEdit(sent: String, draft: String): Boolean = sent != draft

/**
 * The selected node's properties, each committed when its field is left.
 *
 * The field is local until it is committed — on focus loss, or on the keyboard's **Done** —
 * because parsing on every keystroke would report a half-typed expression as a refusal, and the
 * document is the thing being edited. A commit that changes nothing is skipped by [isNewEdit];
 * one the engine refuses says why under the field instead of silently reverting, which is what a
 * commit-per-row button used to hide.
 */
@Composable
public fun InspectorPane(
    session: EditorSession,
    selection: EditorSelection,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.colorScheme
    val model: InspectorModel? = remember(selection, session.document) { session.inspector() }
    if (model == null) {
        Text(
            text = "Select a node to inspect it.",
            color = theme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.background(theme.surface).padding(12.dp),
        )
        return
    }
    Column(
        modifier
            .background(theme.surface)
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(model.displayName, style = MaterialTheme.typography.titleMedium)
        Text(model.type.value, style = MaterialTheme.typography.labelSmall, color = theme.onSurfaceVariant)
        model.properties.forEach { row ->
            PropertyField(session, model.node, row)
        }
    }
}

/** One property's field: a local draft, and the two ways it stops being local. */
@Composable
private fun PropertyField(session: EditorSession, node: NodeId, row: PropertyRow) {
    val theme = MaterialTheme.colorScheme
    // Keyed by the node as well as the key: two nodes both carry a `text`, and a field that
    // survived a selection change would show the previous node's value in this node's row.
    val focus = remember(node, row.key) { mutableStateOf(false) }
    var text by remember(node, row.key) { mutableStateOf(row.text) }
    var sent by remember(node, row.key) { mutableStateOf(row.text) }
    var refusal by remember(node, row.key) { mutableStateOf<String?>(null) }

    // The document is the truth: when something else changes this value (an undo, a palette
    // insert) the field adopts it — unless the author is in the middle of typing, in which case
    // adopting would delete their keystrokes.
    LaunchedEffect(node, row.text) {
        if (!focus.value) {
            text = row.text
            sent = row.text
            refusal = null
        }
    }

    fun commit() {
        if (!isNewEdit(sent, text)) return
        when (val write = session.setProperty(node, row.key, text)) {
            is PropertyWrite.Unparseable -> refusal = write.message
            is PropertyWrite.Refused -> refusal = write.reasons.joinToString("; ") { it.message }
            is PropertyWrite.Applied -> {
                sent = text
                refusal = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                refusal = null
            },
            label = { Text(row.key.value + if (row.required) " *" else "") },
            isError = refusal != null ||
                (row.isExpression && PropertyText.apply(text) is PropertyEdit.Unparseable),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focus.value = it.isFocused
                    if (!it.isFocused) commit()
                }
                .testTag("inspector:${row.key.value}"),
        )
        // The engine's own words, not a rephrasing: a refusal that the author cannot read is
        // indistinguishable from a keystroke that went nowhere.
        refusal?.let {
            Text(it, color = theme.error, style = MaterialTheme.typography.labelSmall)
        }
    }
}
