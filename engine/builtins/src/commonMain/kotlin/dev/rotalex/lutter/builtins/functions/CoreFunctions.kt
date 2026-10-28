package dev.rotalex.lutter.builtins.functions

import dev.rotalex.lutter.builtins.declaredArgument
import dev.rotalex.lutter.interpreter.eval.FunctionImpl
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.function.FunctionEmit
import dev.rotalex.lutter.schema.function.FunctionPrecedence
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.function.ParamSig
import dev.rotalex.lutter.schema.function.TypeSig

/**
 * §10.2's two `core.*` functions, and the implementations that answer them.
 *
 * They are the only two functions with no namespace of their own, because neither is about a
 * kind of value: one is about the *absence* of a value and the other is what a document uses to
 * substitute for it. §5.4 gives `Value.Null` no `TypeRef` of its own, so both signatures are
 * written in terms of a position that may be nullable rather than in terms of null.
 */
public object CoreFunctions {

    /**
     * `core.coalesce(a, b)`: [a] unless it is null, otherwise [b].
     *
     * The second parameter is a bare `T`, not a nullable, and that is the signature's whole
     * point: the result is non-null exactly because the fallback is. A document that passes a
     * nullable fallback is refused rather than answered a `T` the evaluator cannot produce.
     */
    public val coalesce: FunctionSpec = FunctionSpec(
        id = FunctionId("core.coalesce"),
        params = listOf(
            ParamSig("value", TypeSig.Nullable(TypeSig.Element(ELEMENT))),
            ParamSig("fallback", TypeSig.Element(ELEMENT)),
        ),
        returns = TypeSig.Element(ELEMENT),
        // `?:`, at the level it binds at: over `==` and under `+`. Declared rather than read
        // out of the text, or a `?:` beside a `==` is parenthesised as if it bound looser.
        kotlin = FunctionEmit("{0} ?: {1}", precedence = FunctionPrecedence.Elvis),
    )

    /**
     * `core.isNull(a)`: whether a value is the absence of one.
     *
     * A `Nullable(T)` position rather than an element variable, so the function answers about
     * nullability and not about whatever type happens to be there. Nothing in an expression can
     * be null except a position that admits it, so a document asking this about a `T` is asking
     * a question with one answer and gets it.
     */
    public val isNull: FunctionSpec = FunctionSpec(
        id = FunctionId("core.isNull"),
        params = listOf(ParamSig("value", TypeSig.Nullable(TypeSig.Element(ELEMENT)))),
        returns = TypeSig.Exact(TypeRef.Bool),
        // `==` binds looser than any call, so the template declares a comparison rather than a
        // call. At the call level the emitter would leave it bare inside a unary operand, and
        // `!isNull(x)` would read as `(!x) == null`.
        kotlin = FunctionEmit("{0} == null", precedence = FunctionPrecedence.Comparison),
    )

    /** Every spec in §10.2's order. */
    public val all: List<FunctionSpec> = listOf(coalesce, isNull)

    /** Each implementation under the id its spec is filed under; §10.3's pair, kept honest. */
    public val impls: List<Pair<FunctionId, FunctionImpl>> = listOf(
        coalesce.id to FunctionImpl { args ->
            // §10.6's totality row is about absent *data*; a null argument is one, so the
            // fallback is a value the document does not have and not an error.
            val value = declaredArgument<Value>(args, 0, coalesce.id)
            if (value is Value.Null) declaredArgument<Value>(args, 1, coalesce.id) else value
        },
        isNull.id to FunctionImpl { args ->
            Value.Bool(declaredArgument<Value>(args, 0, isNull.id) is Value.Null)
        },
    )
}

/** §10.3's element variable, bound by the fallback and read back through the result. */
private const val ELEMENT: String = "T"
