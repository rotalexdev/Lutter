package dev.rotalex.lutter.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.editor.Autosave
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSelection
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.InspectorModel
import dev.rotalex.lutter.editor.PreviewState
import dev.rotalex.lutter.editor.SaveStatusBar
import dev.rotalex.lutter.editor.WorkspaceSaver
import dev.rotalex.lutter.editor.ui.theme.LutterSpacing
import dev.rotalex.lutter.runtime.UiRuntime
import kotlinx.coroutines.launch

/**
 * What the shell needs to keep the working copy on disk.
 *
 * The saver is the host's, because writing is the host's job (`:app:editor` never owns a
 * directory); the shell only decides where the answer appears. A test that asserts the
 * arrangement passes `null` and gets the same frame without a file behind it.
 */
public class EditorShellSave(
    public val saver: WorkspaceSaver,
    public val location: String,
    public val notice: String?,
)

/**
 * The editor's frame, drawn by both hosts: the app bar, the two screens as tabs, and each
 * screen's body arranged for the width it was given.
 *
 * ### Two arrangements, one screen
 *
 * Under [CompactWidth] there is room for one pane at a time, so the canvas gets the whole
 * window and the palette and the inspector arrive as bottom sheets — the platform's own
 * sheet, not a hand-rolled overlay, which is what makes dismissal, back navigation and the
 * drag handle work without this file doing anything (§32 Phase 17 P6). At or above it the
 * three panes sit side by side, the arrangement a desktop window has the desk for.
 *
 * ### Why the shell and not the hosts
 *
 * The hosts used to repeat this: an `EditorScaffold` in the activity, a `Column` in the
 * window, the same tabs twice, and two different places that forgot the theme. One frame
 * means one place where the tabs, the save line and the selection's actions are decided, and
 * a new host (a tablet build, a browser) is a window around this composable.
 *
 * `Autosave` is started here for the same reason: a shell that showed the save line but left
 * the writing to its host would let a host forget it, which is what the desktop window's
 * close handler already had to work around.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun EditorShell(
    session: EditorSession,
    preview: EditorPreview,
    runtime: UiRuntime,
    save: EditorShellSave?,
    modifier: Modifier = Modifier,
) {
    if (save != null) Autosave(session = session, saver = save.saver)
    FollowPreview(preview)

    val selection: EditorSelection by session.selection.collectAsState()
    val history by session.controller.state.collectAsState()
    // The two keys are also the subscription. `EditorSession.inspector` reads the session's own
    // flows, which are not snapshot state, so a frame whose body never touched `selection` would
    // not redraw when one arrived — and at compact width nothing else in this body does touch
    // it. The symptom is a dock that keeps acting on the node before the one on screen.
    val selected: InspectorModel? = remember(selection, history.document) { session.inspector() }
    var destination: EditorDestination by rememberSaveable { mutableStateOf(EditorDestination.Editor) }
    var paletteOpen: Boolean by remember { mutableStateOf(false) }
    var inspectorOpen: Boolean by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val paletteSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val inspectorSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact: Boolean = maxWidth < CompactWidth
        Scaffold(
            // The insets are the frame's business on both platforms: zero on a desk, the
            // status and gesture bars on a phone. `WindowInsets(0)` on the content is what
            // keeps them from being applied a second time by the body.
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Column {
                    TopAppBar(
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        title = {
                            Column {
                                Text(
                                    text = session.document.meta.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = selectionCaption(selected),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            if (destination == EditorDestination.Editor) {
                                TextButton(
                                    onClick = { session.undo() },
                                    enabled = history.canUndo,
                                ) { Text("Undo") }
                                TextButton(
                                    onClick = { session.redo() },
                                    enabled = history.canRedo,
                                ) { Text("Redo") }
                            }
                        },
                    )
                    TabRow(selectedTabIndex = EditorDestination.entries.indexOf(destination)) {
                        EditorDestination.entries.forEach { tab ->
                            Tab(
                                selected = destination == tab,
                                onClick = { destination = tab },
                                text = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                // The palette is already on screen when the window is wide, so a button that
                // opened the same list twice would be the only redundant thing in the frame.
                if (compact && destination == EditorDestination.Editor) {
                    FloatingActionButton(
                        onClick = { paletteOpen = true },
                        modifier = Modifier.testTag("palette-open"),
                    ) {
                        Icon(AddGlyph, contentDescription = "Add component")
                    }
                }
            },
            bottomBar = {
                Column {
                    // The selection's actions, where a thumb already is. Wide windows put the
                    // same actions in the inspector that is already open.
                    if (compact && destination == EditorDestination.Editor && selected != null) {
                        SelectionActions(
                            onProperties = { inspectorOpen = true },
                            onDuplicate = { session.duplicate(selected.node) },
                            onDelete = { session.remove(selected.node) },
                        )
                    }
                    if (save != null) {
                        SaveStatusBar(
                            location = save.location,
                            state = save.saver.state,
                            notice = save.notice,
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (destination) {
                    EditorDestination.Editor -> if (compact) {
                        CanvasArea(
                            session = session,
                            preview = preview,
                            runtime = runtime,
                            modifier = Modifier.fillMaxSize().testTag("canvas"),
                        )
                    } else {
                        ExpandedEditor(
                            session = session,
                            preview = preview,
                            runtime = runtime,
                            selection = selection,
                        )
                    }
                    EditorDestination.Code -> CodeScreen(
                        session = session,
                        preview = preview,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (paletteOpen) {
            ModalBottomSheet(
                onDismissRequest = { paletteOpen = false },
                sheetState = paletteSheet,
                dragHandle = null,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    SheetTitle(
                        title = "Add component",
                        hint = "Tap one to add it to what is selected.",
                    )
                    PalettePane(
                        sections = session.palette(),
                        onInsert = { entry ->
                            // Same target the wide arrangement uses: the first legal home for
                            // this type. A drop on a specific node is the canvas's gesture, not
                            // the list's.
                            val target = session.dropTargets(entry.type).firstOrNull()
                            if (target != null) session.insert(entry.type, target)
                            scope.launch { paletteSheet.hide() }
                                .invokeOnCompletion { paletteOpen = false }
                        },
                        // A fixed height, not `heightIn`: a sheet measures its content against
                        // the window, and a lazily-sized list that took its height from the items
                        // it had composed would answer the next measure with a different one.
                        modifier = Modifier.fillMaxWidth().height(360.dp),
                    )
                }
            }
        }

        if (inspectorOpen && selected != null) {
            ModalBottomSheet(
                onDismissRequest = { inspectorOpen = false },
                sheetState = inspectorSheet,
                dragHandle = null,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    SheetTitle(title = selected.displayName, hint = selected.type.value)
                    InspectorPane(
                        session = session,
                        selection = selection,
                        // Bounded and scrollable, so a node with twenty properties stays inside
                        // the sheet instead of pushing its own heading off the top.
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    )
                }
            }
        }
    }
}

/**
 * The width at which three panes stop being one pane and a drawer.
 *
 * 600dp is the Material 3 compact/medium boundary, and the same one `WindowSizeClass` uses:
 * below it a phone is held in one hand and a sheet is the only place a second pane can go.
 */
private val CompactWidth = 600.dp

/** The three panes side by side: what a window with a desk under it shows. */
@Composable
private fun ExpandedEditor(
    session: EditorSession,
    preview: EditorPreview,
    runtime: UiRuntime,
    selection: EditorSelection,
) {
    Row(Modifier.fillMaxSize()) {
        PalettePane(
            sections = session.palette(),
            onInsert = { entry ->
                val target = session.dropTargets(entry.type).firstOrNull()
                if (target != null) session.insert(entry.type, target)
            },
            modifier = Modifier.width(240.dp).fillMaxHeight().testTag("palette"),
        )
        VerticalDivider()
        CanvasArea(
            session = session,
            preview = preview,
            runtime = runtime,
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("canvas"),
        )
        VerticalDivider()
        InspectorPane(
            session = session,
            selection = selection,
            modifier = Modifier.width(300.dp).fillMaxHeight().testTag("inspector"),
        )
    }
}

/**
 * `CanvasPane` with the resolved state read here rather than by the frame.
 *
 * A preview state changes on every keystroke in the inspector; collecting it in the shell
 * would recompose the app bar and the tabs for a change only the canvas can see.
 */
@Composable
private fun CanvasArea(
    session: EditorSession,
    preview: EditorPreview,
    runtime: UiRuntime,
    modifier: Modifier = Modifier,
) {
    val rendered: PreviewState by preview.state.collectAsState()
    CanvasPane(session = session, runtime = runtime, state = rendered, modifier = modifier)
}

/**
 * The selection's three acts, docked above the save line on a narrow screen.
 *
 * Text rather than icons: the icon set the frame is allowed to use is the core one, which has
 * a pencil and a bin but nothing for "duplicate", and three labels in a row read faster than
 * two icons and a guess. The actions are one line high so the canvas keeps the rest.
 */
@Composable
private fun SelectionActions(
    onProperties: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LutterSpacing.sm)
                .testTag("selection-actions"),
            horizontalArrangement = Arrangement.spacedBy(LutterSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onProperties) { Text("Properties") }
            TextButton(onClick = onDuplicate) { Text("Duplicate") }
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** A sheet's heading: what this sheet is, and the one line that says what to do in it. */
@Composable
private fun SheetTitle(title: String, hint: String) {
    Column(Modifier.fillMaxWidth().padding(LutterSpacing.lg)) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = hint,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What the app bar says under the document's name: what is selected, or what can be selected.
 *
 * The document's name is already the title, so repeating it here would spend the line on the
 * one fact the person can read above it.
 */
private fun selectionCaption(selected: InspectorModel?): String =
    if (selected == null) "Nothing selected" else "${selected.type.value} selected"

/**
 * A plus, drawn here because the frame has no icon dependency.
 *
 * `material-icons-core` arrives transitively with Material 3 on Android but is not part of the
 * multiplatform `material3` artifact's public surface, and a theme module that pulled in the
 * extended set for one glyph would be a megabyte of vectors for a cross. Four lines of path.
 */
private val AddGlyph: ImageVector = ImageVector.Builder(
    name = "Add",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(19f, 13f)
        horizontalLineTo(13f)
        verticalLineTo(19f)
        horizontalLineTo(11f)
        verticalLineTo(13f)
        horizontalLineTo(5f)
        verticalLineTo(11f)
        horizontalLineTo(11f)
        verticalLineTo(5f)
        horizontalLineTo(13f)
        verticalLineTo(11f)
        horizontalLineTo(19f)
        close()
    }
}.build()
