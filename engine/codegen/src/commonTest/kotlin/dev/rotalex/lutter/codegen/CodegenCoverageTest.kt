package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EmitterId
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.CodegenBinding
import dev.rotalex.lutter.schema.component.ComponentMetadata
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Build-time refusal: every spec emits, or the generator is never created. */
class CodegenCoverageTest {

    private val columnType: ComponentType = ComponentType("test.Column")
    private val textType: ComponentType = ComponentType("test.Text")

    @Test
    fun `composed calls with declared refs pass`() {
        CodegenCoverage.check(boundSchema())
    }

    @Test
    fun `the generator refuses an uncovered schema at construction`() {
        val schema = boundSchema()
        val failure = assertFailsWith<IllegalStateException> {
            KotlinGenerator(schema, CodegenOptions("com.example.app"))
        }

        assertTrue(failure.message?.contains("test.Ghost") == true, "got: ${failure.message}")
    }

    @Test
    fun `a custom binding fails without its emitter and passes with it`() {
        val custom = ComponentSpec(
            type = ComponentType("test.Custom"),
            version = 1,
            metadata = componentMetadata(),
            properties = emptyList(),
            slots = emptyList(),
            events = emptyList(),
            codegen = CodegenBinding.Custom(EmitterId("plug.draw")),
        )
        val schema: Schema<ComponentSpec, ModifierSpec, String, String, String> =
            Schema.build { component(custom) }

        val failure = assertFailsWith<IllegalStateException> { CodegenCoverage.check(schema) }
        assertTrue(failure.message?.contains("test.Custom") == true, "got: ${failure.message}")

        val emitter = CustomEmitter { KtExpr.Snippet("Unit", emptyList()) }
        CodegenCoverage.check(
            schema,
            CodegenExtensions(mapOf(EmitterId("plug.draw") to emitter)),
        )
    }

    @Test
    fun `a param reading an undeclared property fails naming it`() {
        val schema: Schema<ComponentSpec, ModifierSpec, String, String, String> =
            Schema.build {
                component(
                    componentSpec(columnType, 1) {
                        metadata("Column", Category.Layout)
                        composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Column"))
                    },
                )
                component(
                    componentSpec(textType, 1) {
                        metadata("Text", Category.Basic)
                        val text = prop<String>("text", TypeRef.Str, required = true)
                        property(text)
                        composeCall(KotlinSymbol("androidx.compose.material3", "Text")) {
                            param("text", from = listOf(PropertyKey("label")))
                        }
                    },
                )
            }

        val failure = assertFailsWith<IllegalStateException> { CodegenCoverage.check(schema) }
        assertTrue(failure.message?.contains("label") == true, "got: ${failure.message}")
    }

    @Test
    fun `events fail until they emit`() {
        val schema: Schema<ComponentSpec, ModifierSpec, String, String, String> =
            Schema.build {
                component(
                    componentSpec(columnType, 1) {
                        metadata("Column", Category.Layout)
                        slot("children", Cardinality.Many)
                        composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Column")) {
                            event(EventKey("onClick"), "onClick")
                            slot("children", LambdaTarget.Trailing)
                        }
                    },
                )
            }

        val failure = assertFailsWith<IllegalStateException> { CodegenCoverage.check(schema) }
        assertTrue(failure.message?.contains("test.Column") == true, "got: ${failure.message}")
    }

    private fun boundSchema(): Schema<ComponentSpec, ModifierSpec, String, String, String> =
        Schema.build {
            component(
                componentSpec(columnType, 1) {
                    metadata("Column", Category.Layout)
                    slot("children", Cardinality.Many)
                    composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Column")) {
                        slot("children", LambdaTarget.Trailing)
                    }
                },
            )
            component(
                componentSpec(textType, 1) {
                    metadata("Text", Category.Basic)
                    val text = prop<String>("text", TypeRef.Str, required = true)
                    property(text)
                    composeCall(KotlinSymbol("androidx.compose.material3", "Text")) {
                        param("text", from = text)
                    }
                },
            )
            component(
                componentSpec(ComponentType("test.Ghost"), 1) {
                    metadata("Ghost", Category.Basic)
                    intrinsic()
                },
            )
        }

    private fun componentMetadata(): ComponentMetadata =
        ComponentMetadata("Custom", Category.Basic)
}
