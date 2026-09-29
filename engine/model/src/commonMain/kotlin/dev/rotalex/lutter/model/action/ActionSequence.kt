package dev.rotalex.lutter.model.action

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.value.PropertyValue

/**
 * What an event handler does, as data.
 *
 * PLAN §11.2 declares exactly these two types, and the sentence that follows them is the
 * whole design: **"Actions are data, never lambdas."** Everything else in this file follows
 * from that one sentence, so it is worth taking apart rather than repeating.
 *
 * An action has to be three things at once, and a lambda is none of them:
 *
 *  * **Serializable**, because a handler lives in the document (§11.1 draws the line:
 *    a UI event maps to an `ActionSequence` *in the document*, and it is executed from
 *    there). A lambda is a compiled function object; there is nothing on the wire for it.
 *  * **Translatable to generated Kotlin**, because §11.5 and ADR-009 both say the same
 *    document is executed by the interpreter *or* emitted as Kotlin, and the corpus of §10.7
 *    asserts the two agree. A lambda would have to be compiled rather than printed, and
 *    ADR-004 rules out the compiler PSI that would do it — the model owns a hand-written
 *    `Kt*` IR, and a tree of data classes prints into it exactly.
 *  * **Comparable in a document diff**, because ADR-001 normalizes the document precisely so
 *    that it is patch- and diff-friendly, and a handler is one of the things an editor
 *    rewrites most. Two handlers that do the same thing must read the same in `git diff`, and
 *    `() -> Unit` never does.
 *
 * The cost of the constraint is verbosity, and ADR-009's trade-offs say who pays it: *"for
 * text fields (value ref + set action)"* — a bound field is a `PropertyValue.Computed`
 * reading state and a `state.set` action writing it, rather than a `bind` sugar. That
 * verbosity is the price of the first two bullets and it is worth paying, because a document
 * whose behaviour cannot be diffed is a document whose behaviour cannot be reviewed.
 *
 * ### What the model deliberately does not know
 *
 * Nothing here resolves an [ActionId]. Which parameters an action takes, which branches it
 * has, how it is emitted — all of that is an `ActionSpec` in `:engine:schema` (§11.3), and
 * §23.3's rule that `:engine:model` depends on no other module means the model cannot see
 * one. A step in this file says *what was asked for*; the registry says whether it is a
 * legal question. The same split is why [ActionStep.args] is a `Map` of untyped-by-omission
 * [PropertyValue]s rather than a record generated from the spec: a document has to decode
 * before the schema that describes it is known (D4), and a property that only existed while
 * its spec was loaded could not be handed back unchanged.
 *
 * ### The recursion is unbounded here, and the guard is not this file's job
 *
 * [ActionStep.branches] is a `Map<BranchName, ActionSequence>`, and an [ActionSequence] is a
 * list of steps, so a `flow.if` can contain a `flow.if` that contains a `flow.if` to a depth
 * the model cannot see. A data class holding a `List` of itself is ordinary Kotlin and
 * ordinary kotlinx.serialization — the serializer reaches the child only when the document
 * actually contains one, so an empty branch costs nothing and a deep one costs what it says.
 *
 * The depth is not bounded *here*, and that is a decision rather than an oversight. A limit
 * in the data type is a number somebody chose: it refuses at construction, it turns a
 * document that ought to load into one that does not, and the person who hits it is a user
 * with a legitimate document rather than a caller with a bug. The bound belongs to
 * validation, and PLAN §17.1 already has the pass that would carry it — `ActionPass` walks
 * every sequence and step, so a depth rule there is a checked rule with a `nodeId`, a
 * message and a diagnostic code out of §17.3, and it runs on a document that has already
 * been read. The same argument, and the same deferral, is recorded on [Expr], whose nodes are
 * recursive for the identical reason and whose scope is checked by the `ExpressionPass` that
 * sits next to `ActionPass` in that list.
 *
 * @see ActionStep for the two fields that make a step a step.
 */
public data class ActionSequence(
    public val steps: List<ActionStep>,
)

/**
 * One step: an action to invoke, what to invoke it with, and where to go afterwards.
 *
 * All three fields have defaults because a step is not obliged to have arguments or
 * continuations, and a handler that only does one thing should not have to say so twice.
 * The defaults are what make `{"action":"nav.back"}` a complete document.
 */
public data class ActionStep(
    /**
     * The action to invoke, by [ActionId].
     *
     * Namespaced — `nav.navigate`, `state.set`, `flow.if`, `host.call`, `ui.showDialog` —
     * because §11.2's comment lists them that way and §5.2 requires it: a registry key has
     * to be able to say who owns the name, so a plugin can contribute `flow.if` without
     * colliding with anything.
     *
     * The id is not checked against a registry here, and cannot be: see the interface KDoc.
     * A document that names an action no engine has heard of still decodes, and validation
     * reports it as a diagnostic with a `nodeId` rather than a failure to open the file.
     */
    public val action: ActionId,

    /**
     * The arguments, positional by convention and typed by the action's `ActionSpec`.
     *
     * §11.2's comment on this field is *"typed, may contain Computed exprs"*, and the second
     * half is the load-bearing one. It is what makes an action able to read the document:
     * `flow.if`'s `cond` is a `PropertyValue.Computed` holding an `Expr` over state, a
     * `state.set`'s `value` is a computed expression, a host call's argument is one. An
     * action whose arguments had to be constants could not branch, could not transform, and
     * could not talk to a host function — which is most of what §11.4 asks actions to do.
     *
     * Both arms are therefore load-bearing: a [PropertyValue.Const] for a literal argument,
     * a [PropertyValue.Computed] for anything that depends on state, the event, or another
     * property. The *key* is a [PropertyKey] and the *value* is a [PropertyValue] — a closed
     * domain type on both sides, never a bare string, which is the whole of PLAN §5.3's rule
     * against an untyped map and the reason `NoUntypedStringMapTest` has nothing to find
     * here.
     */
    public val args: Map<PropertyKey, PropertyValue> = emptyMap(),

    /**
     * Named sub-sequences, keyed by [BranchName]. `"then"` and `"else"` for `flow.if`.
     *
     * A map and not two nullable fields because §11.3's `ActionSpec` declares
     * `branches: List<BranchSpec>` and the two have to line up: a plugin action may name its
     * own branches, and a step with `then` and `otherwise` fields could not carry them.
     *
     * **`BranchName` is doing two jobs here**, and it is worth saying so because the type's
     * own KDoc in `ids/Ids.kt` describes the other one: it is documented as the name of a
     * `when` arm in a document-defined component, which is not what §11.2 uses it for. PLAN
     * declares the identifier once, in §5.2's list, and writes it here; the component reading
     * comes from a draft, because §5.5 declares `ComponentDecl` in full and it carries no
     * `branches` at all, and `SlotDecl` — named in `ComponentDecl.slots` and §33.1 — is never
     * declared anywhere. So for the first production-capable version this is the only use,
     * and the KDoc in `ids/Ids.kt` describes a field that does not exist. Splitting it into
     * two id types would be a `FORMAT_VERSION` event for a field nothing writes.
     */
    public val branches: Map<BranchName, ActionSequence> = emptyMap(),
)
