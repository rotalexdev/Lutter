package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PropertyTextTest {

    @Test
    fun `a literal prints as its own source and comes back as a literal`() {
        val values = listOf<PropertyValue>(
            PropertyValue.Const(Value.Str("Hello")),
            PropertyValue.Const(Value.Int32(8)),
            PropertyValue.Const(Value.Bool(true)),
            // A palette row fills every required Dp/Sp property with zero, so these two reach the
            // inspector on the first tap and must spell like any other literal.
            PropertyValue.Const(Value.Dp(0f)),
            PropertyValue.Const(Value.Sp(14.5f)),
        )

        for (value in values) {
            assertEquals(value, (PropertyText.apply(PropertyText.of(value)) as PropertyEdit.Value).value)
        }
    }

    @Test
    fun `a non-literal expression survives the round trip`() {
        val expression = PropertyValue.Computed(
            Expr.Binary(
                op = BinaryOp.Add,
                left = Expr.Const(Value.Int32(1)),
                right = Expr.Const(Value.Int32(2)),
            ),
        )

        val text = PropertyText.of(expression)
        assertEquals(expression, (PropertyText.apply(text) as PropertyEdit.Value).value)
    }

    @Test
    fun `text that is not an expression is reported, not silently defaulted`() {
        val result = PropertyText.apply("\"unterminated")

        assertIs<PropertyEdit.Unparseable>(result)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `blank text is refused with a reason that names the way out`() {
        val result = PropertyText.apply("   ")

        assertIs<PropertyEdit.Unparseable>(result)
        assertTrue(result.message.contains("clear the property"))
    }
}
