package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.KotlinSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `TypeRef` to Kotlin type name, both halves of it.
 *
 * The mapped half is asserted as an expression, because that is what a property stores; the
 * unmapped half is asserted by reason, because a refusal that names nothing is a refusal the
 * caller cannot act on. Nothing here throws: §16.7 makes an unmappable type a diagnostic, and
 * the type arrives from the user's document.
 */
class KotlinTypesTest {

    @Test
    fun `the scalars map to the Kotlin names of their runtime type`() {
        assertEquals(name("Boolean"), spelling(TypeRef.Bool))
        assertEquals(name("Int"), spelling(TypeRef.Int32))
        assertEquals(name("Long"), spelling(TypeRef.Int64))
        assertEquals(name("Float"), spelling(TypeRef.Float32))
        assertEquals(name("Double"), spelling(TypeRef.Float64))
        assertEquals(name("String"), spelling(TypeRef.Str))
        assertEquals(name("String"), spelling(TypeRef.Url))
    }

    @Test
    fun `a compose type is a reference so its import computes`() {
        assertEquals(
            KtExpr.Ref(KtSymbolRef(KotlinSymbol("androidx.compose.ui.graphics", "Color"))),
            spelling(TypeRef.Color),
        )
        assertEquals(
            KtExpr.Ref(KtSymbolRef(KotlinSymbol("androidx.compose.ui.unit", "Dp"))),
            spelling(TypeRef.Dp),
        )
        assertEquals(
            KtExpr.Ref(KtSymbolRef(KotlinSymbol("androidx.compose.ui.unit", "TextUnit"))),
            spelling(TypeRef.Sp),
        )
    }

    @Test
    fun `a mapped type reaches the printer as a compiling declaration`() {
        val gap = KtDeclaration.Property(
            "gap",
            emptyList(),
            type(TypeRef.Dp),
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
    fun `a composite is built as IR, so a nullable reaches inside the brackets`() {
        assertEquals(
            KtExpr.TypeApplication(KtExpr.Name("List"), listOf(name("Int"))),
            spelling(TypeRef.ListOf(TypeRef.Int32)),
        )
        // §9.1 fixes the key: `MapOf` carries the value type alone, so the mapper writes String.
        assertEquals(
            KtExpr.TypeApplication(
                KtExpr.Name("Map"),
                listOf(name("String"), KtExpr.Nullable(name("Int"))),
            ),
            spelling(TypeRef.MapOf(TypeRef.Nullable(TypeRef.Int32))),
        )
        assertEquals(KtExpr.Nullable(name("String")), spelling(TypeRef.Nullable(TypeRef.Str)))
    }

    @Test
    fun `a composite of a type nothing can spell refuses with the inner's own reason`() {
        // A `List<Enum>` has no Kotlin spelling either, and repeating "a list is a type
        // application" would name an IR gap that closed with D16 instead of what to fix.
        val refusal = KotlinTypes.spellingFor(TypeRef.ListOf(TypeRef.Enum(TypeId("testAlignment"))))

        assertEquals(
            TypeSpelling.Unspelled("no Kotlin type name is carried by it or its spec"),
            refusal,
        )
    }

    @Test
    fun `every variant without a settled Kotlin type is reported by name`() {
        val unmappable: List<TypeRef> = listOf(
            TypeRef.Enum(TypeId("testAlignment")),
            TypeRef.Object(TypeId("testUser")),
            TypeRef.Ref(RefKind.Page),
            TypeRef.Token(TokenKind.Color),
            TypeRef.Icon(null),
            TypeRef.Dimension,
        )

        for (typeRef in unmappable) {
            val refusal = KotlinTypes.spellingFor(typeRef)
            assertTrue(refusal is TypeSpelling.Unspelled, "$typeRef is mapped: $refusal")
            val reason = (refusal as TypeSpelling.Unspelled).reason
            assertTrue(reason.isNotEmpty(), typeRef.serialTag)
        }
    }

    @Test
    fun `an unmappable type prints its tag and its reason, which is what the diagnostic says`() {
        val refusal = KotlinTypes.spellingFor(TypeRef.Enum(TypeId("testAlignment")))

        assertEquals(
            TypeSpelling.Unspelled("no Kotlin type name is carried by it or its spec"),
            refusal,
        )
    }

    private fun spelling(typeRef: TypeRef): KtExpr {
        val spelled = KotlinTypes.spellingFor(typeRef)
        assertTrue(spelled is TypeSpelling.Spelled, "$typeRef is unmapped: $spelled")
        return (spelled as TypeSpelling.Spelled).expr
    }

    private fun type(typeRef: TypeRef): KtExpr = spelling(typeRef)

    private fun name(text: String): KtExpr = KtExpr.Name(text)
}