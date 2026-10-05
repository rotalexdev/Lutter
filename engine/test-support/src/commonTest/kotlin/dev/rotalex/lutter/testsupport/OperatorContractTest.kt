package dev.rotalex.lutter.testsupport

import dev.rotalex.lutter.analysis.typing.OperatorRules
import dev.rotalex.lutter.interpreter.eval.OperatorRows
import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.UnaryOp
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.kind.ValueKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §10.4's operator rules and §10.6's operator rows must agree, and before this file nothing said so.
 *
 * ### Why there is no table of expectations here
 *
 * Every answer below is computed: the checker's from [OperatorRules], the table `TypeChecker`
 * reads, and the evaluator's from [OperatorRows], which reads the table `Evaluator` reads. A
 * hand-written expectation table in this module would be a third copy that agrees with both until
 * the day one of them is edited, which is the defect this file exists to remove. Editing one row
 * without its twin fails here; that is the whole property.
 *
 * ### Acceptance is checked in one direction, and answers in both
 *
 * **Everything the checker settles, the evaluator answers.** That is the direction a document can
 * fall off: a pair §10.4 settles and §10.6 refuses analyzes clean and throws on composition. The
 * other direction is §10.6 by design — `==` is structural equality on [Value] and cannot refuse a
 * pair, so the evaluator answers `str == i32` where §10.4 refuses it, and making it refuse would
 * mean rewriting `Value`'s `equals`.
 *
 * **Answers are compared symmetrically** over the pairs both accept: the type the checker settled
 * has to accept the value the evaluator produced.
 *
 * ### Why [ValueKinds] can report on this contract but cannot state it
 *
 * `ValueKinds.kindFor` takes a [TypeRef] and the checker's operand space is [ExprType], which has
 * a case with no [TypeRef] at all: `ExprType.Null`, the null literal. §10.4's equality row admits
 * it, so acceptance cannot be phrased in kinds without losing the one operand the rule treats
 * specially. Two more gaps point the same way — an element variable is a `TypeSig.Element` and not
 * a [TypeRef], so it has no kind either, and `TypeRef.Dimension` throws — and `accepts` is a
 * superset test rather than equality: `kindFor(Nullable(Int32)).accepts(Value.Int32(1))` is true.
 * It is exact for the answers these rows produce, because no row settles a nullable, a container
 * or an element variable, and that is the claim the answer test makes.
 *
 * ### What a failure names
 *
 * The operator, the operand pair as the serial tags a §10.4 diagnostic prints, and both answers —
 * so the disagreement is readable without opening either module.
 *
 * ### What this does not compare
 *
 *  * The operand *values*. `str == str` is one row whether the two hold equal or unequal text.
 *  * Which samples were tried. [domain] carries two booleans because `&&` and `||` skip their right
 *    side and one of the two samples decides whether they do, and one value per type otherwise. A
 *    row that answers for one sample of a type and refuses another counts as not accepting the
 *    pair, which is the direction that cannot hide a hole.
 *  * The `enum`, `obj`, `map`, `icon`, `token` and `ref` variants. Every row either admits them
 *    through its "the two types agree" clause or refuses them through its numeric or bool test,
 *    and §10.6's rows partition their values identically, so adding them would exercise the
 *    domain rather than the tables.
 */
class OperatorContractTest {

    /**
     * One operand position: the type the checker settles as, and the values a document can put there.
     *
     * A null [typeRef] is `ExprType.Null` rather than a nullable [TypeRef], because §5.4 gives
     * `Value.Null` no type of its own and the checker carries it as its own case.
     */
    private data class Operand(val typeRef: TypeRef?, val samples: List<Value>) {

        /** The name a §10.4 diagnostic would print, so a failure reads like one. */
        val label: String get() = typeRef?.serialTag ?: "null"

        /** The operand as the checker's own type: [ExprType.Null] for the null literal. */
        fun asType(): ExprType = typeRef?.let { ExprType.Of(it) } ?: ExprType.Null
    }

    /**
     * The scalar family, the null literal, and one nullable and one container — the set the rows
     * actually discriminate on. Two booleans because `&&` and `||` answer without reading their
     * right side whenever the left one already decides, so one sample per bool type would report
     * `(bool, str)` as a row `and` has.
     */
    private val domain: List<Operand> = listOf(
        Operand(null, listOf(Value.Null)),
        Operand(TypeRef.Bool, listOf(Value.Bool(true), Value.Bool(false))),
        Operand(TypeRef.Int32, listOf(Value.Int32(1))),
        Operand(TypeRef.Int64, listOf(Value.Int64(1L))),
        Operand(TypeRef.Float32, listOf(Value.Float32(1.5f))),
        Operand(TypeRef.Float64, listOf(Value.Float64(1.5))),
        Operand(TypeRef.Str, listOf(Value.Str("a"))),
        Operand(TypeRef.Url, listOf(Value.Url("https://example.com"))),
        Operand(TypeRef.Color, listOf(Value.Color(ColorArgb.parse("#FF6200EE")))),
        Operand(TypeRef.Dp, listOf(Value.Dp(8f))),
        Operand(TypeRef.Sp, listOf(Value.Sp(14f))),
        Operand(TypeRef.ListOf(TypeRef.Str), listOf(Value.ListOf(listOf(Value.Str("a"))))),
        Operand(TypeRef.Nullable(TypeRef.Int32), listOf(Value.Null, Value.Int32(1))),
    )

    @Test
    fun `every operator has a row on both sides of the seam`() {
        assertEquals(
            BinaryOp.entries.toSet(),
            OperatorRules.binary.keys,
            "§10.4's binary rows against §10.1's enum",
        )
        assertEquals(
            UnaryOp.entries.toSet(),
            OperatorRules.unary.keys,
            "§10.4's unary rows against §10.1's enum",
        )
        assertEquals(
            BinaryOp.entries.toSet(),
            OperatorRows.binary.keys,
            "§10.6's binary rows against §10.1's enum",
        )
        assertEquals(
            UnaryOp.entries.toSet(),
            OperatorRows.unary.keys,
            "§10.6's unary rows against §10.1's enum",
        )
    }

    @Test
    fun `the probe domain is the kind table's own correspondence`() {
        for (operand in domain) {
            val declared: TypeRef = operand.typeRef ?: continue
            for (sample in operand.samples) {
                assertTrue(
                    ValueKinds.kindFor(declared).accepts(sample),
                    "'$declared' refuses the probe $sample the domain claims for it",
                )
            }
        }
        assertNotNull(
            OperatorRules.binary[BinaryOp.Add]?.answer(TypeRef.Of(TypeRef.Int32), INT32),
            "two '${TypeRef.Int32.serialTag}' operands settle nothing, so the comparisons below are vacuous",
        )
    }

    @Test
    fun `every operand the checker settles is one the evaluator answers`() {
        val refused = mutableListOf<String>()

        for (op in BinaryOp.entries) {
            val row = OperatorRows.binary[op] ?: continue
            for (left in domain) {
                for (right in domain) {
                    val settled: ExprType = checked(op, left, right) ?: continue
                    for (sample in left.samples) {
                        for (other in right.samples) {
                            if (row(sample) { other } != null) continue
                            refused += "'${op.name.lowercase()}' over '${left.label}' and " +
                                "'${right.label}': §10.4 settles '${settled.tag()}', " +
                                "§10.6 has no row (a $sample and a $other)"
                        }
                    }
                }
            }
        }

        for (op in UnaryOp.entries) {
            val row = OperatorRows.unary[op] ?: continue
            for (operand in domain) {
                val settled: ExprType = checked(op, operand) ?: continue
                for (sample in operand.samples) {
                    if (row(sample) != null) continue
                    refused += "'${op.name.lowercase()}' over '${operand.label}': " +
                        "§10.4 settles '${settled.tag()}', §10.6 has no row (a $sample)"
                }
            }
        }

        assertTrue(
            refused.isEmpty(),
            "pairs the checker settles and the evaluator refuses:\n" + refused.joinToString("\n"),
        )
    }

    @Test
    fun `every answer the evaluator gives fits the type the checker settled`() {
        val disagreed = mutableListOf<String>()

        for (op in BinaryOp.entries) {
            val row = OperatorRows.binary[op] ?: continue
            for (left in domain) {
                for (right in domain) {
                    val settled: ExprType = checked(op, left, right) ?: continue
                    val declared: TypeRef = settled.declared(op, left, right)
                    for (sample in left.samples) {
                        for (other in right.samples) {
                            val answered: Value = row(sample) { other } ?: continue
                            if (ValueKinds.kindFor(declared).accepts(answered)) continue
                            disagreed += "'${op.name.lowercase()}' over '${left.label}' and " +
                                "'${right.label}': §10.4 settles '${declared.serialTag}' and " +
                                "§10.6 answers a $answered"
                        }
                    }
                }
            }
        }

        for (op in UnaryOp.entries) {
            val row = OperatorRows.unary[op] ?: continue
            for (operand in domain) {
                val settled: ExprType = checked(op, operand) ?: continue
                val declared: TypeRef = settled.declared(op, operand)
                for (sample in operand.samples) {
                    val answered: Value = row(sample) ?: continue
                    if (ValueKinds.kindFor(declared).accepts(answered)) continue
                    disagreed += "'${op.name.lowercase()}' over '${operand.label}': " +
                        "§10.4 settles '${declared.serialTag}' and §10.6 answers a $answered"
                }
            }
        }

        assertTrue(
            disagreed.isEmpty(),
            "answers that are not one type:\n" + disagreed.joinToString("\n"),
        )
    }

    @Test
    fun `a row that skips its right side still skips it through the view`() {
        val and = assertNotNull(OperatorRows.binary[BinaryOp.And], "'and' has no row")
        val or = assertNotNull(OperatorRows.binary[BinaryOp.Or], "'or' has no row")
        // The right side is the one value no logical row accepts, so an eager view refuses here.
        val refused: () -> Value = { Value.Str("a") }

        assertEquals(Value.Bool(false), and(Value.Bool(false), refused))
        assertEquals(Value.Bool(true), or(Value.Bool(true), refused))
        assertNull(and(Value.Bool(true), refused))
        assertNull(or(Value.Bool(false), refused))
    }

    /** What the checker settles for a binary pair, or null when §10.4 refuses it. */
    private fun checked(op: BinaryOp, left: Operand, right: Operand): ExprType? =
        OperatorRules.binary[op]?.answer(left.asType(), right.asType())

    /** What the checker settles for a unary operand, or null when §10.4 refuses it. */
    private fun checked(op: UnaryOp, operand: Operand): ExprType? =
        OperatorRules.unary[op]?.answer(operand.asType())

    /** No row answers with the null literal, so a settled null type is a breach, not a case. */
    private fun ExprType.declared(op: BinaryOp, left: Operand, right: Operand): TypeRef =
        asTypeRef() ?: error("'$op' over '${left.label}' and '${right.label}' settles null")

    private fun ExprType.declared(op: UnaryOp, operand: Operand): TypeRef =
        asTypeRef() ?: error("'${op.name.lowercase()}' over '${operand.label}' settles null")

    private fun ExprType.asTypeRef(): TypeRef? = (this as? ExprType.Of)?.type

    /** The tag a §10.4 diagnostic prints, which is never a Kotlin class name. */
    private fun ExprType.tag(): String = asTypeRef()?.serialTag ?: "null"

    private companion object {
        val INT32: ExprType = ExprType.Of(TypeRef.Int32)
    }
}