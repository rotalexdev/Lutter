package dev.rotalex.lutter.editor

import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaletteTest {

    @Test
    fun `sections follow the category declaration order, not the registration order`() {
        val sections = Palette.of(editorSchema())

        // LABEL is registered second and belongs to `Basic`; PANEL is registered first and
        // belongs to `Layout`. Category declares Basic before Layout, so the palette must.
        assertEquals(listOf(Category.Basic, Category.Layout), sections.map { it.category })
    }

    @Test
    fun `entries keep the schema's registration order inside a section`() {
        val sections = Palette.of(editorSchema())

        assertEquals(listOf(LABEL, PANEL), sections.flatMap { section -> section.entries.map { it.type } })
    }

    @Test
    fun `an entry carries the metadata the palette shows`() {
        val label = Palette.of(editorSchema()).flatMap { it.entries }.single { it.type == LABEL }

        assertEquals("Label", label.displayName)
        assertEquals(Category.Basic, label.category)
        assertEquals(listOf("text", "caption"), label.keywords)
        assertEquals(null, label.icon)
    }

    @Test
    fun `a blank display name falls back to the type spelling`() {
        val schema = Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
            component(
                componentSpec(MYSTERY, 1) {
                    metadata("", Category.Input)
                    intrinsic()
                },
            )
        }

        val entry = Palette.of(schema).single().entries.single()

        assertEquals(MYSTERY.value, entry.displayName)
    }

    @Test
    fun `a category nobody fills is omitted rather than rendered empty`() {
        assertTrue(Palette.of(editorSchema()).none { it.entries.isEmpty() })
    }
}
