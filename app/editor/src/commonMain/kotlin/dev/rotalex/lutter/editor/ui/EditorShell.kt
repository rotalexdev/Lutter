package dev.rotalex.lutter.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.rotalex.lutter.editor.Autosave
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSelection
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.InspectorModel
import dev.rotalex.lutter.editor.PreviewState
import dev.rotalex.lutter.editor.SaveStatusBar
import dev.rotalex.lutter.editor.WorkspaceSaver
import dev.rotalex.lutter.editor.settings.EditorSettings
import dev.rotalex.lutter.editor.settings.InMemorySettingsStore
import dev.rotalex.lutter.editor.settings.SettingsStore
import dev.rotalex.lutter.editor.ui.theme.LutterSpacing
import dev.rotalex.lutter.runtime.UiRuntime
import kotlinx.coroutines.CoroutineScope
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
 * The editor's frame, drawn by both hosts: the app bar, the primary navigation, and the
 * destination the back stack is showing.
 *
 * ### Where the app is, is a stack
 *
 * The frame owns one back stack of [NavKey]s and renders it with `NavDisplay`. Editor and Code
 * are the two destinations the navigation suite offers; Settings is *pushed* on top of whichever
 * one opened it, so leaving it is a pop, the platform's back gesture performs that pop, and a
 * configuration change restores the whole stack instead of the last flag that was set.
 *
 * The alternative — a boolean that means "the settings are showing" — is what this replaces: two
 * screens fit in a flag, a third turns it into a stack, and the stack is the thing that also
 * makes back mean something.
 *
 * ### Two arrangements, one frame
 *
 * Under [CompactWidth] a `NavigationBar` sits at the bottom, the canvas gets the whole window and
 * the palette and the inspector arrive as bottom sheets — the platform's own sheet, not a
 * hand-rolled overlay, which is what makes dismissal, back navigation and the drag handle work
 * without this file doing anything (§32 Phase 17 P6). At or above it a `NavigationRail` takes the
 * leading edge and the three panes sit side by side, the arrangement a desktop window has the
 * desk for.
 *
 * ### Why the shell and not the hosts
 *
 * The hosts used to repeat this: an `EditorScaffold` in the activity, a `Column` in the window,
 * the same tabs twice, and two different places that forgot the theme. One frame means one place
 * where the navigation, the save line and the selection's actions are decided, and a new host (a
 * tablet build, a browser) is a window around this composable.
 *
 * `Autosave` is started here for the same reason: a shell that showed the save line but left the
 * writing to its host would let a host forget it, which is what the desktop window's close
 * handler already had to work around.
 *
 * ### Settings
 *
 * Reached from the app bar's "more options" menu, and drawn by [SettingsScreen] in the frame's
 * body with a back action in the bar. [settings] has an in-memory default so a test or a host
 * without a directory gets the same frame with the defaults and nothing to write.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun EditorShell(
    session: EditorSession,
    preview: EditorPreview,
    runtime: UiRuntime,
    save: EditorShellSave?,
    modifier: Modifier = Modifier,
    settings: SettingsStore = InMemorySettingsStore(),
) {
    val preferences: EditorSettings by settings.settings.collectAsState()
    // Off means the frame writes nothing on its own, so it must offer the write: an editor that
    // silently stopped saving would be losing work with a switch's worth of ceremony.
    if (save != null && preferences.autosave) Autosave(session = session, saver = save.saver)
    FollowPreview(preview)

    val selection: EditorSelection by session.selection.collectAsState()
    val history by session.controller.state.collectAsState()
    // The two keys are also the subscription. `EditorSession.inspector` reads the session's own
    // flows, which are not snapshot state, so a frame whose body never touched `selection` would
    // not redraw when one arrived — and at compact width nothing else in this body does touch
    // it. The symptom is a dock that keeps acting on the node before the one on screen.
    val selected: InspectorModel? = remember(selection, history.document) { session.inspector() }

    // The whole of "where the app is". `rememberNavBackStack` keeps it across a configuration
    // change by serialising its keys through [EditorSavedState], which is why the routes are
    // `@Serializable` and why the configuration registers each one.
    val backStack = rememberNavBackStack(EditorSavedState, EditorRoute)
    val current: NavKey = backStack.last()

    var paletteOpen: Boolean by remember { mutableStateOf(false) }
    var inspectorOpen: Boolean by remember { mutableStateOf(false) }
    // What the inspector sheet is showing. Held separately from `selected` on purpose: when the
    // selection clears, the sheet still has to draw its last node for the length of the hide
    // animation, and reading `selected` there would empty it a frame before it slid away.
    var sheetModel: InspectorModel? by remember { mutableStateOf(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val paletteSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val inspectorSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // A tap on the page's margin, or a delete, can take the selection away while the inspector
    // sheet is open over the canvas. The sheet must leave the way Material says a sheet leaves —
    // hidden first, removed after — because yanking it out from under a focused text field is
    // what closed the app: the field and the keyboard were torn down mid-animation, in the same
    // frame the dialog's own state was still settling.
    LaunchedEffect(selected) {
        if (selected != null) {
            sheetModel = selected
        } else if (inspectorOpen) {
            // Leaving the sheet is one of the ways out of a field, so the draft is committed
            // before anything moves: clearing focus runs the same `onFocusChanged` the keyboard's
            // Done does. Without this the keystrokes were in a field that no longer existed.
            focus.clearFocus()
            inspectorSheet.hide()
            inspectorOpen = false
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        val compact: Boolean = maxWidth < CompactWidth
        val onEditor: Boolean = current == EditorRoute
        Row(Modifier.fillMaxSize()) {
            if (!compact) {
                PrimaryRail(current = current, onSelect = { backStack.switchTo(it) })
                VerticalDivider()
            }
            Scaffold(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                // The insets are the frame's business on both platforms: zero on a desk, the
                // status and gesture bars on a phone. `WindowInsets(0)` on the content is what
                // keeps them from being applied a second time by the body.
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    // The bar belongs to the destination, so the route picks it: the editor's
                    // has the document, the history and the menu; the code screen's has the
                    // document and the menu; the settings' has the back action and nothing else.
                    when (current) {
                        EditorRoute -> EditorTopBar(
                            title = session.document.meta.name,
                            subtitle = selectionCaption(selected),
                            canUndo = history.canUndo,
                            canRedo = history.canRedo,
                            onUndo = { session.undo() },
                            onRedo = { session.redo() },
                            onSave = if (save != null && !preferences.autosave) {
                                saveNowAction(session = session, saver = save.saver, scope = scope)
                            } else {
                                null
                            },
                            onSettings = { backStack.push(SettingsRoute) },
                        )

                        CodeRoute -> CodeTopBar(
                            documentName = session.document.meta.name,
                            onSettings = { backStack.push(SettingsRoute) },
                        )

                        else -> SettingsTopBar(onBack = { backStack.pop() })
                    }
                },
                bottomBar = {
                    Column {
                        // The selection's actions, where a thumb already is. Wide windows put
                        // the same actions in the inspector that is already open.
                        if (compact && onEditor && selected != null) {
                            SelectionActions(
                                onProperties = {
                                    sheetModel = selected
                                    inspectorOpen = true
                                },
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
                        // Last, and only on a phone: a navigation bar is the bottom edge of a
                        // Material app, and everything the frame adds sits above it.
                        if (compact) {
                            PrimaryBar(current = current, onSelect = { backStack.switchTo(it) })
                        }
                    }
                },
                floatingActionButton = {
                    // The palette is already on screen when the window is wide, so a button that
                    // opened the same list twice would be the only redundant thing in the frame.
                    if (compact && onEditor) {
                        FloatingActionButton(
                            onClick = { paletteOpen = true },
                            modifier = Modifier.testTag("palette-open"),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add component")
                        }
                    }
                },
            ) { padding ->
                NavDisplay(
                    backStack = backStack,
                    // Back at the root returns `false` and the platform closes the window; the
                    // frame never pops its own last entry.
                    onBack = { backStack.pop() },
                    modifier = Modifier.fillMaxSize().padding(padding),
                    entryProvider = entryProvider {
                        entry<EditorRoute> {
                            if (compact) {
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
                        }

                        entry<CodeRoute> {
                            CodeScreen(
                                session = session,
                                preview = preview,
                                textScale = preferences.codeTextScale,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<SettingsRoute> {
                            SettingsScreen(
                                settings = preferences,
                                onChange = { next -> scope.launch { settings.update { next } } },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    },
                )
            }
        }

        if (paletteOpen) {
            ModalBottomSheet(
                onDismissRequest = { dismiss(paletteSheet, scope) { paletteOpen = false } },
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
                        // The tag is the same one the wide arrangement carries, so a host's test
                        // finds one pane whether it arrived as a column or as a sheet.
                        modifier = Modifier.fillMaxWidth().height(360.dp).testTag("palette"),
                    )
                }
            }
        }

        val inspector: InspectorModel? = sheetModel
        if (inspectorOpen && inspector != null) {
            ModalBottomSheet(
                onDismissRequest = { dismiss(inspectorSheet, scope) { inspectorOpen = false } },
                sheetState = inspectorSheet,
                dragHandle = null,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    SheetTitle(title = inspector.displayName, hint = inspector.type.value)
                    InspectorPane(
                        session = session,
                        selection = selection,
                        // Bounded and scrollable, so a node with twenty properties stays inside
                        // the sheet instead of pushing its own heading off the top.
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).testTag("inspector"),
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

/**
 * Dismisses [sheet] and then removes its host, in that order — or leaves both alone.
 *
 * `onDismissRequest` fires when the scrim is tapped, when the sheet is swiped away and when the
 * back gesture arrives, and Material's contract for it is that the *caller* hides the sheet. The
 * flag that composes the sheet belongs to the caller too, which is the trap: setting that flag
 * directly removes a `ModalBottomSheet` mid-animation, with its scrim, its dialog state and a
 * focused field still settling — the same teardown that closed the app when a tap on the page's
 * margin cleared the selection. The scrim covers the canvas, so "tapping the empty space" while
 * a sheet is open is exactly this path.
 *
 * The removal is conditional on the hide having finished: a dismissal that was cancelled — the
 * sheet shown again, the window going away — is not a dismissal, and removing the host then would
 * take a sheet that is still on screen out of the composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun dismiss(sheet: SheetState, scope: CoroutineScope, onHidden: () -> Unit) {
    scope.launch { sheet.hide() }.invokeOnCompletion { cause ->
        if (removesHostAfter(cause, sheet.isVisible)) onHidden()
    }
}

/**
 * Whether a dismissal that finished with [cause] leaves the sheet off screen.
 *
 * Two facts, both required: the hide ran to the end ([cause] is null — a cancelled animation is
 * not a dismissal) and the sheet is no longer visible. Either alone would remove a
 * `ModalBottomSheet` that is still on screen, which is the teardown this rule exists to prevent.
 */
internal fun removesHostAfter(cause: Throwable?, visible: Boolean): Boolean =
    cause == null && !visible

/** The leading-edge navigation a window with a desk under it shows. */
@Composable
private fun PrimaryRail(current: NavKey, onSelect: (NavKey) -> Unit) {
    NavigationRail(modifier = Modifier.fillMaxHeight()) {
        Spacer(Modifier.height(LutterSpacing.sm))
        PrimaryRoutes.forEach { route ->
            NavigationRailItem(
                selected = current == route,
                onClick = { onSelect(route) },
                icon = { Icon(iconOf(route), contentDescription = null) },
                label = { Text(labelOf(route)) },
                modifier = Modifier.testTag(navigationTag(route)),
            )
        }
    }
}

/** The bottom-edge navigation a phone shows. */
@Composable
private fun PrimaryBar(current: NavKey, onSelect: (NavKey) -> Unit) {
    NavigationBar {
        PrimaryRoutes.forEach { route ->
            NavigationBarItem(
                selected = current == route,
                onClick = { onSelect(route) },
                icon = { Icon(iconOf(route), contentDescription = null) },
                label = { Text(labelOf(route)) },
                modifier = Modifier.testTag(navigationTag(route)),
            )
        }
    }
}

/** What a host's test looks for when it taps a destination in either arrangement. */
private fun navigationTag(route: NavKey): String = "nav:${labelOf(route).lowercase()}"

/** The glyph a destination is listed with. */
private fun iconOf(route: NavKey): ImageVector = when (route) {
    CodeRoute -> Icons.Filled.Code
    else -> Icons.Filled.Dashboard
}

/**
 * The deferred write, as the action the bar offers when autosave is off.
 *
 * The same call `Autosave` makes, because it is the same write: one document, one location, no
 * second path to keep correct.
 */
private fun saveNowAction(
    session: EditorSession,
    saver: WorkspaceSaver,
    scope: CoroutineScope,
): () -> Unit = {
    val document = session.document
    val where = session.selection.value
    scope.launch { saver.save(document, where) }
}

/** The editor's bar: what is open, the history, the deferred write and the menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    title: String,
    subtitle: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: (() -> Unit)?,
    onSettings: () -> Unit,
) {
    TopAppBar(
        windowInsets = WindowInsets(0, 0, 0, 0),
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
            if (onSave != null) {
                IconButton(onClick = onSave, modifier = Modifier.testTag("save-now")) {
                    Icon(Icons.Filled.Save, contentDescription = "Save now")
                }
            }
            MoreOptions(onSettings = onSettings)
        },
    )
}

/** The code screen's bar: the same menu, without an editor's actions to act on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeTopBar(documentName: String, onSettings: () -> Unit) {
    TopAppBar(
        windowInsets = WindowInsets(0, 0, 0, 0),
        title = {
            Column {
                Text(
                    text = "Code",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = documentName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = { MoreOptions(onSettings = onSettings) },
    )
}

/** The settings bar: a way back, and the name of what is open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    TopAppBar(
        windowInsets = WindowInsets(0, 0, 0, 0),
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("settings-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
    )
}

/**
 * The app bar's overflow: one item today, and the place the next one goes.
 *
 * Settings is not a tab because it is not somewhere the editor is — it is something the frame
 * opens over it, and a bar that listed it beside the two screens would say otherwise.
 */
@Composable
private fun MoreOptions(onSettings: () -> Unit) {
    var open: Boolean by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.testTag("more-options"),
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                onClick = {
                    open = false
                    onSettings()
                },
                modifier = Modifier.testTag("settings-menu-item"),
            )
        }
    }
}

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
 * would recompose the app bar and the navigation for a change only the canvas can see.
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
 * Text rather than icons: "Properties" has no glyph in the set that says it better than the word,
 * and three labels in a row read faster than two icons and a guess. The actions are one line high
 * so the canvas keeps the rest.
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
