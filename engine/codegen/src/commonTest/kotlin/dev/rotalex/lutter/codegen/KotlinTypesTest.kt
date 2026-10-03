package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.KotlinSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `TypeRef` to Kotlin type name, both halves of it.
 *
 * The mapped half is asserted as an expression, because that is what a property stores; the
 * refused half is asserted by message, because a refusal that names nothing is a refusal the
 * caller cannot act on.
 */
class KotlinTypesTest {

    @Test
    fun `the scalars map to the Kotlin names of their runtime type`() {
        assertEquals(KtExpr.Name("Boolean"), KotlinTypes.exprFor(TypeRef.Bool))
        assertEquals(KtExpr.Name("Int"), KotlinTypes.exprFor(TypeRef.Int32))
        assertEquals(KtExpr.Name("Long"), KotlinTypes.exprFor(TypeRef.Int64))
        assertEquals(KtExpr.Name("Float"), KotlinTypes.exprFor(TypeRef.Float32))
        assertEquals(KtExpr.Name("Double"), KotlinTypes.exprFor(TypeRef.Float64))
        assertEquals(KtExpr.Name("String"), KotlinTypes.exprFor(TypeRef.Str))
        assertEquals(KtExpr.Name("String"), KotlinTypes.exprFor(TypeRef.Url))
    }

    @Test
    fun `a compose type is a reference so its import computes`() {
        assertEquals(
            KtExpr.Ref(KtSymbolRef(KotlinSymbol("androidx.compose.ui.graphics", "Color"))),
            KotlinTypes.exprFor(TypeRef.Color),
        )
        assertEquals(
            KtExpr.Ref(KtSymbolRef(KotlinSymbol("androidx.compose.ui.unit", "Dp"))),
            KotlinTypes.exprFor(TypeRef.Dp),
        )
        assertEquals(
            KtExpr.Ref(KtSymbolRef(KotlinSymbol("androidx.compose.ui.unit", "TextUnit"))),
            KotlinTypes.exprFor(TypeRef.Sp),
        )
    }

    @Test
    fun `a mapped type reaches the printer as a compiling declaration`() {
        val gap = KtDeclaration.Property(
            "gap",
            emptyList(),
            KotlinTypes.exprFor(TypeRef.Dp),
            false,
            null,
            null,
            null,
        )
        val printed = KtPrinter().print(KtFile("com.example.app", null, listOf(gap)))

        assertTrue(printed.contains("${"import"} androidx.compose.ui.unit.Dp"), printed)
        assertTrue(printed.contains("public val gap: Dp"), printed)
    }

    @Test
    fun `every variant without a settled Kotlin type is refused by name`() {
        val refused: List<TypeRef> = listOf(
            TypeRef.Nullable(TypeRef.Str),
            TypeRef.ListOf(TypeRef.Str),
            TypeRef.MapOf(TypeRef.Str),
            TypeRef.Enum(TypeId("testAlignment")),
            TypeRef.Object(TypeId("testUser")),
            TypeRef.Ref(RefKind.Page),
            TypeRef.Token(TokenKind.Color),
            TypeRef.Icon(null),
            TypeRef.Dimension,
        )

        for (typeRef in refused) {
            val prefix: String = "No Kotlin type for " + typeRef.serialTag + ": "
            val failure = assertFailsWith<CodegenBug>("expected $typeRef refused") {
                KotlinTypes.exprFor(typeRef)
            }
            val message: String? = failure.message
            assertTrue(message != null && message.startsWith(prefix), message)
            assertTrue((message?.length ?: 0) > prefix.length, message)
        }
    }
}