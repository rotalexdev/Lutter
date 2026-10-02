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
 * §10.2's five `list.*` functions, and the implementations that answer them.
 *
 * Two lists rather than one list of pairs, and that is the whole design: §10.3's coverage test
 * is worth having only if a spec and its implementation are declared separately, so a new spec
 * with no implementation is a test failure rather than something construction quietly accepts.
 *
 * The element variable is `T` in every signature, which is what lets one list type carry any
 * list and `list.get` answer with the element type rather than with a placeholder.
 */
public object ListFunctions {

    public val isEmpty: FunctionSpec = FunctionSpec(
        id = FunctionId("list.isEmpty"),
        params = listOf(ParamSig("list", TypeSig.ListOf(TypeSig.Element(ELEMENT)))),
        returns = TypeSig.Exact(TypeRef.Bool),
        kotlin = FunctionEmit("{0}.isEmpty()"),
    )

    public val isNotEmpty: FunctionSpec = FunctionSpec(
        id = FunctionId("list.isNotEmpty"),
        params = listOf(ParamSig("list", TypeSig.ListOf(TypeSig.Element(ELEMENT)))),
        returns = TypeSig.Exact(TypeRef.Bool),
        kotlin = FunctionEmit("{0}.isNotEmpty()"),
    )

    public val size: FunctionSpec = FunctionSpec(
        id = FunctionId("list.size"),
        params = listOf(ParamSig("list", TypeSig.ListOf(TypeSig.Element(ELEMENT)))),
        returns = TypeSig.Exact(TypeRef.Int32),
        kotlin = FunctionEmit("{0}.size", precedence = FunctionPrecedence.Atom),
    )

    public val contains: FunctionSpec = FunctionSpec(
        id = FunctionId("list.contains"),
        params = listOf(
            ParamSig("list", TypeSig.ListOf(TypeSig.Element(ELEMENT))),
            ParamSig("element", TypeSig.Element(ELEMENT)),
        ),
        returns = TypeSig.Exact(TypeRef.Bool),
        kotlin = FunctionEmit("{0}.contains({1})"),
    )

    /**
     * §10.6's worked example of totality: an index the list does not have answers `Null`, and
     * the signature says so by returning a nullable element.
     *
     * The template is `getOrNull` rather than `[0]`, and that is the requirement landing rather
     * than a preference: an index into a Kotlin `List` throws, so the emitted half would break
     * on exactly the input the interpreter answers `Null` for.
     */
    public val get: FunctionSpec = FunctionSpec(
        id = FunctionId("list.get"),
        params = listOf(
            ParamSig("list", TypeSig.ListOf(TypeSig.Element(ELEMENT))),
            ParamSig("index", TypeSig.Exact(TypeRef.Int32)),
        ),
        returns = TypeSig.Nullable(TypeSig.Element(ELEMENT)),
        kotlin = FunctionEmit("{0}.getOrNull({1})"),
    )

    /** Every spec in §10.2's order. Registration order is the family order, not the id order. */
    public val all: List<FunctionSpec> = listOf(isEmpty, isNotEmpty, size, contains, get)

    /** Each implementation under the id its spec is filed under; §10.3's pair, kept honest. */
    public val impls: List<Pair<FunctionId, FunctionImpl>> = listOf(
        isEmpty.id to FunctionImpl { args ->
            Value.Bool(declaredArgument<Value.ListOf>(args, 0, isEmpty.id).items.isEmpty())
        },
        isNotEmpty.id to FunctionImpl { args ->
            Value.Bool(declaredArgument<Value.ListOf>(args, 0, isNotEmpty.id).items.isNotEmpty())
        },
        size.id to FunctionImpl { args ->
            Value.Int32(declaredArgument<Value.ListOf>(args, 0, size.id).items.size)
        },
        contains.id to FunctionImpl { args ->
            val list = declaredArgument<Value.ListOf>(args, 0, contains.id)
            Value.Bool(list.items.contains(declaredArgument<Value>(args, 1, contains.id)))
        },
        // `getOrNull` for the same reason the template says it: a missing element is a value
        // the document does not have, and §10.6 makes that `Null` rather than an exception.
        get.id to FunctionImpl { args ->
            val list = declaredArgument<Value.ListOf>(args, 0, get.id)
            val index = declaredArgument<Value.Int32>(args, 1, get.id)
            list.items.getOrNull(index.v) ?: Value.Null
        },
    )
}

/** §10.3's element variable, named the way the plan's own `list.get` example names it. */
private const val ELEMENT: String = "T"
