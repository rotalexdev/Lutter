package dev.rotalex.lutter.desktopapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * The three panes (PLAN §32 Phase 17 P5): what may be added, what it looks like, what it holds.
 *
 * ### One source of truth per question
 *
 * The palette is the schema's metadata, the canvas is the preview's last render, and the
 * inspector is the session's selection. None of them keeps a copy: the palette asks
 * [EditorSession.palette], the inspector asks [EditorSession.inspector], and the canvas draws
 * [PreviewState.Rendered] — the session's document lowered, not a second reading of it.
 *
 * ### The preview follows the session here
 *
 * [EditorPreview.follow] is launched with this composition, so every edit the panes make comes
 * back as a new render. That is the host's job: the session does not know a window exists, and
 * the preview does not know who is driving it.
 *
 * ### Insertion without drag
 *
 * A palette click asks [EditorSession.dropTargets] for the first place the type fits and inserts
 * there. Dragging is the better gesture and the one this pane will grow; the click is what makes
 * the pane usable and testable today, and it goes through the same `dispatch` either way.
 */
@Composable
public fun EditorScreen(
    session: EditorSession,
    preview: EditorPreview,
    runtime: UiRuntime,
    modifier: Modifier = Modifier,
) {
    val rendered: PreviewState by preview.state.collectAsState()
    val selection: EditorSelection by session.selection.collectAsState()

    LaunchedEffect(preview) {
        // Suspends for the life of the composition and is cancelled with it.
        preview.follow()
    }

    Row(modifier.fillMaxSize()) {
        PalettePane(
            sections = session.palette(),
            onInsert = { entry ->
                val target = session.dropTargets(entry.type).firstOrNull()
                if (target != null) session.insert(entry.type, target)
            },
            modifier = Modifier.width(220.dp).fillMaxHeight().testTag("palette"),
        )
        CanvasPane(
            runtime = runtime,
            state = rendered,
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("canvas"),
        )
        InspectorPane(
            session = session,
            selection = selection,
            document = session.document,
            modifier = Modifier.width(280.dp).fillMaxHeight().testTag("inspector"),
        )
    }
}

@Composable
private fun PalettePane(
    sections: List<PaletteSection>,
    onInsert: (PaletteEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sections.forEach { section ->
            item(key = section.category.name) {
                Text(section.category.name, style = MaterialTheme.typography.titleSmall)
            }
            items(section.entries, key = { it.type.value }) { entry ->
                TextButton(
                    onClick = { onInsert(entry) },
                    modifier = Modifier.fillMaxWidth().testTag("palette:${entry.type.value}"),
                ) {
                    Text(entry.displayName)
                }
            }
        }
    }
}

@Composable
private fun CanvasPane(
    runtime: UiRuntime,
    state: PreviewState,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        when (state) {
            PreviewState.Empty -> Text("Nothing resolved yet.", Modifier.padding(12.dp))
            is PreviewState.Refused -> Text(
                text = state.reason
                    ?: state.diagnostics.joinToString("\n") { "${it.code.value}: ${it.message}" },
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp),
            )
            is PreviewState.Rendered -> {
                val document: UiDocument = state.document
                val navigator = remember(document) { BackStackNavigator(document.app.startPage) }
                // A new document means a new page stack and a new page store: the sample's
                // `PreviewContent` argues both at length, and the reasoning is the same here.
                key(document) {
                    UiApp(runtime, state.resolved, RuntimeEnvironment(navigator = navigator))
                }
            }
        }
    }
}

@Composable
private fun InspectorPane(
    session: EditorSession,
    selection: EditorSelection,
    document: UiDocument,
    modifier: Modifier = Modifier,
) {
    val model: InspectorModel? = remember(selection, document) { session.inspector() }
    if (model == null) {
        Text("Select a node to inspect it.", modifier.padding(12.dp))
        return
    }
    Column(
        modifier.padding(8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(model.displayName, style = MaterialTheme.typography.titleMedium)
        Text(model.type.value, style = MaterialTheme.typography.bodySmall)
        model.properties.forEach { row ->
            // The field is local until it is applied: parsing on every keystroke would report a
            // half-typed expression as a refusal, and the document is the thing being edited.
            var text by remember(row.key, row.text) { mutableStateOf(row.text) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(row.key.value + if (row.required) " *" else "") },
                isError = row.isExpression && PropertyText.apply(text) is PropertyEdit.Unparseable,
                keyboardActions = KeyboardActions(onDone = { session.setProperty(model.node, row.key, text) }),
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
