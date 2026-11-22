package dev.rotalex.lutter.editor

import dev.rotalex.lutter.editing.DocumentController
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SequentialIdGenerator
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
        ): EditorSession = EditorSession(DocumentController(document, schema, ids), schema)
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
