package dev.rotalex.lutter.interpreter

import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Constants resolve, wrapped or not; anything else needs the evaluator. */
class ConstantValueTest {

    @Test
    fun `a direct constant resolves`() {
        assertEquals(Value.Str("Hi"), PropertyValue.Const(Value.Str("Hi")).constantOrNull())
    }

    @Test
    fun `a computed constant resolves without the evaluator`() {
        val value: PropertyValue = PropertyValue.Computed(Expr.Const(Value.Int32(3)))

        assertEquals(Value.Int32(3), value.constantOrNull())
    }

    @Test
    fun `a state read needs the evaluator`() {
        val value: PropertyValue =
            PropertyValue.Computed(Expr.Ref(RefTarget.State(StateId("s_count"))))

        assertNull(value.constantOrNull())
    }

    @Test
    fun `a composite needs the evaluator even over constants`() {
        val value: PropertyValue = PropertyValue.Computed(
            Expr.Template(listOf(Expr.Const(Value.Str("Hi")))),
        )

        assertNull(value.constantOrNull())
    }
}
