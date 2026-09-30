package dev.rotalex.lutter.schema

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.TypeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The schema contract: five registries, duplicates named, builds immutable. */
class SchemaTest {

    private typealias Stubs = Schema<String, String, String, String, String>

    private fun buildStubs(
        block: SchemaBuilder<String, String, String, String, String>.() -> Unit,
    ): Stubs = Schema.build(block)

    /** Consumers take the view, not the schema — the probe proves a schema satisfies it. */
    private fun componentCount(view: SchemaView<String, String, String, String, String>): Int =
        view.components.all().size

    @Test
    fun `duplicate component fails naming the key and the registry`() {
        val failure = assertFailsWith<SchemaBuildException> {
            buildStubs {
                component(ComponentType("core.Column"), "first")
                component(ComponentType("core.Column"), "second")
            }
        }

        assertTrue(failure.message?.contains("core.Column") == true)
        assertTrue(failure.message?.contains("components") == true)
    }

    @Test
    fun `each registry names itself in duplicate failures`() {
        val failure = assertFailsWith<SchemaBuildException> {
            buildStubs {
                modifier(ModifierType("layout.padding"), "first")
                modifier(ModifierType("layout.padding"), "second")
            }
        }

        assertTrue(failure.message?.contains("layout.padding") == true)
        assertTrue(failure.message?.contains("modifiers") == true)
    }

    @Test
    fun `duplicate cause stays a duplicate key failure`() {
        val failure = assertFailsWith<SchemaBuildException> {
            buildStubs {
                action(ActionId("nav.navigate"), "first")
                action(ActionId("nav.navigate"), "second")
            }
        }

        assertTrue(failure.cause is DuplicateKeyException)
    }

    @Test
    fun `built schema exposes all five registries read-only`() {
        val schema = buildStubs {
            component(ComponentType("core.Column"), "column")
            modifier(ModifierType("layout.padding"), "padding")
            action(ActionId("nav.navigate"), "navigate")
            function(FunctionId("list.isNotEmpty"), "isNotEmpty")
            type(TypeId("Align"), "align")
        }

        assertEquals(1, componentCount(schema))
        assertEquals("column", schema.components.require(ComponentType("core.Column")))
        assertEquals("padding", schema.modifiers.require(ModifierType("layout.padding")))
        assertEquals("navigate", schema.actions.require(ActionId("nav.navigate")))
        assertEquals("isNotEmpty", schema.functions.require(FunctionId("list.isNotEmpty")))
        assertEquals("align", schema.types.require(TypeId("Align")))
        assertEquals(listOf("column"), schema.components.all())
    }

    @Test
    fun `schema sees only what was registered before build`() {
        val builder = SchemaBuilder<String, String, String, String, String>()
        builder.component(ComponentType("core.Column"), "column")
        val schema: Stubs = builder.build()
        builder.component(ComponentType("core.Row"), "row")

        assertEquals(listOf("column"), schema.components.all())
    }

    @Test
    fun `empty schema has five empty registries`() {
        val schema = buildStubs { }

        assertEquals(emptyList(), schema.components.all())
        assertEquals(emptyList(), schema.modifiers.all())
        assertEquals(emptyList(), schema.actions.all())
        assertEquals(emptyList(), schema.functions.all())
        assertEquals(emptyList(), schema.types.all())
    }
}
