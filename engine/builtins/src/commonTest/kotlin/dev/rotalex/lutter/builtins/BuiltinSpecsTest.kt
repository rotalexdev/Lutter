package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
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
import kotlin.test.assertSame

/** The builtin specs: their surface, their rules, their bindings, their pairing. */
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
    fun `the schema pair wires every builtin spec`() {
        val schema: Schema<ComponentSpec, String, String, String, String> =
            Schema.build<ComponentSpec, String, String, String, String> {
                registerBuiltinSpecs()
            }

        for (spec in allSpecs) {
            assertSame(spec, schema.components[spec.type], "${spec.type}")
        }
    }

    @Test
    fun `row is column across the other axis and provides the scope weight requires`() {
        assertEquals(ComponentType("core.Row"), RowSpec.spec.type)
        assertEquals(Category.Layout, RowSpec.spec.metadata.category)
        assertEquals(TypeId("HorizontalArrangement"), RowSpec.ArrangementId)
        assertEquals(
            PropertyRule.MutuallyExclusive(setOf(PropertyKey("spacing"), PropertyKey("horizontalArrangement"))),
            RowSpec.spec.rules.single(),
        )
        assertEquals(Cardinality.Many, RowSpec.children.cardinality)
        assertEquals(setOf(LayoutScopes.Row), RowSpec.children.provides)

        val call = assertIs<CodegenBinding.ComposeCall>(RowSpec.spec.codegen)
        assertEquals(KotlinSymbol("androidx.compose.foundation.layout", "Row"), call.function)
        val arrangement = call.params.single()
        assertEquals("horizontalArrangement", arrangement.param)
        assertEquals(
            listOf(PropertyKey("spacing"), PropertyKey("horizontalArrangement")),
            arrangement.from,
        )
        assertEquals(2, assertIs<ValueEmit.Cases>(arrangement.emit).cases.size)
        assertEquals(LambdaTarget.Trailing, call.slots.single().target)
    }

    @Test
    fun `box is a scope and a call, with no property of its own`() {
        assertEquals(ComponentType("core.Box"), BoxSpec.spec.type)
        assertEquals(emptyList(), BoxSpec.spec.properties.map { it.key })
        assertEquals(Cardinality.Many, BoxSpec.children.cardinality)
        assertEquals(setOf(LayoutScopes.Box), BoxSpec.children.provides)

        val call = assertIs<CodegenBinding.ComposeCall>(BoxSpec.spec.codegen)
        assertEquals(KotlinSymbol("androidx.compose.foundation.layout", "Box"), call.function)
        assertEquals(emptyList(), call.params)
        assertEquals(LambdaTarget.Trailing, call.slots.single().target)
    }

    @Test
    fun `spacer is its modifier and nothing else`() {
        assertEquals(ComponentType("core.Spacer"), SpacerSpec.spec.type)
        assertEquals(emptyList(), SpacerSpec.spec.properties.map { it.key })
        assertEquals(emptyList(), SpacerSpec.spec.slots)

        val call = assertIs<CodegenBinding.ComposeCall>(SpacerSpec.spec.codegen)
        assertEquals(KotlinSymbol("androidx.compose.foundation.layout", "Spacer"), call.function)
        assertEquals("modifier", call.modifierParam)
        assertEquals(emptyList(), call.params)
        assertEquals(emptyList(), call.slots)
    }

    private companion object {
        val allSpecs: List<ComponentSpec> =
            listOf(ColumnSpec.spec, RowSpec.spec, BoxSpec.spec, SpacerSpec.spec, TextSpec.spec)
    }
}
