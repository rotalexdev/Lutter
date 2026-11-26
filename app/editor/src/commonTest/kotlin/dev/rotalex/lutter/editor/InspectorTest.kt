package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InspectorTest {

    @Test
    fun `rows follow the spec's declaration order and carry the current text`() {
        val model = Inspector.of(editorDocument(), editorSchema(), LEAF)

        assertNotNull(model)
        assertEquals(listOf(TEXT, WEIGHT), model.properties.map { it.key })
        assertEquals(PropertyText.of(HELLO), model.properties.single { it.key == TEXT }.text)
        assertEquals("Label", model.displayName)
        assertEquals("The line of text.", model.properties.single { it.key == TEXT }.doc)
        assertTrue(model.properties.single { it.key == TEXT }.required)
    }

    @Test
    fun `an absent property shows the spec's default, not an empty field`() {
        val model = Inspector.of(editorDocument(), editorSchema(), LEAF)

        assertNotNull(model)
        // WEIGHT has no default and no value in the document, so its field is empty — the one
        // state `PropertyText.apply` refuses to turn into a value.
        assertEquals("", model.properties.single { it.key == WEIGHT }.text)
    }

    @Test
    fun `a computed value is flagged as an expression`() {
        val computed = Node(
            id = LEAF,
            type = LABEL,
            props = mapOf(
                TEXT to HELLO,
                WEIGHT to PropertyValue.Computed(
                    Expr.Binary(BinaryOp.Sub, Expr.Const(Value.Int32(3)), Expr.Const(Value.Int32(1))),
                ),
            ),
        )

        val row = Inspector.of(editorDocument(leaf = computed), editorSchema(), LEAF)!!
            .properties.single { it.key == WEIGHT }

        assertTrue(row.isExpression)
        assertEquals(PropertyText.of(row.value()), row.text)
    }

    @Test
    fun `a value the text view cannot spell shows an empty field instead of throwing`() {
        val icon = Node(
            id = LEAF,
            type = LABEL,
            props = mapOf(TEXT to HELLO, WEIGHT to PropertyValue.Const(Value.Icon("Filled", "Home"))),
        )

        val row = Inspector.of(editorDocument(leaf = icon), editorSchema(), LEAF)!!
            .properties.single { it.key == WEIGHT }

        assertEquals("", row.text)
    }

    @Test
    fun `a dp value spells in the field`() {
        val spaced = Node(
            id = LEAF,
            type = LABEL,
            props = mapOf(TEXT to HELLO, WEIGHT to PropertyValue.Const(Value.Dp(8f))),
        )

        val row = Inspector.of(editorDocument(leaf = spaced), editorSchema(), LEAF)!!
            .properties.single { it.key == WEIGHT }

        assertEquals("8.0.dp", row.text)
    }

    @Test
    fun `a node the document does not hold has no inspector`() {
        assertNull(Inspector.of(editorDocument(), editorSchema(), ABSENT))
    }

    @Test
    fun `a node whose component the schema does not know has no inspector`() {
        assertNull(Inspector.of(editorDocument(leaf = Node(id = LEAF, type = MYSTERY)), editorSchema(), LEAF))
    }
}

/** The value [PropertyText.apply] would build from a row's text, for the round-trip assertion. */
private fun PropertyRow.value(): PropertyValue =
    (PropertyText.apply(text) as PropertyEdit.Value).value
