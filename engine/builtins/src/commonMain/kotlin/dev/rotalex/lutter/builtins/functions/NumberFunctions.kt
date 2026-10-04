package dev.rotalex.lutter.builtins.functions

import dev.rotalex.lutter.builtins.declaredArgument
import dev.rotalex.lutter.interpreter.eval.FunctionImpl
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.model.value.fixedDecimalText
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.function.FunctionEmit
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.function.ParamSig
import dev.rotalex.lutter.schema.function.TypeSig

/**
 * §10.2's two `num.*` functions, and the implementations that answer them.
 *
 * Both exist because §10.4 refuses what §10.6 makes necessary: there is no implicit numeric
 * conversion, and a float may not be a template part. Between them they are the only route from
 * a number to text, which is why this file is where §10.6's first hazard row is discharged.
 */
public object NumberFunctions {

    /** The symbol both backends call. §10.3's `imports` are the only way a template can name one. */
    private val formatter: KotlinSymbol =
        KotlinSymbol("dev.rotalex.lutter.model.value", "fixedDecimalText")

    /** §10.4's "floating types": the pair `num.format` exists to print. */
    private val floating: List<TypeSig> = listOf(TypeSig.Exact(TypeRef.Float64), TypeSig.Exact(TypeRef.Float32))

    /** §10.4's arithmetic types, every one of which `num.toDouble` may be asked about. */
    private val numeric: List<TypeSig> =
        floating + listOf(TypeSig.Exact(TypeRef.Int32), TypeSig.Exact(TypeRef.Int64))

    /**
     * `num.format(v, n)`: [fixedDecimalText] on both sides.
     *
     * The template names the function in `:engine:model` rather than re-deriving the arithmetic
     * in Kotlin text, because that is the only way §10.6's "integer math on both sides" can be
     * structural instead of a convention: a `FunctionEmit` carries a pattern and its imports and
     * nothing else, so an emitted call has to *call* something, and one implementation cannot
     * drift away from the other. `Kotlin.format` was the alternative and it is exactly what the
     * hazard row rules out.
     */
    public val format: FunctionSpec = FunctionSpec(
        id = FunctionId("num.format"),
        params = listOf(
            ParamSig("value", TypeSig.OneOf(floating)),
            ParamSig("decimals", TypeSig.Exact(TypeRef.Int32)),
        ),
        returns = TypeSig.Exact(TypeRef.Str),
        kotlin = FunctionEmit("fixedDecimalText({0}, {1})", imports = listOf(formatter)),
    )

    /**
     * The conversion §10.4 names when it refuses `Int32 + Float64`.
     *
     * The union is not decoration: the plan sends every numeric type through this one function,
     * so a signature accepting only `i32` would leave the checker's own hint ("wrap one side in
     * num.toDouble") unfollowable for an `i64` or an `f32`.
     */
    public val toDouble: FunctionSpec = FunctionSpec(
        id = FunctionId("num.toDouble"),
        params = listOf(ParamSig("value", TypeSig.OneOf(numeric))),
        returns = TypeSig.Exact(TypeRef.Float64),
        kotlin = FunctionEmit("{0}.toDouble()"),
    )

    /** Every spec in §10.2's order. */
    public val all: List<FunctionSpec> = listOf(format, toDouble)

    /** Each implementation under the id its spec is filed under; §10.3's pair, kept honest. */
    public val impls: List<Pair<FunctionId, FunctionImpl>> = listOf(
        // Both floating types, because §10.4 sends both through here; widening a `Float` is
        // exact, so an `f32` takes the same path an `f64` value does.
        format.id to FunctionImpl { args ->
            val value = floatingValue(args, format.id)
            val decimals = declaredArgument<Value.Int32>(args, 1, format.id).v
            Value.Str(fixedDecimalText(value, decimals))
        },
        toDouble.id to FunctionImpl { args -> Value.Float64(numericValue(args, toDouble.id)) },
    )
}

/**
 * The widened value `num.format` prints, refusing an argument its `ParamSig` does not declare.
 *
 * The refusal is the two halves disagreeing, not a value the document does not have: §10.6's
 * totality row is about the latter, and pass 5 already refused this one.
 */
private fun floatingValue(args: List<Value>, id: FunctionId): Double = when (val value = args.getOrNull(0)) {
    is Value.Float64 -> value.v
    is Value.Float32 -> value.v.toDouble()
    else -> throw IllegalStateException("'$id' argument 1 is not the type its ParamSig declares")
}

/** The widened value `num.toDouble` answers, refusing an argument its `ParamSig` does not declare. */
private fun numericValue(args: List<Value>, id: FunctionId): Double = when (val value = args.getOrNull(0)) {
    is Value.Float64 -> value.v
    is Value.Float32 -> value.v.toDouble()
    is Value.Int64 -> value.v.toDouble()
    is Value.Int32 -> value.v.toDouble()
    else -> throw IllegalStateException("'$id' argument 1 is not the type its ParamSig declares")
}
