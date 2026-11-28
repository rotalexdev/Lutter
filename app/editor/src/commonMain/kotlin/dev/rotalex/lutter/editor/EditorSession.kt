package dev.rotalex.lutter.editor

import dev.rotalex.lutter.editing.DocumentController
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.editing.policy.DropTarget
import dev.rotalex.lutter.editing.policy.DropTargets
import dev.rotalex.lutter.editing.template.IdRemapper
import dev.rotalex.lutter.editing.template.NodeTemplate
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SequentialIdGenerator
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a property write did.
 *
 * Distinct from [CommandResult] on purpose: a commit can fail *before* a command exists (the
 * text is not an expression), and a sealed set that only the field produces is how the UI
 * knows where to show the failure — beside the field — rather than in the document's
 * diagnostics.
 */
public sealed interface PropertyWrite {

    /** The value was accepted and the patch applied. */
    public data class Applied(public val patch: Patch) : PropertyWrite

    /** The planner refused the command; [reasons] are the engine's own diagnostics. */
    public data class Refused(public val reasons: List<Diagnostic>) : PropertyWrite

    /** The text is not an expression; nothing was edited. */
    public data class Unparseable(public val message: String) : PropertyWrite
}

/**
 * The editor's session: a controller, a selection, and the three questions a UI asks of them.
 *
 * ### Why this class exists at all
 *
 * `:engine:editing` answers "what does this command do"; it deliberately does not answer "what
 * is the palette" or "which properties does the thing I selected have". Those are editor
 * questions — §25.4 tabulates them as *editor needs → engine API* and gives each one an engine
 * call, not an engine type — and this class is the one place they are answered, so the hosts
 * (`desktopApp/`, `androidApp/`) hold no logic that could disagree with each other.
 *
 * ### The selection is the session's only state
 *
 * It lives here and not in a `DocumentController`, because a controller's state is the
 * document: which node a person clicked is not a fact about the document, it must not enter
 * history, and it must not survive a command that removes the node. [dispatch] is therefore
 * the one method that both edits and reconciles the selection — a UI that had to remember to
 * re-check after every command would forget once, and the failure is an inspector showing a
 * node that is gone.
 *
 * **History is not persisted** (§26.3): a session is reopened with an empty undo stack, which
 * is why [open] builds a fresh controller rather than accepting one whose history it cannot
 * inspect.
 */
public class EditorSession(

    /** The single mutation gateway (§25.1). Every edit goes through [dispatch]. */
    public val controller: DocumentController<ActionSpec, FunctionSpec, TypeSpec>,

    /** What the palette and the inspector read. */
    public val schema: SchemaView<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,

    /**
     * Where a freshly inserted node's id comes from.
     *
     * The default is a fresh sequence, and that is safe rather than merely convenient:
     * [IdRemapper] excludes every id the document holds at the moment it is asked, and the
     * document grows after each insert. Sharing one generator with the controller (which
     * [open] does) is the stronger contract and the one §28.3's determinism claim prefers.
     */
    private val ids: IdGenerator = SequentialIdGenerator(),
) {

    private val selectionFlow: MutableStateFlow<EditorSelection> = MutableStateFlow(EditorSelection.None)

    /** What the canvas and the inspector are pointing at. */
    public val selection: StateFlow<EditorSelection> = selectionFlow.asStateFlow()

    /** The document as the controller currently holds it, never a copy this class maintains. */
    public val document: UiDocument get() = controller.state.value.document

    /** Selects [value], which is a plain assignment: a selection cannot be illegal. */
    public fun select(value: EditorSelection): Unit {
        selectionFlow.value = value
    }

    /** The component palette, from [schema]'s own metadata (see [Palette.of]). */
    public fun palette(): List<PaletteSection> = Palette.of(schema)

    /**
     * The inspector's rows for the selected node, or null when nothing is selected or the
     * selection names a node this document does not contain.
     */
    public fun inspector(): InspectorModel? {
        val selected = selectionFlow.value as? EditorSelection.Node ?: return null
        return Inspector.of(document, schema, selected.id)
    }

    /**
     * Every place a [type] may be inserted **right now**, straight from
     * [DropTargets.forNode] — the same check the planner will run, asked once.
     *
     * The list is a snapshot, not a reservation: the caller renders it, and a later edit can
     * invalidate it. §25.3 accepts that because the planner is still the backstop.
     */
    public fun dropTargets(type: ComponentType): List<DropTarget> =
        DropTargets.forNode(document, schema, type)

    /**
     * [type]'s palette template placed at [into], selected once it lands.
     *
     * The subtree is [paletteSubtree]'s: the component plus the placeholder without which it draws
     * nothing, derived from the schema. Properties are still `analysis`'s to default and a required
     * property is still `analysis`'s to complain about — what the template adds is *structure*, the
     * child a button needs to say anything, and the one string a text needs to be a text.
     *
     * It used to insert one bare node, and on a phone that read as a broken app: seven taps, seven
     * empty buttons, no words. The doc-comment that defended it was right about values and wrong
     * about children.
     */
    public fun insert(type: ComponentType, into: DropTarget): CommandResult {
        val remapper = IdRemapper(document, ids)
        val fresh: NodeId = remapper.fresh()
        val subtree: NodeTemplate = paletteSubtree(schema, type, fresh, remapper::fresh)
        val result = dispatch(
            Command.InsertNode(
                parent = into.parent,
                slot = into.slot,
                index = into.index,
                subtree = subtree,
            ),
        )
        if (result is CommandResult.Ok) selectionFlow.value = EditorSelection.Node(fresh)
        return result
    }

    /**
     * A copy of [node]'s subtree, selected once it lands.
     *
     * The copy's id is the planner's to mint, so it is read back off the patch rather than
     * predicted: the patch is the record of what actually happened, and a second guess at the
     * id would be a second answer to the same question.
     */
    public fun duplicate(node: NodeId): CommandResult {
        val result = dispatch(Command.DuplicateNode(node))
        if (result is CommandResult.Ok) {
            val copy = result.patch.ops
                .filterIsInstance<PatchOp.InsertNodes>()
                .firstOrNull()?.nodes?.firstOrNull()?.id
            if (copy != null) selectionFlow.value = EditorSelection.Node(copy)
        }
        return result
    }

    /** [node] repositioned at [into], and still selected afterwards. */
    public fun move(node: NodeId, into: DropTarget): CommandResult {
        val result = dispatch(
            Command.MoveNode(id = node, newParent = into.parent, slot = into.slot, index = into.index),
        )
        if (result is CommandResult.Ok) selectionFlow.value = EditorSelection.Node(node)
        return result
    }

    /**
     * [node]'s subtree lifted out. The selection is [dispatch]'s business: if it named this
     * node, reconciliation clears it.
     */
    public fun remove(node: NodeId): CommandResult = dispatch(Command.RemoveNode(node))

    /**
     * One step back through the history, with the selection reconciled.
     *
     * The controller owns the stack, but reconciliation is the session's: undoing an insert
     * leaves the selection pointing at a node that no longer exists, and a shell that called
     * the controller directly would show an inspector for a ghost. `false` means there was
     * nothing to undo, which is how the frame decides whether the action is available.
     */
    public fun undo(): Boolean {
        val undone: Boolean = controller.undo()
        reconcile()
        return undone
    }

    /** One step forward again, on the same terms as [undo]. */
    public fun redo(): Boolean {
        val redone: Boolean = controller.redo()
        reconcile()
        return redone
    }

    /**
     * One command, planned and applied by the controller, with the selection reconciled.
     *
     * A refused command changes nothing, and the selection is reconciled either way: a
     * refusal is a fact about one command, not a reason to forget what was selected.
     */
    public fun dispatch(command: Command, label: String = ""): CommandResult {
        val result: CommandResult = controller.dispatch(command, label)
        reconcile()
        return result
    }

    /**
     * [node] moved by a drag delta, in dp, as one command and therefore one undo step.
     *
     * The node's own offset is the source of truth: a drag that starts on an already-placed node
     * adds to it, so two gestures compose instead of the second erasing the first. Both axes are
     * written every time because `layout.offset` requires a pair — a document that names one axis
     * has not said where the node goes.
     */
    public fun moveBy(node: NodeId, dx: Float, dy: Float): CommandResult {
        val placed = document.nodes[node]?.modifiers?.firstOrNull { it.type == Placement.offset }
        val x: Float = placed?.args?.get(Placement.x).asDp() + dx
        val y: Float = placed?.args?.get(Placement.y).asDp() + dy
        return dispatch(
            Command.SetModifierArgs(
                node = node,
                type = Placement.offset,
                args = mapOf(
                    Placement.x to PropertyValue.Const(Value.Dp(x)),
                    Placement.y to PropertyValue.Const(Value.Dp(y)),
                ),
            ),
        )
    }

    /**
     * [node] resized to [width] × [height] dp, moved by the handle's shift, as one undo step.
     *
     * A resize from a left or top grip changes the size *and* the origin — the opposite edge is
     * what stays still — so the two commands run inside one transaction: one gesture is one
     * entry in the undo menu, not two. The shift is a delta, so a second resize composes with the
     * offset the first one wrote.
     */
    public fun resizeBy(
        node: NodeId,
        width: Float,
        height: Float,
        shiftX: Float,
        shiftY: Float,
    ): CommandResult {
        val placed = document.nodes[node]?.modifiers?.firstOrNull { it.type == Placement.offset }
        val x: Float = placed?.args?.get(Placement.x).asDp() + shiftX
        val y: Float = placed?.args?.get(Placement.y).asDp() + shiftY

        val outcome: CommandResult = controller.transaction("Resize") {
            val sized: CommandResult = dispatch(
                Command.SetModifierArgs(
                    node = node,
                    type = Placement.size,
                    args = mapOf(
                        Placement.width to PropertyValue.Const(Value.Dp(width)),
                        Placement.height to PropertyValue.Const(Value.Dp(height)),
                    ),
                ),
            )
            if (sized is CommandResult.Rejected) return@transaction sized
            // A right or bottom grip moves no origin, and writing an offset of zero would add a
            // modifier the node never needed — the size alone is the whole gesture.
            if (shiftX == 0f && shiftY == 0f) return@transaction sized
            dispatch(
                Command.SetModifierArgs(
                    node = node,
                    type = Placement.offset,
                    args = mapOf(
                        Placement.x to PropertyValue.Const(Value.Dp(x)),
                        Placement.y to PropertyValue.Const(Value.Dp(y)),
                    ),
                ),
            )
        }
        reconcile()
        return outcome
    }

    /**
     * The expression-field commit for one property: parse, then dispatch.
     *
     * A refusal is surfaced with the engine's own reasons rather than as a generic failure,
     * because the field it came from is the place the person looks: the planner's sentence about
     * why this command is illegal belongs beside the field that produced it.
     */
    public fun setProperty(node: NodeId, key: PropertyKey, text: String): PropertyWrite {
        val parsed = PropertyText.apply(text)
        if (parsed is PropertyEdit.Unparseable) {
            return PropertyWrite.Unparseable(parsed.message)
        }
        val value = (parsed as PropertyEdit.Value).value
        return when (val result = dispatch(Command.SetProperty(node, key, value))) {
            is CommandResult.Ok -> PropertyWrite.Applied(result.patch)
            is CommandResult.Rejected -> PropertyWrite.Refused(result.reasons)
        }
    }

    public companion object {

        /**
         * A session over [document], with a fresh controller and therefore an empty undo stack
         * (§26.3: history is not persisted, and a reopened document inherits nothing).
         *
         * [ids] is a parameter for §28.3's reason: a scripted session that planned the same
         * command twice must produce the same ids.
         */
        public fun open(
            document: UiDocument,
            schema: SchemaView<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
            ids: IdGenerator = SequentialIdGenerator(),
        ): EditorSession = EditorSession(DocumentController(document, schema, ids), schema, ids)
    }

    private fun reconcile(): Unit {
        val current = selectionFlow.value
        val gone: Boolean = when (current) {
            EditorSelection.None -> false
            is EditorSelection.Node -> current.id !in document.nodes
            is EditorSelection.Page -> current.id !in document.pages
        }
        if (gone) selectionFlow.value = EditorSelection.None
    }
}

/** A property's dp value, or zero when the entry is absent or not a constant. */
private fun PropertyValue?.asDp(): Float = ((this as? PropertyValue.Const)?.value as? Value.Dp)?.v ?: 0f
