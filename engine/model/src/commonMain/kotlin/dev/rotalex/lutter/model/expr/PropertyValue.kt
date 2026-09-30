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

import dev.rotalex.lutter.model.value.Value
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * What a property holds: a value, or the expression that produces one.
 *
 * The shared currency of `Node.props`, `ModifierEntry.args` and `ActionStep.args`, so a
 * third variant is a `FORMAT_VERSION` event for all three at once.
 *
 * Two variants rather than one type with a nullable `expr`: the null-and-present state is
 * unrepresentable, and both arms stay an object with a `type` key, which is what lets a
 * decoder read a property without knowing its component (D4).
 *
 * It lives here rather than in `value` because §5.4 mandates both edges — `Computed` holds an
 * `Expr`, `Expr.Const` holds a `Value` — and only this arrangement makes the graph acyclic.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface PropertyValue {
    /**
     * A literal, already canonicalized.
     *
     * A [Value] and not a bare `String` or a primitive, so the eighteen variants keep their
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