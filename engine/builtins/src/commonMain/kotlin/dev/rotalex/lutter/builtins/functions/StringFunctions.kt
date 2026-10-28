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
 * §10.2's six `str.*` functions, and the implementations that answer them.
 *
 * Every one is a `Str` in and nothing else out but a number or a bool, which is §10.6's row on
 * string comparison: these are the only text operations an expression has, so a rule about
 * locale-dependent ordering is a rule about this file rather than about the operator tables.
 */
public object StringFunctions {

    public val isBlank: FunctionSpec = FunctionSpec(
        id = FunctionId("str.isBlank"),
        params = listOf(ParamSig("text", TypeSig.Exact(TypeRef.Str))),
        returns = TypeSig.Exact(TypeRef.Bool),
        kotlin = FunctionEmit("{0}.isBlank()"),
    )

    /**
     * `length` is `Int32` because §5.4 has one integer type per width and a string's length is
     * an `i32` in every document; the emitted `String.length` is the same `Int` the interpreter
     * wraps, so the two halves agree without a conversion either of them has to make.
     */
    public val length: FunctionSpec = FunctionSpec(
        id = FunctionId("str.length"),
        params = listOf(ParamSig("text", TypeSig.Exact(TypeRef.Str))),
        returns = TypeSig.Exact(TypeRef.Int32),
        kotlin = FunctionEmit("{0}.length", precedence = FunctionPrecedence.Atom),
    )

    /**
     * Locale-independent on purpose: Kotlin's `uppercase()`/`lowercase()` fold against the root
     * locale rather than the platform's, which is the only reason §10.6's row survives a text
     * case function at all. A locale-sensitive fold would make the same document render
     * differently in Istanbul and in Berlin.
     */
    public val uppercase: FunctionSpec = FunctionSpec(
        id = FunctionId("str.uppercase"),
        params = listOf(ParamSig("text", TypeSig.Exact(TypeRef.Str))),
        returns = TypeSig.Exact(TypeRef.Str),
        kotlin = FunctionEmit("{0}.uppercase()"),
    )

    public val lowercase: FunctionSpec = FunctionSpec(
        id = FunctionId("str.lowercase"),
        params = listOf(ParamSig("text", TypeSig.Exact(TypeRef.Str))),
        returns = TypeSig.Exact(TypeRef.Str),
        kotlin = FunctionEmit("{0}.lowercase()"),
    )

    public val trim: FunctionSpec = FunctionSpec(
        id = FunctionId("str.trim"),
        params = listOf(ParamSig("text", TypeSig.Exact(TypeRef.Str))),
        returns = TypeSig.Exact(TypeRef.Str),
        kotlin = FunctionEmit("{0}.trim()"),
    )

    /**
     * Literal containment. `String.contains(String)` is the literal form and
     * `String.contains(Regex)` the pattern one, so the generated half is the literal form too —
     * §10.2 postpones regexes, and an expression cannot name one.
     */
    public val contains: FunctionSpec = FunctionSpec(
        id = FunctionId("str.contains"),
        params = listOf(
            ParamSig("text", TypeSig.Exact(TypeRef.Str)),
            ParamSig("part", TypeSig.Exact(TypeRef.Str)),
        ),
        returns = TypeSig.Exact(TypeRef.Bool),
        kotlin = FunctionEmit("{0}.contains({1})"),
    )

    /** Every spec in §10.2's order. */
    public val all: List<FunctionSpec> = listOf(isBlank, length, uppercase, lowercase, trim, contains)

    /** Each implementation under the id its spec is filed under; §10.3's pair, kept honest. */
    public val impls: List<Pair<FunctionId, FunctionImpl>> = listOf(
        isBlank.id to FunctionImpl { args ->
            Value.Bool(declaredArgument<Value.Str>(args, 0, isBlank.id).v.isBlank())
        },
        length.id to FunctionImpl { args ->
            Value.Int32(declaredArgument<Value.Str>(args, 0, length.id).v.length)
        },
        uppercase.id to FunctionImpl { args ->
            Value.Str(declaredArgument<Value.Str>(args, 0, uppercase.id).v.uppercase())
        },
        lowercase.id to FunctionImpl { args ->
            Value.Str(declaredArgument<Value.Str>(args, 0, lowercase.id).v.lowercase())
        },
        trim.id to FunctionImpl { args ->
            Value.Str(declaredArgument<Value.Str>(args, 0, trim.id).v.trim())
        },
        contains.id to FunctionImpl { args ->
            val text = declaredArgument<Value.Str>(args, 0, contains.id).v
            Value.Bool(text.contains(declaredArgument<Value.Str>(args, 1, contains.id).v))
        },
    )
}
