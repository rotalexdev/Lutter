package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.builtins.enums.HorizontalArrangementSpec
import dev.rotalex.lutter.builtins.enums.VerticalArrangementSpec
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.CodegenBinding
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EventArgSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.Positional
import dev.rotalex.lutter.schema.component.PropertyRule
import dev.rotalex.lutter.schema.component.ValueEmit
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.EnumTypeSpec
import dev.rotalex.lutter.schema.types.TypeSpec
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
    fun `both layouts read one arrangement vocabulary`() {
        assertEquals(VerticalArrangementSpec.Id, ColumnSpec.ArrangementId)
        assertEquals(HorizontalArrangementSpec.Id, RowSpec.ArrangementId)
        assertEquals(
            listOf("Top", "Center", "Bottom"),
            VerticalArrangementSpec.entries.map { it.name },
        )
        assertEquals(
            listOf("Start", "Center", "End"),
            HorizontalArrangementSpec.entries.map { it.name },
        )
        // Member-qualified, so codegen writes one import for the object behind both axes.
        assertEquals(
            listOf("Arrangement.Top", "Arrangement.Center", "Arrangement.Bottom"),
            VerticalArrangementSpec.entries.map { it.kotlin.name },
        )
        val schema: Schema<ComponentSpec, ModifierSpec, String, String, TypeSpec> =
            Schema.build<ComponentSpec, ModifierSpec, String, String, TypeSpec> {
                registerBuiltinEnums()
            }

        assertIs<EnumTypeSpec>(schema.types[VerticalArrangementSpec.Id])
        assertIs<EnumTypeSpec>(schema.types[HorizontalArrangementSpec.Id])
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

    @Test
    fun `button is one label slot and a press no binding reads`() {
        assertEquals(ComponentType("m3.Button"), ButtonSpec.spec.type)
        assertEquals(Category.Basic, ButtonSpec.spec.metadata.category)
        assertEquals(emptyList(), ButtonSpec.spec.properties.map { it.key })
        assertEquals(Cardinality.ExactlyOne, ButtonSpec.content.cardinality)
        assertEquals(setOf(LayoutScopes.Row), ButtonSpec.content.provides)
        assertEquals(listOf(EventKey("onClick")), ButtonSpec.spec.events.map { it.key })

        val call = assertIs<CodegenBinding.ComposeCall>(ButtonSpec.spec.codegen)
        assertEquals(KotlinSymbol("androidx.compose.material3", "Button"), call.function)
        assertEquals(emptyList(), call.params)
        assertEquals(emptyList(), call.events, "a bound event fails CodegenCoverage")
        assertEquals(LambdaTarget.Trailing, call.slots.single().target)
    }

    @Test
    fun `textfield declares its edit as the event a handler is`() {
        assertEquals(ComponentType("m3.TextField"), TextFieldSpec.spec.type)
        assertEquals(Category.Input, TextFieldSpec.spec.metadata.category)
        assertEquals(listOf(PropertyKey("value")), TextFieldSpec.spec.properties.map { it.key })
        assertEquals(true, TextFieldSpec.value.required)

        val handler = TextFieldSpec.spec.events.single()
        assertEquals(EventKey("onValueChange"), handler.key)
        assertEquals(listOf(EventArgSpec("value", TypeRef.Str)), handler.args)

        val call = assertIs<CodegenBinding.ComposeCall>(TextFieldSpec.spec.codegen)
        assertEquals(KotlinSymbol("androidx.compose.material3", "TextField"), call.function)
        assertEquals("value", call.params.single().param)
        assertEquals(emptyList(), call.events, "a bound event fails CodegenCoverage")
        assertEquals(emptyList(), call.slots)
    }

    @Test
    fun `card is a surface with a body and no scope of its own`() {
        assertEquals(ComponentType("m3.Card"), CardSpec.spec.type)
        assertEquals(Category.Basic, CardSpec.spec.metadata.category)
        assertEquals(emptyList(), CardSpec.spec.properties.map { it.key })
        assertEquals(Cardinality.Many, CardSpec.content.cardinality)
        assertEquals(emptySet(), CardSpec.content.provides)

        val call = assertIs<CodegenBinding.ComposeCall>(CardSpec.spec.codegen)
        assertEquals(KotlinSymbol("androidx.compose.material3", "Card"), call.function)
        assertEquals(emptyList(), call.params)
        assertEquals(LambdaTarget.Trailing, call.slots.single().target)
    }

    private companion object {
        val allSpecs: List<ComponentSpec> = listOf(
            ColumnSpec.spec, RowSpec.spec, BoxSpec.spec, SpacerSpec.spec, TextSpec.spec,
            ButtonSpec.spec, TextFieldSpec.spec, CardSpec.spec,
        )
    }
}
