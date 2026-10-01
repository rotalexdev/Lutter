package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.EnumTypeSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The §31.2 modifier set: each spec's shape, the enum types they name, and the wiring. */
class BuiltinModifierSpecsTest {

    private val specs = LayoutModifiers.all + DecorationModifiers.all + InteractionModifiers.all

    @Test
    fun `weight is gated on the row scope and emits one call`() {
        assertEquals(ModifierType("layout.weight"), WeightModifier.spec.type)
        assertEquals(setOf(LayoutScopes.Row), WeightModifier.spec.requiresScope)
        assertEquals(KotlinSymbol("androidx.compose.foundation.layout", "weight"), WeightModifier.spec.emit.function)
        assertEquals(emptyList(), WeightModifier.spec.emit.cases)
        assertEquals(listOf(PropertyKey("weight")), WeightModifier.spec.params.map { it.key })
        assertEquals(Value.Float32(1f), WeightModifier.spec.params.single().default)
    }

    @Test
    fun `padding picks a call from which arguments are present`() {
        assertEquals(
            listOf(PropertyKey("all"), PropertyKey("horizontal"), PropertyKey("vertical")),
            PaddingModifier.spec.params.map { it.key },
        )
        val cases = PaddingModifier.spec.emit.cases
        assertEquals(4, cases.size)
        assertEquals(
            listOf(
                "padding({all})",
                "padding(horizontal = {horizontal}, vertical = {vertical})",
                "padding(horizontal = {horizontal})",
                "padding(vertical = {vertical})",
            ),
            cases.map { it.pattern },
        )
        assertEquals(setOf(PropertyKey("all")), cases.first().whenPresent)
        assertEquals(
            setOf(PropertyKey("horizontal"), PropertyKey("vertical")),
            cases[1].whenPresent,
        )
    }

    @Test
    fun `the argument-free modifiers carry no params and no cases`() {
        for (spec in listOf(FillMaxSizeModifier, FillMaxWidthModifier, FillMaxHeightModifier, ClickableModifier)) {
            assertEquals(emptyList(), spec.spec.params, "${spec.spec.type}")
            assertEquals(emptyList(), spec.spec.emit.cases, "${spec.spec.type}")
            assertEquals(emptySet(), spec.spec.requiresScope, "${spec.spec.type}")
        }
        assertEquals(
            "androidx.compose.foundation.layout",
            FillMaxSizeModifier.spec.emit.function.packageName,
        )
    }

    @Test
    fun `the enum-typed arguments name the enum type they read`() {
        assertEquals(TypeRef.Enum(AlignmentSpec.Id), AlignModifier.alignment.type)
        assertEquals(TypeRef.Enum(ShapeSpec.Id), ClipModifier.shape.type)
        assertEquals("androidx.compose.ui.draw", ClipModifier.spec.emit.function.packageName)
    }

    @Test
    fun `the set is the plan's fourteen, and no type is claimed twice`() {
        assertEquals(14, specs.size)
        assertEquals(specs.size, specs.map { it.type }.toSet().size)
        assertEquals(
            listOf(
                "decoration.alpha",
                "decoration.background",
                "decoration.border",
                "decoration.clip",
                "interaction.clickable",
                "layout.align",
                "layout.fillMaxHeight",
                "layout.fillMaxSize",
                "layout.fillMaxWidth",
                "layout.height",
                "layout.padding",
                "layout.size",
                "layout.weight",
                "layout.width",
            ),
            specs.map { it.type.value }.sorted(),
        )
    }

    @Test
    fun `a scope has one spelling, shared by the slot that provides it`() {
        assertEquals("compose.RowScope", LayoutScopes.Row.value)
        assertEquals("compose.ColumnScope", LayoutScopes.Column.value)
        assertEquals("compose.BoxScope", LayoutScopes.Box.value)
        assertEquals(setOf(LayoutScopes.Column), ColumnSpec.children.provides)
    }

    @Test
    fun `the schema pair wires the modifiers and the enums they read`() {
        val schema: Schema<ComponentSpec, ModifierSpec, String, String, TypeSpec> =
            Schema.build<ComponentSpec, ModifierSpec, String, String, TypeSpec> {
                registerBuiltinModifiers()
                registerBuiltinEnums()
            }

        for (spec in specs) {
            assertSame(spec, schema.modifiers[spec.type], "${spec.type}")
        }
        assertEquals(14, schema.modifiers.all().size)
        assertIs<EnumTypeSpec>(schema.types[AlignmentSpec.Id])
        assertIs<EnumTypeSpec>(schema.types[ShapeSpec.Id])
    }

    @Test
    fun `outside the pair nothing resolves`() {
        val schema: Schema<ComponentSpec, ModifierSpec, String, String, TypeSpec> =
            Schema.build<ComponentSpec, ModifierSpec, String, String, TypeSpec> {
                registerBuiltinModifiers()
            }

        assertNull(schema.modifiers[ModifierType("layout.ghost")])
        assertNull(schema.types[AlignmentSpec.Id])
    }

    @Test
    fun `the alignment enum is the nine entries with member-qualified symbols`() {
        assertEquals(
            listOf(
                "TopLeft", "TopCenter", "TopRight",
                "CenterLeft", "Center", "CenterRight",
                "BottomLeft", "BottomCenter", "BottomRight",
            ),
            AlignmentSpec.entries.map { it.name },
        )
        assertEquals(
            KotlinSymbol("androidx.compose.ui", "Alignment.Center"),
            AlignmentSpec.entries[4].kotlin,
        )
        assertEquals(AlignmentSpec.Id, AlignmentSpec.spec.id)
    }

    @Test
    fun `the shape enum names the two shapes a clip can carry`() {
        assertEquals(listOf("Rectangle", "Circle"), ShapeSpec.entries.map { it.name })
        assertEquals(
            listOf(
                KotlinSymbol("androidx.compose.foundation.shape", "RectangleShape"),
                KotlinSymbol("androidx.compose.foundation.shape", "CircleShape"),
            ),
            ShapeSpec.entries.map { it.kotlin },
        )
    }
}
