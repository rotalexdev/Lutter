package dev.rotalex.lutter.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.editing.policy.DropTarget
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSelection
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.CanvasBox
import dev.rotalex.lutter.editor.EditorViewport
import dev.rotalex.lutter.editor.Gap
import dev.rotalex.lutter.editor.Guide
import dev.rotalex.lutter.editor.Handle
import dev.rotalex.lutter.editor.InspectorModel
import dev.rotalex.lutter.editor.PaletteEntry
import dev.rotalex.lutter.editor.PaletteSection
import dev.rotalex.lutter.editor.PreviewState
import dev.rotalex.lutter.editor.Resize
import dev.rotalex.lutter.editor.Resizing
import dev.rotalex.lutter.editor.Snap
import dev.rotalex.lutter.editor.Snapping
import dev.rotalex.lutter.editor.ViewPoint
import dev.rotalex.lutter.editor.PropertyEdit
import dev.rotalex.lutter.editor.PropertyRow
import dev.rotalex.lutter.editor.PropertyText
import dev.rotalex.lutter.editor.PropertyWrite
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.runtime.BackStackNavigator
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.UiApp
import dev.rotalex.lutter.runtime.UiRuntime
import kotlin.math.roundToInt

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
 * ### Two ways in, one command
 *
 * A palette click asks [EditorSession.dropTargets] for the first place the type fits and inserts
 * there; a long press on a node and a drop on a container asks the same question and calls
 * [EditorSession.move]. The click is what makes the pane usable and testable without a gesture,
 * and the drag is what makes it an editor. Both go through `dispatch`, so an illegal drop is
 * refused by the planner rather than by the gesture.
 *
 * Each pane carries a `testTag` so a host's UI test can find it: `palette`, `canvas`,
 * `inspector`, `palette:<type>`, `inspector:<key>`. The canvas adds `canvas-surface` (the overlay
 * that owns pointers), `canvas-selection` and `canvas-drop-target` (the two outlines).
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
    session: EditorSession,
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
                    // One recorder per document: its boxes describe *this* tree, and a map kept
                    // across documents would draw the new page's selection ring where the old
                    // page's node used to be.
                    val bounds = remember(document) { NodeBounds() }
                    val environment = remember(document, navigator, bounds) {
                        RuntimeEnvironment(navigator = navigator, hooks = bounds)
                    }
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = theme.surface,
                        shape = MaterialTheme.shapes.medium,
                        shadowElevation = 1.dp,
                    ) {
                        EditorCanvas(
                            session = session,
                            runtime = runtime,
                            resolved = state.resolved,
                            document = document,
                            environment = environment,
                            bounds = bounds.measured,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The page, plus the two things an editor owes it: a selection you can see and a drag that moves.
 *
 * ### Why an overlay, and not a clickable on the preview
 *
 * `UiApp` is the real renderer, so its `m3.Button` really is a button. In edit mode a tap means
 * "select this node", not "press it" — a preview that runs the app while you are editing it is
 * how an author ends up unable to reach the thing they just dropped. The overlay therefore sits
 * above the preview and owns every pointer, which is the split the Android Studio layout editor
 * makes too: design mode, and a separate way to run.
 *
 * ### Why the coordinates are converted
 *
 * [bounds] are in root coordinates (the window), and a gesture position is local to this box.
 * `originInRoot` is the conversion, captured from the box itself, so a top bar or a palette above
 * the canvas does not shift every hit by its height.
 *
 * ### Why long-press to drag
 *
 * A plain drag on a canvas is also how a person scrolls it. `detectDragGesturesAfterLongPress`
 * makes "pick this up" an explicit act, and leaves the plain drag for panning, which starts only
 * on the page's margin so a drag that begins on a node still belongs to the reorder.
 */
@Composable
private fun EditorCanvas(
    session: EditorSession,
    runtime: UiRuntime,
    resolved: ResolvedDocument,
    document: UiDocument,
    environment: RuntimeEnvironment,
    bounds: Map<NodeId, Rect>,
) {
    val selection: EditorSelection by session.selection.collectAsState()
    val theme = MaterialTheme.colorScheme
    var originInRoot: Offset by remember { mutableStateOf(Offset.Zero) }
    var drag: Drag by remember(document) { mutableStateOf<Drag>(Drag.None) }
    var viewport: EditorViewport by remember(document) { mutableStateOf(EditorViewport()) }
    val density: Density = LocalDensity.current

    // What the drag would commit if released now: the raw delta corrected to the nearest
    // alignment, plus the lines and distances to draw. Computed once per frame from the same
    // values the release uses, so the preview cannot promise a place the commit does not take.
    fun snapOf(move: Drag.Move): Snap {
        val moving: CanvasBox = bounds[move.node]?.toBox() ?: return Snap(move.delta.x, move.delta.y)
        val own: Set<NodeId> = subtreeOf(document, move.node)
        val others: List<CanvasBox> = bounds.filterKeys { it !in own }.values.map { it.toBox() }
        return Snapping.snap(
            moving = moving,
            others = others,
            delta = ViewPoint(move.delta.x, move.delta.y),
            threshold = with(density) { 6.dp.toPx() },
        )
    }

    // The size a handle drag would commit, in dp. The measured box is in screen pixels and
    // already carries the viewport's zoom, so dividing by the px-per-dp factor is all it takes to
    // put the arithmetic in the unit the document stores.
    fun resizeOf(resize: Drag.Resize): Resize? {
        val box: Rect = bounds[resize.node] ?: return null
        val perDp: Float = density.density * viewport.scale
        if (perDp <= 0f) return null
        return Resizing.apply(
            width = box.width / perDp,
            height = box.height / perDp,
            handle = resize.handle,
            delta = ViewPoint(resize.delta.x / perDp, resize.delta.y / perDp),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { originInRoot = it.boundsInRoot().topLeft },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = viewport.scale
                    scaleY = viewport.scale
                    translationX = viewport.translationX
                    translationY = viewport.translationY
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            key(document) {
                UiApp(runtime, resolved, environment)
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .testTag("canvas-surface")
                .pointerInput(Unit) {
                    // The wheel zooms about the pointer, so the node under it stays under it.
                    // `scrollDelta` is common API, which is what keeps this in commonMain while
                    // the desktop window is the only host with a wheel today.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val scroll = change.scrollDelta
                            if (scroll.y == 0f) continue
                            change.consume()
                            viewport = viewport.zoomedAt(
                                anchor = ViewPoint(change.position.x, change.position.y),
                                factor = if (scroll.y < 0f) {
                                    EditorViewport.Step
                                } else {
                                    1f / EditorViewport.Step
                                },
                            )
                        }
                    }
                }
                .pointerInput(document) {
                    // A plain drag pans, but only when it starts on the page's margin: a drag
                    // that starts on a node belongs to the long-press reorder, and taking it
                    // here would pan instead of moving the node.
                    var panning = false
                    detectDragGestures(
                        onDragStart = { start ->
                            panning =
                                deepestNodeAt(document, bounds, start + originInRoot) == null
                        },
                        onDrag = { change, delta ->
                            if (panning) {
                                change.consume()
                                viewport = viewport.panned(delta.x, delta.y)
                            }
                        },
                        onDragEnd = { panning = false },
                        onDragCancel = { panning = false },
                    )
                }
                .pointerInput(document) {
                    detectTapGestures { position ->
                        val hit = deepestNodeAt(document, bounds, position + originInRoot)
                        // A tap on the page's margin clears the selection, the way every layout
                        // editor does it: the alternative is a dock that keeps acting on a node
                        // the person has stopped looking at.
                        session.select(
                            if (hit == null) EditorSelection.None else EditorSelection.Node(hit),
                        )
                    }
                }
                .pointerInput(document) {
                    // A plain drag moves the node it starts on; a drag on the page's margin pans,
                    // and one that begins with a hold belongs to the reorder below. The delta
                    // accumulates in screen pixels and is committed once, on release, which is
                    // what makes one gesture one undo step rather than one per frame.
                    val density: Density = this
                    var moving: Drag.Move? = null
                    detectDragGestures(
                        onDragStart = { start ->
                            val id = deepestNodeAt(document, bounds, start + originInRoot)
                            moving = id?.let { Drag.Move(it, Offset.Zero) }
                            drag = moving ?: Drag.None
                        },
                        onDrag = { change, amount ->
                            val current = moving
                            if (current != null) {
                                change.consume()
                                moving = current.copy(delta = current.delta + amount)
                                drag = moving ?: Drag.None
                            }
                        },
                        onDragEnd = {
                            val current = moving
                            if (current != null && (current.delta.x != 0f || current.delta.y != 0f)) {
                                val snap = snapOf(current)
                                session.moveBy(
                                    node = current.node,
                                    dx = with(density) { snap.dx.toDp().value },
                                    dy = with(density) { snap.dy.toDp().value },
                                )
                            }
                            moving = null
                            drag = Drag.None
                        },
                        onDragCancel = {
                            moving = null
                            drag = Drag.None
                        },
                    )
                }
                .pointerInput(document) {
                    // The list of legal homes is read once per drag, at its start: the document
                    // cannot change while a finger is down (every edit goes through the overlay
                    // this gesture owns), and asking the planner every frame would answer the
                    // same question dozens of times.
                    var targets: List<DropTarget> = emptyList()

                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            val point = position + originInRoot
                            val id = deepestNodeAt(document, bounds, point)
                            val type: ComponentType? = id?.let { document.nodes[it]?.type }
                            if (id != null && type != null) {
                                val own = subtreeOf(document, id)
                                targets = session.dropTargets(type).filterNot { it.parent in own }
                                drag = Drag.Reorder(
                                    node = id,
                                    target = dropTargetAt(document, bounds, targets, point),
                                )
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val current = drag
                            if (current is Drag.Reorder) {
                                drag = current.copy(
                                    target = dropTargetAt(
                                        document = document,
                                        bounds = bounds,
                                        targets = targets,
                                        point = change.position + originInRoot,
                                    ),
                                )
                            }
                        },
                        onDragEnd = {
                            val current = drag
                            if (current is Drag.Reorder) {
                                val target = current.target
                                if (target != null) session.move(current.node, target)
                            }
                            drag = Drag.None
                        },
                        onDragCancel = { drag = Drag.None },
                    )
                },
        )

        // The drop target first, so the selection ring is drawn on top of it: the node being
        // moved is usually inside the container it is about to land in.
        (drag as? Drag.Reorder)?.target?.let { target ->
            Outline(
                box = bounds[target.parent],
                originInRoot = originInRoot,
                color = theme.tertiary,
                fill = theme.tertiary.copy(alpha = 0.10f),
                tag = "canvas-drop-target",
            )
        }
        (drag as? Drag.Move)?.let { move ->
            val snap = snapOf(move)
            Outline(
                box = bounds[move.node]?.translate(snap.dx, snap.dy),
                originInRoot = originInRoot,
                color = theme.secondary,
                fill = theme.secondary.copy(alpha = 0.12f),
                tag = "canvas-move-preview",
            )
            snap.guides.forEach { guide ->
                GuideLine(guide, originInRoot, theme.secondary)
            }
            snap.gaps.forEach { gap ->
                GapLabel(gap, originInRoot, density)
            }
        }
        (drag as? Drag.Resize)?.let { resizing ->
            val box: Rect? = bounds[resizing.node]
            val resized: Resize? = resizeOf(resizing)
            if (box != null && resized != null) {
                val perDp: Float = density.density * viewport.scale
                val left: Float = box.left + resized.shiftX * perDp
                val top: Float = box.top + resized.shiftY * perDp
                Outline(
                    box = Rect(left, top, left + resized.width * perDp, top + resized.height * perDp),
                    originInRoot = originInRoot,
                    color = theme.secondary,
                    fill = theme.secondary.copy(alpha = 0.12f),
                    tag = "canvas-resize-preview",
                )
            }
        }
        val selected: NodeId? = (selection as? EditorSelection.Node)?.id
        Outline(
            box = selected?.let { bounds[it] },
            originInRoot = originInRoot,
            color = theme.primary,
            fill = theme.primary.copy(alpha = 0.06f),
            tag = "canvas-selection",
        )

        // The grips come last so they sit above the surface's own gestures: a drag that starts on
        // one belongs to the resize, and the canvas below never sees it.
        val selectedBox: Rect? = selected?.let { bounds[it] }
        if (selected != null && selectedBox != null && drag == Drag.None) {
            Handle.entries.forEach { handle ->
                ResizeHandleGrip(
                    handle = handle,
                    box = selectedBox,
                    originInRoot = originInRoot,
                    onStart = { drag = Drag.Resize(selected, handle, Offset.Zero) },
                    onDrag = { amount ->
                        val current: Drag = drag
                        if (current is Drag.Resize) {
                            drag = current.copy(delta = current.delta + amount)
                        }
                    },
                    onEnd = {
                        val current: Drag = drag
                        if (current is Drag.Resize) {
                            val resized: Resize? = resizeOf(current)
                            if (resized != null) {
                                session.resizeBy(
                                    node = current.node,
                                    width = resized.width,
                                    height = resized.height,
                                    shiftX = resized.shiftX,
                                    shiftY = resized.shiftY,
                                )
                            }
                        }
                        drag = Drag.None
                    },
                )
            }
        }
    }
}

/** What a finger is doing on the canvas. A value, so a drag has no half-set fields. */
private sealed interface Drag {
    data object None : Drag

    /** A long-press drag, choosing the container the node will land in. */
    data class Reorder(val node: NodeId, val target: DropTarget?) : Drag

    /** A plain drag, moving the node by a screen-pixel delta it commits on release. */
    data class Move(val node: NodeId, val delta: Offset) : Drag

    /** A drag on one of the eight grips, in screen pixels until the release turns it into dp. */
    data class Resize(val node: NodeId, val handle: Handle, val delta: Offset) : Drag
}

/** How large a grip is drawn, in dp. Small enough not to cover the edge it grabs. */
private val HandleSize = 12.dp

/**
 * One grip on the selected node's box.
 *
 * The grip only reports; it owns no state. The drag delta lives on the canvas so the preview and
 * the commit read the same value — a grip with its own accumulator would let the two disagree by
 * however many frames the release took.
 */
@Composable
private fun ResizeHandleGrip(
    handle: Handle,
    box: Rect,
    originInRoot: Offset,
    onStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    val density: Density = LocalDensity.current
    val half: Float = with(density) { HandleSize.toPx() } / 2f
    val x: Float = (when {
        handle.left -> box.left
        handle.right -> box.right
        else -> box.center.x
    } - originInRoot.x) - half
    val y: Float = (when {
        handle.top -> box.top
        handle.bottom -> box.bottom
        else -> box.center.y
    } - originInRoot.y) - half
    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(HandleSize)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.primary)
            .pointerInput(handle, box) {
                detectDragGestures(
                    onDragStart = { onStart() },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    },
                    onDragEnd = { onEnd() },
                    onDragCancel = { onEnd() },
                )
            }
            .testTag("canvas-handle-${handle.tag}"),
    )
}

/**
 * One measured box drawn where the node actually is.
 *
 * A no-op when [box] is null, which is the honest answer for "not measured yet": a ring drawn at
 * the origin would be a claim about a node that has not been laid out.
 */
@Composable
private fun Outline(
    box: Rect?,
    originInRoot: Offset,
    color: Color,
    fill: Color,
    tag: String,
) {
    if (box == null) return
    val density: Density = LocalDensity.current
    val local: Rect = box.translate(-originInRoot.x, -originInRoot.y)
    Box(
        modifier = Modifier
            .offset { IntOffset(local.left.roundToInt(), local.top.roundToInt()) }
            .size(
                width = with(density) { local.width.toDp() },
                height = with(density) { local.height.toDp() },
            )
            .background(fill)
            .border(2.dp, color, MaterialTheme.shapes.extraSmall)
            .testTag(tag),
    )
}

/**
 * One alignment line, drawn across the boxes that share it.
 *
 * A one-pixel line in a translucent colour: the guide is a measurement, not a decoration, so it
 * never competes with the node being dragged.
 */
@Composable
private fun GuideLine(guide: Guide, originInRoot: Offset, color: Color) {
    val length: Float = guide.to - guide.from
    if (length <= 0f) return
    val start: Float = guide.at - if (guide.vertical) originInRoot.x else originInRoot.y
    val from: Float = guide.from - if (guide.vertical) originInRoot.y else originInRoot.x
    Box(
        modifier = Modifier
            .offset { IntOffset(start.roundToInt(), from.roundToInt()) }
            .then(if (guide.vertical) Modifier.width(1.dp) else Modifier.height(1.dp))
            .then(
                if (guide.vertical) {
                    Modifier.height(with(LocalDensity.current) { length.toDp() })
                } else {
                    Modifier.width(with(LocalDensity.current) { length.toDp() })
                },
            )
            .background(color.copy(alpha = 0.7f)),
    )
}

/**
 * The distance between the dragged box and a neighbour, as the number a person would type.
 *
 * Placed at the middle of the gap so it reads as belonging to it; the value is converted back to
 * dp, which is the unit the document stores and the inspector shows.
 */
@Composable
private fun GapLabel(gap: Gap, originInRoot: Offset, density: Density) {
    val at: Float = gap.at - if (gap.vertical) originInRoot.x else originInRoot.y
    val along: Float = gap.along - if (gap.vertical) originInRoot.y else originInRoot.x
    val text: String = with(density) { gap.size.toDp().value.roundToInt() }.toString()
    Box(
        modifier = Modifier
            .offset {
                if (gap.vertical) {
                    IntOffset(at.roundToInt() - 12, along.roundToInt() - 8)
                } else {
                    IntOffset(along.roundToInt() - 12, at.roundToInt() - 8)
                }
            }
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The same four numbers, in the type the snap arithmetic takes. */
private fun Rect.toBox(): CanvasBox = CanvasBox(left, top, right, bottom)

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
