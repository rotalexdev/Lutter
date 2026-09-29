@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

// The opt-in above is for exactly one thing: `@JsonClassDiscriminator`, which
// kotlinx.serialization still marks experimental. Everything else this file uses is stable
// API. The annotation is not optional decoration - it is what puts the discriminator on the
// wire under the name this project chose rather than the library default - so opting in at
// file scope is narrower and more honest than suppressing use by use.
//
// It has to be a file annotation and not a per-use `@OptIn` because Kotlin requires file
// annotations to precede the package declaration, and putting it after imports is a syntax
// error rather than a warning.

package dev.rotalex.lutter.model.expr

import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The expression AST: what a document writes instead of a value when the value is not known
 * until the program runs.
 *
 * PLAN §10.1 declares nine variants, and this hierarchy declares nine. The list is closed for
 * the same reason [Value] is closed and for the same price: a tenth is a `FORMAT_VERSION`
 * event, paid rarely, and §10.2 already says which features the first production-capable
 * version has. Everything §10.2 lists as postponed — lambdas, user-defined functions,
 * assignment inside an expression, loops, `/` and `%`, implicit conversions, regexes, dates —
 * has no variant here, and none of them is going to acquire one quietly.
 *
 * ### §10.2, in the model's own words
 *
 * The plan draws the line twice, once as a feature list and once as an exclusion list, and
 * the exclusion list is the one that constrains this file. Included: constants; references to
 * state, params and event args; member access on data models including safe `?.`; calls into
 * the `FunctionRegistry`; `+ - *`; comparison; equality; `&& || !`; unary minus; `if`;
 * list literals; string templates; derived state. Each of those is one of the nine below or
 * one of the thirteen operator entries in `Operators.kt`, and nothing here goes past that
 * list.
 *
 * ### Every tag is spelled
 *
 * Each variant carries an explicit `@SerialName` and none of them may be named by its class
 * name. `Expr` is stored inside a document under a `PropertyValue.Computed`, so a rename
 * without a tag would repoint every stored expression at a discriminator nothing answers to.
 * The failure mode is the unpleasant one: a property that decodes to nothing, with no
 * diagnostic, in a document that still opens. `ExprTest` reads the discriminator back out of
 * the encoded JSON rather than off the annotation, so the two copies cannot drift.
 *
 * ### The discriminator key
 *
 * `type`, written explicitly through [JsonClassDiscriminator], the same key [Value] and
 * [TypeRef] use. The full argument — including PLAN §9.2's `k` and the two places PLAN writes
 * it — is in [TypeRef]'s KDoc, and it is not repeated here because a third copy of an
 * argument is a third thing to forget to update. What is worth repeating is the consequence
 * here: `Expr.Ref` wraps a [RefTarget], and `RefTarget` is polymorphic too, so a single
 * document word nests two `type` keys at two depths. That is legal, it is what the plan's
 * own declarations produce, and it is stable — which is the only property that matters for a
 * published format.
 *
 * ### Two constraints a tenth variant would break
 *
 *  * **No property may be called `type`.** The discriminator owns that key at this level, and
 *    kotlinx.serialization fails schema construction when a class property collides with it.
 *    `Expr.Call` came closest and calls its field `function`.
 *  * **There is no `serialTag` member, and that is not an oversight.** [TypeRef] has one
 *    because §9.2 tabulates the serialization of every type and the member is that column
 *    made executable. §10 has no such table, so a `serialTag` here would be a copy of a
 *    `@SerialName` with nothing to disagree with — a second source of truth for a fact that
 *    already has one. The discriminator is the whole of it.
 *
 * ### Where the recursion is, and where the guard is not
 *
 * Every variant but [Const] and [Ref] contains another [Expr], and the depth is bounded by
 * nothing the model can see: a document may nest `Binary` inside `Binary` inside `If` as
 * deeply as it likes. The model does not refuse a depth, because a depth limit in a data type
 * is a number somebody chose rather than a rule anybody reasoned about, and refusing at
 * construction turns a document that ought to load into one that does not. The guard belongs
 * to validation, where the depth limit can be a checked rule with a message — §17.1's
 * `ExpressionPass` is the pass that already walks this tree, and §10.4's diagnostics are
 * where its answer belongs. Same argument, and same deferral, as `ActionStep.branches`.
 *
 * ### The three judgements a reader is most likely to want argued
 *
 *  * **`Member.safe` defaults to `false`.** `false` is the stricter reading, and §10.4 backs
 *    it: a member access on a nullable receiver *requires* `safe = true`, so the default
 *    refuses a null-dereference the analysis would reject anyway. Defaulting to `true` would
 *    make the safe form the one a document gets by omission, which is the wrong default for
 *    a field access that is very often the bug.
 *  * **`Template.parts` is a list of [Expr] and not a list of strings.** Interpolation is
 *    `String + <part>` for each part, evaluated left to right, which is what §10.5's codegen
 *    column emits. A part that is itself a template is legal and nests, so a document that
 *    wants one interpolation inside another does not need a second variant.
 *  * **No map literal.** [ListLiteral] is the only collection constructor here, and that is
 *    the same gap the value union has: §5.4's closed `Value` has no map variant, so no
 *    expression can produce one either. See the argument in `TypeRef.MapOf`, which is where
 *    that gap is recorded rather than papered over.
 *
 * @see RefTarget for the four things an expression is allowed to name.
 * @see UnaryOp and BinaryOp for the operator sets, and for why division is not among them.
 * @see dev.rotalex.lutter.model.value.PropertyValue for the wrapper that puts an `Expr` where
 *   a document expects a property.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface Expr {

    /**
     * A literal, already typed and already canonicalized.
     *
     * The payload is a [Value], not a Kotlin primitive, and that is what lets `Const` cover
     * all seventeen of them without seventeen variants of its own. The number reaching a
     * document through here is written by `CanonicalFloat`/`CanonicalDouble`, so D1 holds for
     * an expression exactly as it holds for a property.
     */
    @Serializable
    @SerialName("const")
    public data class Const(public val value: Value) : Expr

    /**
     * A read of something in scope: state, a parameter, an event argument or an iteration
     * variable.
     *
     * A separate variant from [Member] because the two resolve in different ways and cost
     * different things to get wrong. A [Member] is a field of a value this document already
     * has, checked against a data model; a [Ref] is a named binding, checked against the
     * scope an analysis pass computed. Neither is reducible to the other, and collapsing them
     * would put a scope lookup in the middle of field access.
     */
    @Serializable
    @SerialName("ref")
    public data class Ref(public val target: RefTarget) : Expr

    /**
     * A field read on a receiver: `user.name`, or `user?.name` when [safe] is set.
     *
     * **[name] is a bare `String`, and §10.1's choice here is inconsistent with its own.**
     * The keys of an object value are `PropertyKey` — a checked identifier — because a field
     * of a data model is a name that `PropertyKey` validates. A `Member` names the same kind
     * of thing: a field of the model behind the receiver. §10.1 writes `String`, and §10.1 is
     * the specification this file implements, so `String` is what it is.
     *
     * The cost is a check the sibling variant has and this one does not: a typo in a field
     * name decodes here and is caught in analysis rather than refused at construction. Closing
     * that gap is a `FORMAT_VERSION` event and a maintainer's decision, not one to settle
     * inside a work unit, so it is recorded rather than quietly fixed.
     *
     * What the model does have to do, and does, is make the name round-trip: a field of a
     * model this engine has never loaded has to decode, because D4 is about exactly that (see
     * [Value.Obj] for the same argument about a model this engine cannot resolve).
     */
    @Serializable
    @SerialName("member")
    public data class Member(
        public val receiver: Expr,
        public val name: String,
        public val safe: Boolean = false,
    ) : Expr

    /**
     * A call into the function registry: `FunctionId` plus evaluated arguments.
     *
     * The [FunctionId] is namespaced — `list.isNotEmpty`, `str.trim`, `core.coalesce` — which
     * is §5.2's registry-key shape, and it is what tells the resolver who owns the name. The
     * arguments are positional and already evaluated; a named-argument call is not in §10.2's
     * list, and the AST for it is a strict superset of this one that nothing in the first
     * version would write.
     *
     * A call may not take a function as an argument. §10.2 postpones higher-order functions,
     * and `args` being `List<Expr>` is the structural half of that promise: no variant of
     * [Expr] *is* a function, so there is nothing a higher-order argument could hold even if
     * a document tried to write one.
     */
    @Serializable
    @SerialName("call")
    public data class Call(
        public val function: FunctionId,
        public val args: List<Expr>,
    ) : Expr

    /**
     * One operand and one operator: `!flag`, `-amount`.
     *
     * A variant of its own rather than a [Binary] with a missing side, because an operator
     * with a missing side is a special case every reader has to know about: the evaluator, the
     * type checker and the code generator would each carry a branch for "the right operand is
     * not there", which is three places to keep in step for a shape that is not a shape at
     * all. Two arguments where there are two, one where there is one.
     */
    @Serializable
    @SerialName("unary")
    public data class Unary(public val op: UnaryOp, public val operand: Expr) : Expr

    /**
     * Two operands and one operator, in the order they were written.
     *
     * [left] and [right] rather than a commutative pair, and the names rather than
     * `first`/`second`, because evaluation order is a semantic fact and a `Binary` whose two
     * sides are interchangeable on paper is a `Binary` whose codegen can guess. §10.6 lists
     * short-circuit evaluation as a hazard policy, and the only way to be sure both backends
     * agree about it is for the tree to say which side is which.
     */
    @Serializable
    @SerialName("binary")
    public data class Binary(
        public val op: BinaryOp,
        public val left: Expr,
        public val right: Expr,
    ) : Expr

    /**
     * A conditional expression, with both branches mandatory.
     *
     * **No `otherwise` that can be absent.** §10.2 says `if` expressions, and an `if` without
     * an else in Kotlin is a statement, not an expression — a branch that yields nothing is
     * a `Unit` flowing into a typed position, which is exactly the implicit conversion §10.2
     * excludes. Making the absent branch a default would push that `Unit` into the type
     * system where the analyzer has to special-case it. A caller that wants "or null" writes
     * `core.coalesce` or an explicit `Value.Null`, which is a decision the document makes on
     * purpose.
     */
    @Serializable
    @SerialName("if")
    public data class If(
        public val cond: Expr,
        public val then: Expr,
        public val otherwise: Expr,
    ) : Expr

    /**
     * A list of values, in document order.
     *
     * A `List` and not an immutable collection for the reason [Value.ListOf] gives: the
     * document is data, and kotlinx.serialization has one answer for a `List` on every
     * target.
     */
    @Serializable
    @SerialName("list")
    public data class ListLiteral(public val items: List<Expr>) : Expr

    /**
     * String interpolation: the parts, concatenated left to right.
     *
     * §10.5's codegen column emits `"Hello ${user.name}"`, and the interpreter concatenates
     * using canonical `toDisplayString` — so the two backends agree on a part that is a
     * number only because `toDisplayString` is canonical and not `toString`. §10.6 is the
     * hazard table and §10.4 is why this is not enforced here: a part may be `Str`, `Int32`,
     * `Int64` or `Bool` and a float must go through `num.format`, but that is a rule about
     * types and the model holds no types. Nothing stops a document writing a float part
     * today; analysis will say so later, with a `nodeId` and a property, which is the outcome
     * §10.4 asks for and the model cannot produce on its own.
     */
    @Serializable
    @SerialName("template")
    public data class Template(public val parts: List<Expr>) : Expr
}

/**
 * The four things an expression is allowed to name, and nothing else.
 *
 * §10.1's comments are the whole specification and they are short: state (including derived
 * state), page and component params, event arguments inside handlers, and the iteration
 * variable. Four is a small enough set to be worth defending individually, because the
 * alternatives were all "just a `String`" or "a general binding" and both were rejected.
 *
 * ### Why the four are separate and not one `Binding(name)`
 *
 * Each resolves against a different thing and each fails differently. A [State] is resolved
 * against the state declarations of the enclosing page, component or app; a [Param] against
 * the declared parameters of a page or a document-defined component; an [EventArg] against
 * the signature of the event whose handler this is; and an [Item] against a loop that, in the
 * first production-capable version, does not exist. One `Binding(name)` would make all four
 * lookups the same code, and the diagnostics they produce are not interchangeable — "no such
 * state" and "no such event argument" send a person to two different files.
 *
 * ### [Item] is declared, unused, and post-MVP
 *
 * §10.1 labels it *(wave 2)* and §10.2's included list has no iteration in it, so nothing in
 * the first version can produce an [Item] and nothing will read one. It is declared anyway,
 * for the reason [TypeRef.Dimension] is: a `@SerialName`d four-line variant costs almost
 * nothing, and a document written against a later engine decodes on this one instead of
 * failing. **Declared, unused, and this comment is why** — the first person to reach for it
 * should read this first. The scope check for it is not optional when that wave lands: a name
 * bound by nothing is the one `RefTarget` variant with no place to look.
 *
 * ### Why [Param] is an id and the other two are strings
 *
 * [Param] carries a [ParamName], which is one of §5.2's typed identifiers and validates its
 * own syntax on construction, so a malformed parameter reference cannot be built. [EventArg]
 * and [Item] carry a bare `String`, and the asymmetry is deliberate: those two name a binding
 * *in scope*, which is written by the person authoring the handler and is declared by nothing
 * anywhere in the document, so there is no declaration for a name to be checked against. A
 * validated identifier there would refuse names that are legal in a scope and buy a guarantee
 * about a string the model has no second source to compare.
 *
 * The honest limit of that choice: [EventArg.name] and [Item.name] are unbounded, so an
 * event argument may be named anything at all and nothing in this module objects. That is the
 * right way round — the alternative is a refusal at construction of a name that a later
 * analysis pass was going to look up anyway.
 *
 * ### The discriminator nests
 *
 * A `Ref` inside an `Expr` writes two `type` keys at two depths —
 * `{"type":"ref","target":{"type":"state","id":"s_count"}}` — because both hierarchies are
 * polymorphic and both use the same key. That is not a collision: they are separate objects,
 * and the inner one is unambiguously a [RefTarget]. It is worth stating because the shape
 * looks like a mistake and is not.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface RefTarget {

    /**
     * A state declaration of the enclosing page, component or app, by [StateId].
     *
     * **Derived state included, and the comment is the reason the type is `StateId`.** §10.1
     * says *"includes derived state"*, and derived state in this plan is a `StateDecl` whose
     * initial value is an expression — it is declared in the same list and resolved by the
     * same pass. A separate `Derived(id)` variant would have split one lookup into two
     * diagnostics that mean the same thing.
     *
     * The id is the declared one, not a name, so §6.2's "renaming a page/component/state
     * changes `name`, not `id`" applies here too and a rename is not a document edit.
     */
    @Serializable
    @SerialName("state")
    public data class State(public val id: StateId) : RefTarget

    /**
     * A parameter of a page or a document-defined component, by [ParamName].
     *
     * §5.7 writes `Expr.Ref(RefTarget.Param("title"))` — a bare string where this constructor
     * takes a [ParamName]. That is a shorthand in the plan's prose, not a second spelling on
     * the wire, and it is called out here because a reader who copies it literally gets a
     * compile error and deserves to know why the plan's example is not the code.
     */
    @Serializable
    @SerialName("param")
    public data class Param(public val name: ParamName) : RefTarget

    /**
     * An argument of the event whose handler this is.
     *
     * Only legal inside an action's expression, and nothing here enforces that: whether an
     * `EventArg` is in scope is a question about the handler it appears in, which is
     * [ActionStep]'s business and analysis's. The model is a closed vocabulary of shapes, not
     * a checker of where they may appear.
     */
    @Serializable
    @SerialName("event")
    public data class EventArg(public val name: String) : RefTarget

    /**
     * The variable bound by an iteration, by name.
     *
     * Post-MVP — see the interface KDoc. The name is a `String` for the reason [EventArg]'s
     * is, and the extra reason that a loop variable's name is chosen by the author of the
     * expression rather than declared by anything.
     */
    @Serializable
    @SerialName("item")
    public data class Item(public val name: String) : RefTarget
}
