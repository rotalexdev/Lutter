package dev.rotalex.lutter.editing.command

import dev.rotalex.lutter.editing.command.planners.planAddPage
import dev.rotalex.lutter.editing.command.planners.planApplyPatch
import dev.rotalex.lutter.editing.command.planners.planDuplicateNode
import dev.rotalex.lutter.editing.command.planners.planInsertNode
import dev.rotalex.lutter.editing.command.planners.planMoveNode
import dev.rotalex.lutter.editing.command.planners.planRemoveNode
import dev.rotalex.lutter.editing.command.planners.planRenameNode
import dev.rotalex.lutter.editing.command.planners.planSetEventHandler
import dev.rotalex.lutter.editing.command.planners.planSetModifiers
import dev.rotalex.lutter.editing.command.planners.planSetProperty
import dev.rotalex.lutter.editing.command.planners.planUnwrapNode
import dev.rotalex.lutter.editing.command.planners.planWrapNode
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.overlay.SchemaOverlay
import dev.rotalex.lutter.schema.registry.Registry

/**
 * The one place a [Command] becomes a `Patch`.
 *
 * PLAN §25.3 writes `Command -> CommandPlanner.plan(document, schema): Result<Patch>`. The
 * return type is [CommandResult] rather than `kotlin.Result`, and the reason is §25.2's own
 * spelling of the same outcome — `Ok(patch) | Rejected(reasons)` — which `kotlin.Result` has
 * no room for: its failure arm is a `Throwable`, and a refusal is a value an editor renders,
 * not an exception anybody catches.
 *
 * ### Legality is asked here; validity is not
 *
 * A planner answers exactly one question: **is this edit described by the schema?** A slot the
 * component does not declare, a slot past its `Cardinality`, a child its `ChildFilter` does
 * not admit — those are spec questions and they are answered here, so §25.3's promise holds
 * that an editor can offer only legal drop targets and never has to undo an edit that was
 * going to be wrong.
 *
 * Everything else belongs to §17 and is deliberately absent. Whether a property *value* is
 * the right type is `prop.type_mismatch`. Whether a modifier type exists is
 * `modifier.unknown`. Whether a node type resolves at all is `component.unknown` — D4's rule
 * that a document naming something this engine has never heard of still reads, still
 * round-trips and is reported rather than refused. A planner that answered those questions
 * would not be doing validation, it would be doing a worse version of it: half the rules,
 * no `ResolvedDocument`, and a second vocabulary for the same findings.
 *
 * The one place a component type *is* refused is where its slots had to be read to answer the
 * question at all — see [EditCodes.UnknownComponent].
 *
 * ### The document overlay, because `doc.*` is not special here
 *
 * A node whose type is a document component's synthesized `doc.` type has to resolve like any
 * other, or every document that declares a component would refuse every edit into it. §8.4's
 * overlay is applied per call, exactly as `Analyzer` does it: built from the document being
 * planned against, never cached on this object, so one document's declarations cannot reach
 * another's planning.
 *
 * The planners themselves take a [Registry] of component specs rather than a whole
 * [SchemaView], because slots, properties and events all hang off [ComponentSpec] and nothing
 * a planner reads lives in the other four registries. The five type parameters below are the
 * price of that narrowing not being made in `:engine:schema`, where `Analyzer` pays the same
 * one.
 *
 * ### Nothing is applied here, and nothing is stored
 *
 * A planner is a pure function of its four arguments. The document it is handed is the
 * document as it is now; the patch it returns names positions in *that* document, which is
 * why [PatchApplier] is the only writer and why the two must be applied in that order.
 */
public object CommandPlanner {

    /**
     * [command] planned against [document] and [schema], or the reasons it was refused.
     *
     * Pure, total and non-throwing on a well-formed command: every refusal is a
     * [CommandResult.Rejected] and no exception reaches the caller, because the caller is an
     * editor's event handler and an exception there is a crash with a stack trace instead of
     * a message attached to the thing the user tried to do.
     *
     * ### Why [ids] is a parameter and not a member
     *
     * §25.3's spelling has no generator, and two commands cannot be planned without one:
     * [Command.DuplicateNode] must give every copied row a fresh id, because §6.2 assigns an
     * id once and `NodeTable.with` answers a collision by replacing the original, and
     * [Command.WrapNode] creates a row out of nothing. Inventing a generator here would break
     * §6.2's determinism — a fixture that plans the same command twice would produce two
     * different patches — so the caller injects the one it already owns. §25.2's
     * `DocumentController` holds exactly this.
     *
     * @param ids the source of fresh node ids. Every id it returns is checked against the
     *   document, so a generator that repeats one is refused rather than silently applied.
     */
    public fun <A : Any, F : Any, T : Any> plan(
        command: Command,
        document: UiDocument,
        schema: SchemaView<ComponentSpec, ModifierSpec, A, F, T>,
        ids: IdGenerator,
    ): CommandResult {
        val components: Registry<ComponentType, ComponentSpec> = componentRegistry(document, schema)

        // Exhaustive with no `else`, which is the point: a thirteenth `Command` does not
        // compile until it has been given a planner, and the prompt arrives at the one place
        // the variant has to mean something.
        return when (command) {
            is Command.InsertNode -> planInsertNode(command, document, components, ids)
            is Command.RemoveNode -> planRemoveNode(command, document, components, ids)
            is Command.MoveNode -> planMoveNode(command, document, components, ids)
            is Command.DuplicateNode -> planDuplicateNode(command, document, components, ids)
            is Command.WrapNode -> planWrapNode(command, document, components, ids)
            is Command.UnwrapNode -> planUnwrapNode(command, document, components, ids)
            is Command.SetProperty -> planSetProperty(command, document, components, ids)
            is Command.SetModifiers -> planSetModifiers(command, document, components, ids)
            is Command.SetEventHandler -> planSetEventHandler(command, document, components, ids)
            is Command.RenameNode -> planRenameNode(command, document, components, ids)
            is Command.AddPage -> planAddPage(command, document, components, ids)
            is Command.ApplyPatch -> planApplyPatch(command, document, components, ids)
        }
    }

    /**
     * The component registry a command is planned against: §8.4's view when the document
     * declares components of its own, and the schema's own registry when it does not.
     *
     * The branch is what makes a document that declares nothing pay nothing — the overlay
     * merges every registry on construction, and a document with no `doc.*` instances gains
     * nothing from the merge.
     *
     * **`internal` and not private, because there are two callers and one answer.**
     * [dev.rotalex.lutter.editing.policy.DropTargets.forNode] resolves the same registry,
     * because §25.3's promise that an editor is offered only legal targets is a promise that
     * both sides resolve `doc.*` the same way. A private copy of these five lines in that file
     * would be two implementations of "which specs apply to this document", and the second one
     * would not be under this KDoc.
     */
    internal fun <A : Any, F : Any, T : Any> componentRegistry(
        document: UiDocument,
        schema: SchemaView<ComponentSpec, ModifierSpec, A, F, T>,
    ): Registry<ComponentType, ComponentSpec> =
        if (document.components.isEmpty()) schema.components else SchemaOverlay(schema, document).components
}