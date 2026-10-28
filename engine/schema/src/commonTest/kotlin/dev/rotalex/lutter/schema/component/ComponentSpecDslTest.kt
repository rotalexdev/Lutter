package dev.rotalex.lutter.schema.component

import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuildException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The S2 surface: the §7.4 authoring shape, its defaults, and the S1 joint it binds.
 *
 * Each promise carries its own failure: a test that cannot fail proves nothing, so the
 * omissions (no metadata, no codegen), the repeats (two codegens) and the collisions
 * (two specs, one key) are asserted alongside the shape they guard.
 */
class ComponentSpecDslTest {

    private val text: PropertySpec<String> = prop("text", TypeRef.Str, required = true)
    private val color: PropertySpec<ColorArgb?> =
        prop("color", TypeRef.Nullable(TypeRef.Color))
    private val style: PropertySpec<TokenName?> =
        prop("style", TypeRef.Nullable(TypeRef.Token(TokenKind.Typography)))

    private val textSpec: ComponentSpec = componentSpec(ComponentType("m3.Text"), version = 1) {
        metadata(displayName = "Text", category = Category.Basic)
        property(text)
        property(color)
        property(style)
        composeCall(KotlinSymbol("androidx.compose.material3", "Text")) {
            param("text", from = text, positional = Positional.WhenSole)
            param("color", from = color)
            param("style", from = style)
        }
    }

    private val columnSpec: ComponentSpec = componentSpec(ComponentType("core.Column"), version = 1) {
        metadata(displayName = "Column", category = Category.Layout)
        property(prop<Float>("spacing", TypeRef.Nullable(TypeRef.Dp)))
        property(prop<String>("verticalArrangement", TypeRef.Nullable(TypeRef.Enum(TYPE_ALIGN))))
        rule(PropertyRule.MutuallyExclusive(setOf(PropertyKey("spacing"), PropertyKey("verticalArrangement"))))
        slot("content", Cardinality.Many, provides = setOf(ScopeId("compose.ColumnScope")))
        event("onClick")
        composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Column")) {
            param(
                "verticalArrangement",
                from = listOf(PropertyKey("spacing"), PropertyKey("verticalArrangement")),
                emit = ValueEmit.Cases(
                    listOf(
                        EmitCase(
                            setOf(PropertyKey("spacing")),
                            "Arrangement.spacedBy({spacing})",
                        ),
                        EmitCase(
                            setOf(PropertyKey("verticalArrangement")),
                            "{verticalArrangement}",
                        ),
                    ),
                ),
            )
            slot("content", LambdaTarget.Trailing)
        }
    }

    @Test
    fun `the dsl builds the text shape`() {
        assertEquals(ComponentType("m3.Text"), textSpec.type)
        assertEquals(1, textSpec.version)
        assertEquals("Text", textSpec.metadata.displayName)
        assertEquals(Category.Basic, textSpec.metadata.category)
        assertEquals(listOf(text.key, color.key, style.key), textSpec.properties.map { it.key })

        val call = assertIs<CodegenBinding.ComposeCall>(textSpec.codegen)
        assertEquals(KotlinSymbol("androidx.compose.material3", "Text"), call.function)
        assertEquals("modifier", call.modifierParam)
        assertEquals(listOf("text", "color", "style"), call.params.map { it.param })
        assertEquals(Positional.WhenSole, call.params.first().positional)
    }

    @Test
    fun `prop carries its declaration and the quiet defaults`() {
        assertEquals(PropertyKey("text"), text.key)
        assertEquals(TypeRef.Str, text.type)
        assertEquals(true, text.required)

        assertEquals(null, color.default)
        assertEquals(false, color.required)
        assertEquals(true, color.bindable)
        assertEquals(EditorHints.None, color.editor)
        assertEquals("", color.doc)
    }

    @Test
    fun `a fresh spec says nothing beyond its declaration`() {
        assertEquals(PlatformTag.ALL, textSpec.availability)
        assertEquals(ModifierPolicy.All, textSpec.modifiers)
        assertEquals(emptyList(), textSpec.rules)
        assertEquals(emptyList(), textSpec.slots)
        assertEquals(emptyList(), textSpec.events)
        assertEquals(SpecOrigin.Static, textSpec.origin)
    }

    @Test
    fun `the column shape carries rules slots events and cases`() {
        assertEquals(1, columnSpec.rules.size)
        assertEquals(
            PropertyRule.MutuallyExclusive(
                setOf(PropertyKey("spacing"), PropertyKey("verticalArrangement")),
            ),
            columnSpec.rules.single(),
        )
        assertEquals(Cardinality.Many, columnSpec.slots.single().cardinality)
        assertEquals(setOf(ScopeId("compose.ColumnScope")), columnSpec.slots.single().provides)
        assertEquals(1, columnSpec.events.size)

        val call = assertIs<CodegenBinding.ComposeCall>(columnSpec.codegen)
        val arrangement = call.params.single()
        assertEquals(
            listOf(PropertyKey("spacing"), PropertyKey("verticalArrangement")),
            arrangement.from,
        )
        assertIs<ValueEmit.Cases>(arrangement.emit)
        assertEquals(1, call.slots.size)
    }

    @Test
    fun `the joint registers the spec under its own type`() {
        val schema: ComponentSchema<String, String, String, String> =
            Schema.build<ComponentSpec, String, String, String, String> {
                component(textSpec)
                component(columnSpec)
            }

        assertSame(textSpec, schema.components.require(ComponentType("m3.Text")))
        assertSame(columnSpec, schema.components.require(ComponentType("core.Column")))
        assertEquals(listOf(columnSpec, textSpec), schema.components.all())
    }

    @Test
    fun `two specs on one key fail naming the key and the registry`() {
        val failure = assertFailsWith<SchemaBuildException> {
            Schema.build<ComponentSpec, String, String, String, String> {
                component(textSpec)
                component(textSpec)
            }
        }

        assertTrue(failure.message?.contains("m3.Text") == true)
        assertTrue(failure.message?.contains("components") == true)
    }

    @Test
    fun `a spec without codegen fails naming it`() {
        val failure = assertFailsWith<IllegalStateException> {
            componentSpec(ComponentType("m3.Text"), version = 1) {
                metadata(displayName = "Text", category = Category.Basic)
                property(text)
            }
        }

        assertTrue(failure.message?.contains("codegen") == true)
    }

    @Test
    fun `a spec without metadata fails naming it`() {
        val failure = assertFailsWith<IllegalStateException> {
            componentSpec(ComponentType("m3.Text"), version = 1) {
                intrinsic()
            }
        }

        assertTrue(failure.message?.contains("metadata") == true)
    }

    @Test
    fun `a second codegen fails instead of overriding`() {
        assertFailsWith<IllegalStateException> {
            componentSpec(ComponentType("m3.Text"), version = 1) {
                metadata(displayName = "Text", category = Category.Basic)
                intrinsic()
                intrinsic()
            }
        }
    }

    @Test
    fun `a second metadata block fails instead of overriding`() {
        assertFailsWith<IllegalStateException> {
            componentSpec(ComponentType("m3.Text"), version = 1) {
                metadata(displayName = "Text", category = Category.Basic)
                metadata(displayName = "Text", category = Category.Basic)
                intrinsic()
            }
        }
    }

    private companion object {
        private val TYPE_ALIGN = TypeId("Align")
    }
}
