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

package dev.rotalex.lutter.model.value

import dev.rotalex.lutter.model.expr.Expr
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * What a property holds: a value, or the expression that produces one.
 *
 * PLAN §5.4 declares this as a two-variant sealed union and everything else in the engine
 * depends on the two of them staying exactly two. `Node.props` (§5.3) is
 * `Map<PropertyKey, PropertyValue>`, `ModifierEntry.args` is the same shape, and
 * `ActionStep.args` (§11.2) is the same shape again — so this is the *shared currency* of the
 * document, and a third variant is a `FORMAT_VERSION` event for all three at once.
 *
 * ### Why it is two variants and not an optional field
 *
 * The obvious alternative is one type with a nullable `expr`. It is worse in a way that only
 * shows up in a decoder: a nullable field admits a third state that means nothing — an
 * expression that is present *and* absent is a document that has to be interpreted, and two
 * readers will interpret it differently. A sealed union of two makes the invalid state
 * unrepresentable, which is the same argument [Value] makes for closing its own seventeen.
 *
 * It also keeps the *shape* on the wire identical in both arms, which is what lets a decoder
 * read a property without knowing the component that owns it (D4). Both arms are an object
 * with a `type` key, so `PropertyValue` and `Value` are read by the same code, and a property
 * a newer plugin wrote decodes here whether its arm is a constant or an expression.
 *
 * ### Why this lives in the `value` package, and the cycle that creates
 *
 * §33.1 puts it at `value/PropertyValue.kt` and the reason is the sentence above: it is
 * currency for values, arguments and modifiers alike, and a wrapper for a value that lived
 * somewhere else would make every one of those maps depend on that somewhere else.
 *
 * The cost, stated rather than hidden: **`value` and `expr` now depend on each other.**
 * [Expr.Const] holds a [Value], so `expr` imports `value`; [Computed] holds an [Expr], so
 * `value` imports `expr`. That is a cycle between two packages, and this project has taken a
 * package cycle seriously before — `TypeRef`, `RefKind` and `TokenKind` live in their own
 * `type` package precisely so that `value` and `type` would have the single edge
 * `value → type`, and `TypeRef`'s own KDoc records that as the reason.
 *
 * The same argument cannot be applied here, and the reason is that §5.4 mandates *both*
 * edges: `PropertyValue.Computed` must hold an `Expr` and `Expr.Const` must hold a `Value`, so
 * no arrangement of packages makes the graph acyclic without moving a type the plan puts
 * somewhere specific. §33.1's own table records both dependencies, in both directions.
 *
 * A package cycle inside one compilation unit costs nothing: there is no build-order
 * question, no module to compile first, and `ModuleGraphRules.ALLOWED` (§23.3) is about
 * *module* dependencies, not packages. It would become a real cost the day these two packages
 * were separate modules, and §23.3's rule that `:engine:model` depends on no other module is
 * what says that day is not this project's. The arrangement is therefore correct for the
 * reason that it is legal, and this paragraph is the note for the reader who expected the P10
 * rule to be applied uniformly.
 *
 * ### Two arms, and a tag that is also an [Expr] tag
 *
 * The tag `const` appears in *two* unions — here, and on [Expr.Const] — and because §10.1
 * gave the expression variant the same tag *and* the same field name, the two produce
 * **byte-identical** JSON for the same inner value:
 * `{"type":"const","value":{"type":"i32","v":1}}` in either position.
 *
 * That is harmless and worth writing down rather than discovering. The two are never read as
 * one another: a `PropertyValue` is decoded where a property or an action argument is
 * expected, an `Expr` where a computed value is expected, and no position in the document
 * accepts both. What it does mean is that a reader looking at a stored document cannot tell
 * from that fragment alone whether an author wrote a constant property or a constant
 * expression — which is exactly the ambiguity §33.1's compact encoding is meant to collapse.
 * The other tag, `expr`, is unique to this union.
 *
 * ### Why there is no `TypeRef` on the arms
 *
 * A property's type is declared by its `PropertySpec` in the schema, and §9.1 keeps type
 * declarations there. Putting one on the value as well would be a second source for a fact
 * the document already states once, and D4 forbids the second source for a sharper reason:
 * decoding a property may not consult the schema, so a type carried on the value would have
 * to be trusted or ignored, and either answer is worse than not having it. The type checker
 * compares what the spec says against what the tree evaluates to (§10.4) rather than reading
 * a claim off the value.
 *
 * ### The compact encoding is not this
 *
 * §33.1 lists a separate, internal `PropertyValueSerializer` whose stated job is to *collapse*
 * the `const` arm. It is not written here and it is not T5's: it is an envelope concern owned
 * by the work unit that owns the envelope, and until it exists the generic `@Serializable` path
 * on this interface is the wire form, which is one extra object of nesting per constant
 * property. That is verbose and correct, and the two are not the same thing — a hand-written
 * serializer that could not read a document written by the generic path would trade
 * readability for bytes in a format ADR-006 says is chosen for being human-readable.
 *
 * @see Value for the seventeen things a [Const] can hold, and for why the union is closed.
 * @see Expr for the nine shapes a [Computed] can have.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface PropertyValue {

    /**
     * A literal, already canonicalized.
     *
     * A [Value] and not a bare `String` or a primitive, so the seventeen variants keep their
     * own tags and D1's number spelling reaches a property through here unchanged. The
     * asymmetry with [Computed] is that this arm needs no scope, no analysis and no
     * evaluation: it is the arm a document can be checked against without understanding a
     * single expression in it, which is what makes it the one an editor falls back to.
     */
    @Serializable
    @SerialName("const")
    public data class Const(public val value: Value) : PropertyValue

    /**
     * The expression that produces the value.
     *
     * §5.3 and §11.2 both say a property or an action argument *may* hold one of these, and
     * this is where the expression language earns its place in the document: a property that
     * depends on state, an action argument computed from the event, a bound `Text` field.
     *
     * The [Expr] is stored unevaluated and unchecked. Whether it typechecks, which functions
     * it calls, whether the state it reads is in scope — all of that is §10.4's analysis, and
     * keeping it out of the model is what lets a document be read before it is understood.
     */
    @Serializable
    @SerialName("expr")
    public data class Computed(public val expr: Expr) : PropertyValue
}
