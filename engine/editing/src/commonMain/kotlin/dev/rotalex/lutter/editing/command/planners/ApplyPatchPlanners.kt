package dev.rotalex.lutter.editing.command.planners

import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.registry.Registry

/**
 * [Command.ApplyPatch]: the pass-through, and the only path into a document that is not
 * schema-checked.
 *
 * ### Why no check
 *
 * A patch was built against a document this planner has not been shown. Every check above
 * compares a claim against the document in hand, and here there is nothing to compare against
 * that would mean anything: the ops name positions, and whether those positions fit is the
 * question `PatchApplier` is built to answer — it looks up the id each op claims and refuses
 * the one that is not there.
 *
 * So the legality checks are not skipped for convenience. Running them would be **wrong**,
 * not merely redundant: the first op of a five-op patch is checked against a document that the
 * other four have not run yet, so a `Cardinality` that holds after the whole patch would be
 * refused on the strength of a state the patch was written to move through. The command
 * exists precisely so that an edit the engine did not produce — a diff, a migration, a
 * collaborator's queue — enters the same history and the same undo stack, and a mechanism that
 * refuses it on the first intermediate state would be a second, worse mechanism.
 *
 * The consequence is stated rather than left to be discovered: **an `ApplyPatch` can carry a
 * patch that throws when it is applied.** That is `PatchApplier`'s documented refusal of a
 * patch built for another document, and it is the same class of error as `InsertNodes` with
 * an out-of-range index, which the type allows on purpose.
 *
 * ### An empty patch is not a refusal
 *
 * `Command.ApplyPatch(Patch(emptyList()))` is `Ok` with an empty patch. §26.2 calls an empty
 * patch legal and §26.3 refuses only *failed* commands, so turning "no ops" into a rejection
 * would make it impossible to express a command that did nothing.
 */
internal fun planApplyPatch(
    command: Command.ApplyPatch,
    document: UiDocument,
    components: Registry<ComponentType, ComponentSpec>,
    ids: IdGenerator,
): CommandResult = CommandResult.Ok(command.patch)