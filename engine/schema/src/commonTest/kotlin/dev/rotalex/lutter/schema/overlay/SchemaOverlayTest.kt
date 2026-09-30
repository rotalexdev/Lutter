package dev.rotalex.lutter.schema.overlay

import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.ComponentDecl
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.ParamDecl
import dev.rotalex.lutter.model.doc.SlotDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.CodegenBinding
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.SpecOrigin
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The S4 overlay: synthesized specs resolve beside static ones, under `doc.*` only.
 *
 * Each promise carries its own failure: the static shadow, the unknown key and the
 * recorded-version override are asserted alongside the synthesis they guard.
 */
class SchemaOverlayTest {

    private val columnSpec: ComponentSpec =
        componentSpec(ComponentType("core.Column"), version = 1) {
            metadata(displayName = "Column", category = Category.Layout)
            intrinsic()
        }

    private val cardDecl: ComponentDecl = ComponentDecl(
        id = ComponentDeclId("card"),
        name = "Card",
        params = listOf(ParamDecl(ParamName("title"), TypeRef.Str)),
        slots = listOf(SlotDecl(SlotName("content"))),
        root = NodeId("n1"),
    )

    private fun document(
        decl: ComponentDecl = cardDecl,
        versions: Map<ComponentType, Int> = emptyMap(),
    ): UiDocument = UiDocument(
        meta = DocumentMeta(name = "test", componentVersions = versions),
        app = AppSpec(packageName = "com.example", startPage = PageId("home")),
        pages = emptyMap(),
        components = mapOf(decl.id to decl),
        nodes = NodeTable.EMPTY,
    )

    private fun overlay(document: UiDocument = document()): SchemaOverlay<String, String, String, String> {
        val schema: ComponentSchemaForOverlay =
            Schema.build<ComponentSpec, String, String, String, String> {
                component(columnSpec)
                modifier(ModifierType("layout.padding"), "padding")
            }
        return SchemaOverlay(schema, document)
    }

    @Test
    fun `a declaration synthesizes beside the static schema`() {
        val view = overlay()

        assertSame(columnSpec, view.components.require(ComponentType("core.Column")))
        val card = view.components.require(ComponentType("doc.card"))
        assertEquals(listOf(PropertyKey("title")), card.properties.map { it.key })
        assertEquals(true, card.properties.single().required)
        assertEquals(Cardinality.Many, card.slots.single().cardinality)
        assertEquals(emptySet(), card.slots.single().provides)
        assertIs<CodegenBinding.Intrinsic>(card.codegen)
        assertEquals(SpecOrigin.Document(ComponentDeclId("card")), card.origin)
        assertEquals(1, card.version)
    }

    @Test
    fun `the recorded contract version wins over the default`() {
        val view = overlay(document(versions = mapOf(ComponentType("doc.card") to 3)))

        assertEquals(3, view.components.require(ComponentType("doc.card")).version)
    }

    @Test
    fun `the other four registries delegate untouched`() {
        val view = overlay()

        assertEquals("padding", view.modifiers.require(ModifierType("layout.padding")))
        assertEquals(emptyList(), view.actions.all())
        assertEquals(emptyList(), view.functions.all())
        assertEquals(emptyList(), view.types.all())
    }

    @Test
    fun `a static doc key fails naming the reservation`() {
        val shadow: ComponentSpec =
            componentSpec(ComponentType("doc.card"), version = 1) {
                metadata(displayName = "Shadow", category = Category.Basic)
                intrinsic()
            }
        val schema: ComponentSchemaForOverlay =
            Schema.build<ComponentSpec, String, String, String, String> {
                component(shadow)
            }

        val failure = assertFailsWith<IllegalStateException> {
            SchemaOverlay(schema, document())
        }

        assertTrue(failure.message?.contains("doc.card") == true)
    }

    @Test
    fun `an unknown component fails at read time`() {
        val view = overlay()

        assertFailsWith<NoSuchElementException> {
            view.components.require(ComponentType("doc.missing"))
        }
    }

    private typealias ComponentSchemaForOverlay =
        Schema<ComponentSpec, String, String, String, String>
}
