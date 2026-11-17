package dev.rotalex.lutter.model.expr

import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * §10.8's text view: `parse(print(ast)) == ast` and the canonical spellings are pinned.
 *
 * The AST is the source of truth and the text is a view, so the property the plan names is
 * the whole contract: whatever [ExprPrinter] emits, [ExprParser] must read back to the exact
 * tree — data-class equality, not a re-derivation. The concrete spellings below pin the
 * canonical form a person sees in the editor's expression field; the seeded loop is the
 * property across the nine variants.
 */
class ExprTextRoundTripTest {

    // --- the canonical spellings a reader sees ---

    @Test
    fun `refs spell with their binding kind`() {
        assertEquals(
            "state.s_count",
            ExprPrinter.print(Expr.Ref(RefTarget.State(StateId("s_count")))),
        )
        assertEquals(
            "param.title",
            ExprPrinter.print(Expr.Ref(RefTarget.Param(ParamName("title")))),
        )
        assertEquals(
            "event.value",
            ExprPrinter.print(Expr.Ref(RefTarget.EventArg("value"))),
        )
        assertEquals(
            "item.product",
            ExprPrinter.print(Expr.Ref(RefTarget.Item("product"))),
        )
    }

    @Test
    fun `members spell with a dot and safe access spells with a question mark`() {
        assertEquals(
            "state.s_user.name",
            ExprPrinter.print(Expr.Member(Expr.Ref(RefTarget.State(StateId("s_user"))), "name")),
        )
        assertEquals(
            "state.s_user?.name",
            ExprPrinter.print(
                Expr.Member(Expr.Ref(RefTarget.State(StateId("s_user"))), "name", safe = true),
            ),
        )
        assertEquals(
            "state.s_user.address.city",
            ExprPrinter.print(
                Expr.Member(
                    Expr.Member(Expr.Ref(RefTarget.State(StateId("s_user"))), "address"),
                    "city",
                ),
            ),
        )
    }

    @Test
    fun `calls spell fully qualified`() {
        assertEquals(
            "list.isNotEmpty(state.s_items)",
            ExprPrinter.print(
                Expr.Call(
                    FunctionId("list.isNotEmpty"),
                    listOf(Expr.Ref(RefTarget.State(StateId("s_items")))),
                ),
            ),
        )
        assertEquals(
            "core.coalesce(state.s_a, param.title)",
            ExprPrinter.print(
                Expr.Call(
                    FunctionId("core.coalesce"),
                    listOf(
                        Expr.Ref(RefTarget.State(StateId("s_a"))),
                        Expr.Ref(RefTarget.Param(ParamName("title"))),
                    ),
                ),
            ),
        )
        assertEquals("core.now()", ExprPrinter.print(Expr.Call(FunctionId("core.now"), emptyList())))
    }

    @Test
    fun `operators spell with their symbols`() {
        assertEquals(
            "!state.s_flag",
            ExprPrinter.print(Expr.Unary(UnaryOp.Not, Expr.Ref(RefTarget.State(StateId("s_flag"))))),
        )
        assertEquals(
            "-state.s_amount",
            ExprPrinter.print(Expr.Unary(UnaryOp.Neg, Expr.Ref(RefTarget.State(StateId("s_amount"))))),
        )
        assertEquals(
            "state.s_a && state.s_b",
            ExprPrinter.print(
                Expr.Binary(
                    BinaryOp.And,
                    Expr.Ref(RefTarget.State(StateId("s_a"))),
                    Expr.Ref(RefTarget.State(StateId("s_b"))),
                ),
            ),
        )
        assertEquals(
            "(state.s_a + state.s_b) * state.s_c",
            ExprPrinter.print(
                Expr.Binary(
                    BinaryOp.Mul,
                    Expr.Binary(
                        BinaryOp.Add,
                        Expr.Ref(RefTarget.State(StateId("s_a"))),
                        Expr.Ref(RefTarget.State(StateId("s_b"))),
                    ),
                    Expr.Ref(RefTarget.State(StateId("s_c"))),
                ),
            ),
        )
    }

    @Test
    fun `if spells with keywords and its compound branches carry parens`() {
        assertEquals(
            "if (state.s_c) then state.s_t else state.s_o",
            ExprPrinter.print(
                Expr.If(
                    Expr.Ref(RefTarget.State(StateId("s_c"))),
                    Expr.Ref(RefTarget.State(StateId("s_t"))),
                    Expr.Ref(RefTarget.State(StateId("s_o"))),
                ),
            ),
        )
        assertEquals(
            "if (state.s_c) then (if (state.s_d) then state.s_x else state.s_y) else state.s_o",
            ExprPrinter.print(
                Expr.If(
                    Expr.Ref(RefTarget.State(StateId("s_c"))),
                    Expr.If(
                        Expr.Ref(RefTarget.State(StateId("s_d"))),
                        Expr.Ref(RefTarget.State(StateId("s_x"))),
                        Expr.Ref(RefTarget.State(StateId("s_y"))),
                    ),
                    Expr.Ref(RefTarget.State(StateId("s_o"))),
                ),
            ),
        )
    }

    @Test
    fun `literals spell in kotlin-like form`() {
        assertEquals("42", ExprPrinter.print(Expr.Const(Value.Int32(42))))
        assertEquals("42L", ExprPrinter.print(Expr.Const(Value.Int64(42L))))
        assertEquals("1.5", ExprPrinter.print(Expr.Const(Value.Float64(1.5))))
        assertEquals("1.5f", ExprPrinter.print(Expr.Const(Value.Float32(1.5f))))
        assertEquals("true", ExprPrinter.print(Expr.Const(Value.Bool(true))))
        assertEquals("null", ExprPrinter.print(Expr.Const(Value.Null)))
        assertEquals("\"hello\"", ExprPrinter.print(Expr.Const(Value.Str("hello"))))
        assertEquals(
            "\"a\\\"b\\\\c\\n\"",
            ExprPrinter.print(Expr.Const(Value.Str("a\"b\\c\n"))),
        )
    }

    @Test
    fun `negative literals and negation spell unambiguously`() {
        assertEquals("-5", ExprPrinter.print(Expr.Const(Value.Int32(-5))))
        assertEquals(
            "-(5)",
            ExprPrinter.print(Expr.Unary(UnaryOp.Neg, Expr.Const(Value.Int32(5)))),
        )
        assertEquals(
            "-(-5)",
            ExprPrinter.print(Expr.Unary(UnaryOp.Neg, Expr.Const(Value.Int32(-5)))),
        )
    }

    @Test
    fun `templates spell every part interpolated`() {
        assertEquals(
            "\"\${\"Hello \"}\${state.s_user.name}\"",
            ExprPrinter.print(
                Expr.Template(
                    listOf(
                        Expr.Const(Value.Str("Hello ")),
                        Expr.Member(Expr.Ref(RefTarget.State(StateId("s_user"))), "name"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `list literals spell with commas`() {
        assertEquals(
            "[1, 2, 3]",
            ExprPrinter.print(
                Expr.ListLiteral(
                    listOf(
                        Expr.Const(Value.Int32(1)),
                        Expr.Const(Value.Int32(2)),
                        Expr.Const(Value.Int32(3)),
                    ),
                ),
            ),
        )
        assertEquals(
            "[(1 + 2), 3]",
            ExprPrinter.print(
                Expr.ListLiteral(
                    listOf(
                        Expr.Binary(
                            BinaryOp.Add,
                            Expr.Const(Value.Int32(1)),
                            Expr.Const(Value.Int32(2)),
                        ),
                        Expr.Const(Value.Int32(3)),
                    ),
                ),
            ),
        )
    }

    // --- the property ---

    @Test
    fun `parse of print is the exact tree across the nine variants`() {
        val random = Random(13)
        repeat(2_000) {
            val expr = randomExpr(random, depth = 3)
            assertEquals(expr, ExprParser.parse(ExprPrinter.print(expr)), "seed draw $it")
        }
    }

    // --- the refusals ---

    @Test
    fun `garbage text refuses with the position named`() {
        assertFailsWith<IllegalArgumentException> { ExprParser.parse("state.s_x +") }
        assertFailsWith<IllegalArgumentException> { ExprParser.parse("") }
        assertFailsWith<IllegalArgumentException> { ExprParser.parse("state.s_x extra") }
        assertFailsWith<IllegalArgumentException> { ExprParser.parse("\"unterminated") }
        assertFailsWith<IllegalArgumentException> { ExprParser.parse("then x") }
    }

    @Test
    fun `unspellable values refuse rather than guess`() {
        assertFailsWith<IllegalArgumentException> {
            ExprPrinter.print(Expr.Const(Value.Dp(8.0f)))
        }
    }

    // --- the generator ---

    /** A bounded random expression over the nine variants, spellable values only. */
    private fun randomExpr(random: Random, depth: Int): Expr {
        val leaf: () -> Expr = {
            when (random.nextInt(4)) {
                0 -> Expr.Ref(
                    when (random.nextInt(4)) {
                        0 -> RefTarget.State(StateId("s_${random.nextInt(3)}"))
                        1 -> RefTarget.Param(ParamName("p_${random.nextInt(2)}"))
                        2 -> RefTarget.EventArg("arg")
                        else -> RefTarget.Item("it")
                    },
                )
                1 -> Expr.Const(
                    when (random.nextInt(5)) {
                        0 -> Value.Int32(random.nextInt(21) - 5)
                        1 -> Value.Int64(random.nextLong(21) - 5)
                        2 -> Value.Float64(0.5 * random.nextInt(-9, 10))
                        3 -> Value.Str(pickString(random))
                        else -> Value.Bool(random.nextBoolean())
                    },
                )
                2 -> Expr.Call(
                    FunctionId("list.size"),
                    if (random.nextBoolean()) {
                        listOf(Expr.Ref(RefTarget.State(StateId("s_list"))))
                    } else {
                        emptyList()
                    },
                )
                else -> Expr.ListLiteral(
                    List(random.nextInt(3)) { Expr.Const(Value.Int32(random.nextInt(5))) },
                )
            }
        }
        if (depth <= 0) return leaf()
        return when (random.nextInt(5)) {
            0 -> Expr.Member(leaf(), pickName(random), safe = random.nextBoolean())
            1 -> Expr.Unary(
                if (random.nextBoolean()) UnaryOp.Not else UnaryOp.Neg,
                randomExpr(random, depth - 1),
            )
            2 -> Expr.Binary(
                BinaryOp.entries[random.nextInt(BinaryOp.entries.size)],
                randomExpr(random, depth - 1),
                randomExpr(random, depth - 1),
            )
            3 -> Expr.If(
                randomExpr(random, depth - 1),
                randomExpr(random, depth - 1),
                randomExpr(random, depth - 1),
            )
            else -> Expr.Template(
                List(random.nextInt(3) + 1) {
                    if (random.nextBoolean()) {
                        Expr.Const(Value.Str(pickString(random)))
                    } else {
                        randomExpr(random, depth - 1)
                    }
                },
            )
        }
    }
                },
            )
        }.also { check(shape in 0..4) }
    }

    private fun pickString(random: Random): String = when (random.nextInt(4)) {
        0 -> "ok"
        1 -> "a\"b"
        2 -> "line\nbreak"
        else -> "back\\slash"
    }

    private fun pickName(random: Random): String = when (random.nextInt(3)) {
        0 -> "name"
        1 -> "size"
        else -> "value"
    }
}
