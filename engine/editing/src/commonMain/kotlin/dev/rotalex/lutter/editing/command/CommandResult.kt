package dev.rotalex.lutter.editing.command

import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.DiagnosticCode
import dev.rotalex.lutter.model.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.model.diagnostic.Severity

/**
 * What planning a command produced: a patch, or the reasons it could not.
 *
 * PLAN §25.2 writes `dispatch(command: Command): CommandResult  // Ok(patch) | Rejected(reasons:
 * List<Diagnostic>)`. This is that signature exactly, which is not something this module could
 * have written a week ago: `Diagnostic` lived in `:engine:analysis` and `ModuleGraphRules`
 * gives `:engine:editing` only `:engine:model` and `:engine:schema`. D29 moves the type down to
 * `:engine:model` and the signature stops being a wish.
 *
 * ### Why exactly two arms
 *
 * §26.3 states a rule that only has two cases: *"Failed commands (rejected) never enter
 * history."* A third arm would be a way to have failed without saying so.
 *
 * ### A refusal is a [Diagnostic], and it is not a §17 finding
 *
 * Same type, same fields, same severity — and a different vocabulary. A §17 code says the
 * document is wrong (`struct.orphan`, `prop.type_mismatch`); an `edit.` code says **this one
 * edit may not be made** against a document that may be perfectly fine. That is the whole
 * reason the codes are [EditCodes] and not a second slice of `DiagnosticCodes`: a caller
 * rendering a list of findings must be able to tell "your document is broken" from "that drop
 * was not allowed" without parsing a message, and the prefix does it.
 *
 * Every refusal here is [Severity.Error], and that is not a default — it is the only answer.
 * A command either describes an edit that may be made or one that may not, and there is no
 * severity in between; a *warning* refusal would be a refusal the caller was invited to
 * ignore, and §26.3 has no place to put an ignored one.
 */
public sealed interface CommandResult {

    /**
     * The command is legal and this is how to do it.
     *
     * The patch is planned, not applied: it names positions in the document as it is *now*,
     * and applying it is [dev.rotalex.lutter.editing.patch.PatchApplier]'s single job.
     *
     * [patch] may hold no ops. §26.2's own note that an empty patch is legal is what makes
     * this true, and `Command.ApplyPatch(Patch(emptyList()))` is the ordinary case.
     */
    public data class Ok(public val patch: Patch) : CommandResult

    /**
     * The command is not legal against this document and this schema, and nothing was
     * produced.
     *
     * **Empty is refused at construction.** A rejection with no reasons reports a failure
     * without saying what failed, and the editor's job — show the user why the drop did not
     * happen — is not answerable from one.
     */
    public data class Rejected(public val reasons: List<Diagnostic>) : CommandResult {
        init {
            require(reasons.isNotEmpty()) { "CommandResult.Rejected carries no reason" }
        }
    }
}

/**
 * Every code a refusal may carry, and the whole of it.
 *
 * A closed catalog, for the reason a `DiagnosticCode` is a value class rather than a string:
 * a caller switches on these, so adding one is an API event rather than a refactor, and that
 * is exactly when it should happen.
 *
 * The prefix is `edit.` and it is the load-bearing part. §17.3's codes say a document is
 * wrong; these say one edit is not available. Nothing in this module can be mistaken at a
 * glance in a log for the output of an analysis pass, which is the whole difference between
 * "fix your document" and "that drop target was not offered".
 *
 * A refusal's [DiagnosticLocation] carries the node the refusal is *about*, which is not always
 * the node the command named: a drop into an undeclared slot is about the parent, and a move
 * into the moved node's own subtree is about the node being moved. §25.4's validation row
 * wants a `nodeId` for an inline marker, and a marker on the wrong node is worse than none.
 */
public object EditCodes {

    /** A `NodeId` no row of the table holds. §6.2's existence rule, asked at plan time. */
    public val UnknownNode: DiagnosticCode = DiagnosticCode("edit.unknown_node")

    /**
     * A component type the schema does not register, in a place where its slots had to be
     * read.
     *
     * **Only where the spec is needed.** A node whose type is unknown is preserved and
     * reported as `component.unknown`, which is D4's rule for a node nothing can resolve.
     * A *parent* into which children are being placed is the exception: without the spec
     * there is no way to know whether the slot exists, and planning an edit that cannot be
     * certified is the thing §25.3 exists to prevent.
     */
    public val UnknownComponent: DiagnosticCode = DiagnosticCode("edit.unknown_component")

    /** A slot the parent component does not declare. */
    public val UndeclaredSlot: DiagnosticCode = DiagnosticCode("edit.slot_undeclared")

    /** A slot that already holds as many children as its `Cardinality` allows. */
    public val SlotCardinality: DiagnosticCode = DiagnosticCode("edit.slot_cardinality")

    /** A slot whose `ChildFilter` does not admit the child's component type. */
    public val SlotChildNotAllowed: DiagnosticCode = DiagnosticCode("edit.slot_child_not_allowed")

    /** An index outside `0..size` of the slot it addresses. */
    public val IndexOutOfRange: DiagnosticCode = DiagnosticCode("edit.index_out_of_range")

    /** An id the document already holds. §6.2 assigns one id per node, once. */
    public val DuplicateId: DiagnosticCode = DiagnosticCode("edit.duplicate_id")

    /** A node no parent lists — a page or component root, which has no position to lose. */
    public val NoParent: DiagnosticCode = DiagnosticCode("edit.no_parent")

    /** An edit that would make a node its own ancestor. §6.2's acyclicity rule. */
    public val Cycle: DiagnosticCode = DiagnosticCode("edit.cycle")

    /** Two parents, or two slots, claiming the same child. A document §17.1 has to report. */
    public val MultipleParents: DiagnosticCode = DiagnosticCode("edit.multiple_parents")

    /** Nothing to place: the subtree is empty. */
    public val EmptySubtree: DiagnosticCode = DiagnosticCode("edit.empty_subtree")

    /** An unwrap of a node that has no children, which would change nothing. */
    public val NothingToUnwrap: DiagnosticCode = DiagnosticCode("edit.nothing_to_unwrap")

    /**
     * A page whose `root` is not the first row of the subtree being added.
     *
     * §5.5 makes `Page.root` the node a screen's tree starts at, so a page naming anything else
     * describes a tree that is not the one being added. There is no spec question here and no
     * analysis answer either: nothing in §17 would notice until a walk of the page reached a
     * node the author never put there.
     */
    public val RootMismatch: DiagnosticCode = DiagnosticCode("edit.root_mismatch")
}