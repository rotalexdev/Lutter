package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.model.dsl.PageScope
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.function.FunctionEmit
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.function.ParamSig
import dev.rotalex.lutter.schema.function.TypeSig
import dev.rotalex.lutter.schema.function.function
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How §10.3's signature shapes are matched against what an expression produced.
 *
 * Two shapes are under test and neither was reachable when the seed set was written: a `Nullable`
 * position, which has to accept the `T?` it names rather than only the `T`, and a [TypeSig.OneOf]
 * union, which is the only way to write "`i32`, `i64` or a float" as one parameter. They are
 * exercised together because the composition the seed set needs — a nullable read narrowed by a
 * fallback — fails if either is wrong.
 */
class FunctionSignatureTest {

    private val analyzer: Analyzer<String, FunctionSpec, String> = Analyzer(signatureSchema())

    @Test
    fun `a nullable position accepts a value and answers a nullable type`() {
        val result = analyze { node(BadgeType) { computed("caption", read(str("a"))) } }

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        assertEquals(ExprType.Of(TypeRef.Nullable(TypeRef.Str)), typeOf(result, "caption"))
    }

    @Test
    fun `a nullable read feeding a fallback answers the fallback's type`() {
        // The composition §10.2's `core.coalesce` exists for and §10.6's `list.get` produces.
        // While the `Nullable` arm left the value's own nullable layer in place the first
        // argument bound `T` to `T?`, the second argument refused to match, and the pair was
        // untypeable — which is the same gap a `T?` position has on its own.
        val result = analyze {
            node(BadgeType) {
                computed("caption", coalesce(read(str("a")), str("fallback")))
            }
        }

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        assertEquals(ExprType.Of(TypeRef.Str), typeOf(result, "caption"))
    }

    @Test
    fun `a fallback of another type is refused and names the position`() {
        val result = analyze {
            node(BadgeType) {
                computed("caption", coalesce(read(str("a")), Expr.Const(Value.Int32(1))))
            }
        }

        val finding = result.diagnostics.single { it.code == DiagnosticCodes.ExprTypeMismatch }
        assertTrue(finding.message.contains("fallback") == true, finding.message)
    }

    @Test
    fun `a union position takes a listed type and refuses an unlisted one`() {
        val accepted = analyze { node(BadgeType) { computed("caption", narrow(str("a"))) } }
        val refused = analyze { node(BadgeType) { computed("caption", narrow(Expr.Const(Value.Bool(true)))) } }

        assertTrue(accepted.diagnostics.isEmpty(), "got ${accepted.diagnostics}")
        assertEquals(ExprType.Of(TypeRef.Str), typeOf(accepted, "caption"))
        assertTrue(
            refused.diagnostics.any { it.code == DiagnosticCodes.ExprTypeMismatch },
            "got ${refused.diagnostics}",
        )
    }

    @Test
    fun `a union that cannot be one type has no result type to report`() {
        // §10.3 writes no rule for reading a union back, so the checker refuses rather than
        // picking the first option: a document agreed to two types at once.
        val result = analyze {
            node(BadgeType) { computed("caption", Expr.Call(SplitId, listOf(str("a")))) }
        }

        assertTrue(
            result.diagnostics.any { it.code == DiagnosticCodes.ExprTypeMismatch },
            "got ${result.diagnostics}",
        )
    }

    private fun analyze(block: PageScope.() -> Unit): AnalysisResult = analyzer.analyze(homeDocument(block))

    private fun typeOf(result: AnalysisResult, key: String): ExprType? = assertNotNull(result.resolved)
        .pages.get(PageId("p_home"))?.root?.props?.get(PropertyKey(key))?.typed?.type

    private fun read(argument: Expr): Expr = Expr.Call(ReadId, listOf(argument))

    private fun coalesce(value: Expr, fallback: Expr): Expr = Expr.Call(CoalesceId, listOf(value, fallback))

    private fun narrow(argument: Expr): Expr = Expr.Call(NarrowId, listOf(argument))

    private fun str(value: String): Expr = Expr.Const(Value.Str(value))
}

/** §10.6's nullable read, in the shape `list.get` has: a `T` in, a `T?` out. */
private val ReadId: FunctionId = FunctionId("test.read")

/** §10.2's `core.coalesce`, in its seed-set shape. */
private val CoalesceId: FunctionId = FunctionId("test.coalesce")

/** A union parameter with a single declared result, in the shape `num.toDouble` has. */
private val NarrowId: FunctionId = FunctionId("test.narrow")

/** A union whose two options name different types, which §10.3 has no way to resolve. */
private val SplitId: FunctionId = FunctionId("test.split")

/** §10.3's element variable, named the way the seed set names it. */
private const val ELEMENT: String = "T"

/**
 * The walking skeleton plus a `T?` destination and the four signatures under test.
 *
 * A nullable *expression* can only come from a call: §5.4 has no `TypeRef.Null`, so a nullable
 * value in a document comes from a state or parameter declaration and a nullable result comes
 * from a function that promises one.
 */
private fun signatureSchema(): Schema<ComponentSpec, ModifierSpec, String, FunctionSpec, String> =
    Schema.build<ComponentSpec, ModifierSpec, String, FunctionSpec, String> {
        walkingSkeleton()
        component(
            componentSpec(BadgeType, 1) {
                metadata("Badge", Category.Basic)
                property(prop<String>("caption", TypeRef.Nullable(TypeRef.Str)))
                composeCall(KotlinSymbol("androidx.compose.material3", "Text"))
            },
        )
        function(
            FunctionSpec(
                id = ReadId,
                params = listOf(ParamSig("value", TypeSig.Element(ELEMENT))),
                returns = TypeSig.Nullable(TypeSig.Element(ELEMENT)),
                kotlin = FunctionEmit("read()"),
            ),
        )
        function(
            FunctionSpec(
                id = CoalesceId,
                params = listOf(
                    ParamSig("value", TypeSig.Nullable(TypeSig.Element(ELEMENT))),
                    ParamSig("fallback", TypeSig.Element(ELEMENT)),
                ),
                returns = TypeSig.Element(ELEMENT),
                kotlin = FunctionEmit("coalesce()"),
            ),
        )
        function(
            FunctionSpec(
                id = NarrowId,
                params = listOf(
                    ParamSig("value", TypeSig.OneOf(listOf(TypeSig.Exact(TypeRef.Str)))),
                ),
                returns = TypeSig.Exact(TypeRef.Str),
                kotlin = FunctionEmit("narrow()"),
            ),
        )
        function(
            FunctionSpec(
                id = SplitId,
                params = listOf(ParamSig("value", TypeSig.Element(ELEMENT))),
                returns = TypeSig.OneOf(listOf(TypeSig.Exact(TypeRef.Str), TypeSig.Exact(TypeRef.Int32))),
                kotlin = FunctionEmit("split()"),
            ),
        )
    }
