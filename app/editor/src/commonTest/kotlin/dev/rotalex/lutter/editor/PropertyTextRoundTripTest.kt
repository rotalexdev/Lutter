package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprParser
import dev.rotalex.lutter.model.expr.ExprPrinter
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * S8 and S3's second half: the expression field round-trips through the parser.
 *
 * ### The rule, not a list of strings
 *
 * `PropertyText` is one box over `ExprParser`/`ExprPrinter`, and `ExprParser` guarantees
 * `parse(print(expr)) == expr`. This test asserts the editor keeps that guarantee at its own
 * boundary: for each expression, the text the field shows prints back to the same AST, and
 * committing that text returns the same value. A row that only checked "text in, text out"
 * would pass on a printer that normalises two different expressions to one string.
 *
 * ### One row per family
 *
 * The families are the ones a `PropertyValue` can hold: a literal, arithmetic, comparison,
 * logic, a string chain, a list read, a null coalesce and a template. They are not the corpus
 * — that lives in `:engine:interpreter` and is driven by the conformance suite — but they are
 * the editor's own contract, and every one of them crosses the parse/print boundary twice.
 */
class PropertyTextRoundTripTest {

    // Every string here is a form `ExprPrinter` emits, taken from the engine's own
    // `ExprTextRoundTripTest`: writing a shape the printer never produces would test the parser
    // against a text the editor can never show.
    private val expressions: List<String> = listOf(
        "\"Alpha\"",
        "42",
        "true",
        "null",
        "1 + 2 * 3",
        "(1 + 2) * 3",
        "1 < 2",
        "state.s_a && state.s_b",
        "!state.s_flag",
        "\"a\" + \"b\"",
        "[1, 2, 3]",
        "state.s_user.name",
        "state.s_user?.name",
        "param.title",
        "event.value",
        "core.now()",
        "core.coalesce(state.s_a, param.title)",
        "list.isNotEmpty(state.s_items)",
        "if (state.s_c) then state.s_t else state.s_o",
        "\"\${\"Hi \"}\${state.s_user.name}\"",
    )

    @Test
    fun the_canonical_text_parses_back_to_the_same_expression() {
        expressions.forEach { text ->
            val parsed: Expr = ExprParser.parse(text)
            val printed: String = ExprPrinter.print(parsed)
            assertEquals(parsed, ExprParser.parse(printed), "parse(print(expr)) == expr for '$text'")
        }
    }

    @Test
    fun committing_the_text_the_field_shows_returns_the_same_value() {
        expressions.forEach { text ->
            val first = assertIs<PropertyEdit.Value>(
                PropertyText.apply(text),
                "'$text' must be a committable value",
            )
            val shown: String = PropertyText.of(first.value)
            val again = assertIs<PropertyEdit.Value>(
                PropertyText.apply(shown),
                "the canonical text of '$text' must commit again",
            )
            assertEquals(first.value, again.value, "round trip changed the value for '$text'")
            assertEquals(shown, PropertyText.of(again.value), "the canonical text must be stable for '$text'")
        }
    }

    @Test
    fun a_commit_keeps_a_literal_a_literal_and_an_expression_an_expression() {
        // `Expr.Const` is the arm that makes a literal a literal; a value that came from a
        // literal must come back as one, and a value that came from an expression must not.
        val literal = assertIs<PropertyEdit.Value>(PropertyText.apply("\"Alpha\""))
        assertEquals(PropertyValue.Const(Value.Str("Alpha")), literal.value)

        val expression = assertIs<PropertyEdit.Value>(PropertyText.apply("\"a\" + \"b\""))
        assertEquals(
            PropertyValue.Computed(ExprParser.parse("\"a\" + \"b\"")),
            expression.value,
            "an expression must not collapse into a literal",
        )
    }

    @Test
    fun blank_text_is_unparseable_rather_than_a_default() {
        listOf("", "   ").forEach { text ->
            assertIs<PropertyEdit.Unparseable>(PropertyText.apply(text), "'$text' is not an expression")
        }
    }
}
