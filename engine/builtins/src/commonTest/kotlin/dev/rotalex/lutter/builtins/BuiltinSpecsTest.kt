package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.CodegenBinding
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.Positional
import dev.rotalex.lutter.schema.component.PropertyRule
import dev.rotalex.lutter.schema.component.ValueEmit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** The two skeleton specs: their surface, their rules, their bindings, their pairing. */
class BuiltinSpecsTest {

    @Test
    fun `text carries the worked example's shape`() {
        assertEquals(ComponentType("m3.Text"), TextSpec.spec.type)
        assertEquals(1, TextSpec.spec.version)
        assertEquals(Category.Basic, TextSpec.spec.metadata.category)
        assertEquals(
            listOf(PropertyKey("text"), PropertyKey("color"), PropertyKey("style")),
            TextSpec.spec.properties.map { it.key },
        )
        assertEquals(true, TextSpec.text.required)

        val call = assertIs<CodegenBinding.ComposeCall>(TextSpec.spec.codegen)
        assertEquals(KotlinSymbol("androidx.compose.material3", "Text"), call.function)
        assertEquals(listOf("text", "color", "style"), call.params.map { it.param })
        assertEquals(Positional.WhenSole, call.params.first().positional)
    }

    @Test
    fun `column carries spacing xor arrangement, a many slot and cases`() {
        assertEquals(ComponentType("core.Column"), ColumnSpec.spec.type)
        assertEquals(Category.Layout, ColumnSpec.spec.metadata.category)
        assertEquals(
            PropertyRule.MutuallyExclusive(setOf(PropertyKey("spacing"), PropertyKey("verticalArrangement"))),
            ColumnSpec.spec.rules.single(),
        )
        assertEquals(Cardinality.Many, ColumnSpec.children.cardinality)
        assertEquals(setOf(LayoutScopes.Column), ColumnSpec.children.provides)

        val call = assertIs<CodegenBinding.ComposeCall>(ColumnSpec.spec.codegen)
        val arrangement = call.params.single()
        assertEquals("verticalArrangement", arrangement.param)
        assertEquals(
            listOf(PropertyKey("spacing"), PropertyKey("verticalArrangement")),
            arrangement.from,
        )
        val cases = assertIs<ValueEmit.Cases>(arrangement.emit)
        assertEquals(2, cases.cases.size)
        assertEquals(LambdaTarget.Trailing, call.slots.single().target)
    }

    @Test
    fun `the schema pair wires both specs`() {
        val schema: Schema<ComponentSpec, String, String, String, String> =
            Schema.build<ComponentSpec, String, String, String, String> {
                registerBuiltinSpecs()
            }

        assertNotNull(schema.components[ColumnSpec.spec.type])
        assertNotNull(schema.components[TextSpec.spec.type])
    }
}
