package dev.rotalex.lutter.schema.function

import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * An expression function's static contract: typed signature and Kotlin emission.
 *
 * PLAN §10.3 field for field. A plain class, as the plan spells it: specs are filed by key
 * and never compared structurally, so data-class equality would promise what nothing uses.
 */
public class FunctionSpec(
    public val id: FunctionId,
    public val params: List<ParamSig>,
    public val returns: TypeSig,
    public val kotlin: FunctionEmit,
    public val pure: Boolean = true,
)

/**
 * One parameter: its name and type. A bare string, like `EventArgSpec`: the name binds a
 * call site the analysis resolves, so construction-time validation would refuse legal names
 * before anything could resolve them.
 */
public data class ParamSig(
    public val name: String,
    public val type: TypeSig,
)

/**
 * A signature type: an exact model type, an element variable, or a composition of either.
 *
 * Element variables are the only generics (§10.3): `list.get` is `(ListOf(Element(T)), Int32)`
 * returning `Nullable(Element(T))`. [OneOf] is not a generic — it is a union of positions the
 * plan's own numeric rules need — and it is here because a signature that cannot be written is
 * a function that cannot be typed.
 */
public sealed interface TypeSig {
    public data class Exact(public val type: TypeRef) : TypeSig

    /** An element-type variable, named so diagnostics can repeat what the author wrote. */
    public data class Element(public val name: String) : TypeSig

    public data class ListOf(public val element: TypeSig) : TypeSig

    public data class Nullable(public val inner: TypeSig) : TypeSig

    /**
     * Any one of [options] fills this position.
     *
     * The seed set needs it and the other three shapes cannot say it: §10.4 sends every
     * narrower numeric type through `num.toDouble`, and there is no way to name "an `i32`, an
     * `i64` or a float" without a union. [Element] would accept anything at all, including a
     * string, and an over-accepting signature is worse than a missing one: the generated call
     * would not compile.
     */
    public data class OneOf(public val options: List<TypeSig>) : TypeSig
}

/**
 * How a call becomes Kotlin: fill [pattern]'s `{0}`, `{1}` placeholders, adding [imports].
 *
 * Operators are table-driven (§10.5), never templates, so a template is the one place where a
 * binding has to be declared rather than derived: [precedence] is what the filled text binds as,
 * and the emitter parenthesizes it wherever a tighter context would read it differently.
 */
public data class FunctionEmit(
    public val pattern: String,
    public val imports: List<KotlinSymbol> = emptyList(),
    public val precedence: FunctionPrecedence = FunctionPrecedence.Call,
)

/**
 * What a filled template binds as: the loosest thing its own text can be.
 *
 * The level is a claim about the template, so a template that binds looser than it declares is a
 * wrong claim rather than a style, and declaring it is the alternative to reading the text.
 */
public enum class FunctionPrecedence {
    /** A value or a member read: `x.size`, tighter than every operator. */
    Atom,

    /** A postfix call: `x.isNotEmpty()`, parenthesized inside anything tighter. */
    Call,

    /** A binary equality: `x == null`, looser than any call. The `==` level, not the `<` one. */
    Comparison,

    /** `x ?: y`: tighter than an equality and looser than a sum, so it is a level of its own. */
    Elvis,
}

/**
 * The S1 joint, bound: S1 left [Schema] generic over five value types because these specs
 * did not exist yet. The function registry is now `Registry<FunctionId, FunctionSpec>`.
 */
public typealias FunctionSchemaView<C, M, A, T> =
    SchemaView<C, M, A, FunctionSpec, T>

/** A [Schema] whose function registry holds [FunctionSpec]. The other units bind separately. */
public typealias FunctionSchema<C, M, A, T> =
    Schema<C, M, A, FunctionSpec, T>

/**
 * Registers an already-built spec under its own id. An overload, not a member: S1's
 * generic mechanics stay untouched and its stub-typed tests keep compiling.
 */
public fun <C : Any, M : Any, A : Any, T : Any> SchemaBuilder<C, M, A, FunctionSpec, T>.function(
    spec: FunctionSpec,
): Unit = function(spec.id, spec)
