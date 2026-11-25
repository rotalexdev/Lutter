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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSelection
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.InspectorModel
import dev.rotalex.lutter.editor.PaletteEntry
import dev.rotalex.lutter.editor.PaletteSection
import dev.rotalex.lutter.editor.PreviewState
import dev.rotalex.lutter.editor.PropertyEdit
import dev.rotalex.lutter.editor.PropertyText
import dev.rotalex.lutter.model.doc.UiDocument
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
 * `inspector`, `palette:<type>`, `inspector:<key>`, `apply:<key>`.
 */

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
 * The selected node's properties, each committed by its own **Apply**.
 *
 * The field is local until it is applied: parsing on every keystroke would report a half-typed
 * expression as a refusal, and the document is the thing being edited.
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
            var text by remember(row.key, row.text) { mutableStateOf(row.text) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(row.key.value + if (row.required) " *" else "") },
                isError = row.isExpression && PropertyText.apply(text) is PropertyEdit.Unparseable,
                keyboardActions = KeyboardActions(
                    onDone = { session.setProperty(model.node, row.key, text) },
                ),
                modifier = Modifier.fillMaxWidth().testTag("inspector:${row.key.value}"),
            )
            Button(
                onClick = { session.setProperty(model.node, row.key, text) },
                modifier = Modifier.testTag("apply:${row.key.value}"),
            ) {
                Text("Apply")
            }
        }
    }
}
