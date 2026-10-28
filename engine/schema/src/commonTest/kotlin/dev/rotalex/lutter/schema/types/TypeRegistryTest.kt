package dev.rotalex.lutter.schema.types

import dev.rotalex.lutter.model.doc.FieldDecl
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuildException
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.registry.DuplicateKeyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The S4 types surface: the one-namespace registry, icon sets, and the S1 joint it binds.
 *
 * Each promise carries its own failure: the cross-kind duplicate, the wrong-kind lookup
 * and the missing key are asserted alongside the shapes they guard.
 */
class TypeRegistryTest {

    private val alignSpec: EnumTypeSpec = EnumTypeSpec(
        id = TypeId("Align"),
        entries = listOf(
            EnumEntrySpec("Start", KotlinSymbol("androidx.compose.ui", "Alignment.Start")),
            EnumEntrySpec("Center", KotlinSymbol("androidx.compose.ui", "Alignment.Center")),
        ),
    )

    private val userSpec: ObjectTypeSpec = ObjectTypeSpec(
        id = TypeId("User"),
        fields = listOf(
            FieldDecl(PropertyKey("name"), TypeRef.Str),
            FieldDecl(PropertyKey("age"), TypeRef.Int32),
        ),
    )

    private val icons: IconSet = IconSet(
        name = "core",
        icons = mapOf("Home" to KotlinSymbol("androidx.compose.material.icons", "Icons.Filled.Home")),
    )

    private fun registry(): TypeRegistry = TypeRegistryBuilder().apply {
        register(alignSpec)
        register(userSpec)
        iconSet(icons)
    }.build()

    @Test
    fun `typed getters return each kind under its own id`() {
        val types = registry()

        assertSame(alignSpec, types.enumType(TypeId("Align")))
        assertSame(userSpec, types.objectType(TypeId("User")))
        assertSame(icons, types.iconSet("core"))
    }

    @Test
    fun `a getter for the wrong kind returns null instead of casting`() {
        val types = registry()

        assertNull(types.enumType(TypeId("User")))
        assertNull(types.objectType(TypeId("Align")))
    }

    @Test
    fun `an enum and an object never share an id`() {
        val failure = assertFailsWith<DuplicateKeyException> {
            TypeRegistryBuilder().apply {
                register(alignSpec)
                register(ObjectTypeSpec(TypeId("Align"), emptyList()))
            }
        }

        assertTrue(failure.message?.contains("Align") == true)
    }

    @Test
    fun `two icon sets on one name fail naming it`() {
        assertFailsWith<DuplicateKeyException> {
            TypeRegistryBuilder().apply {
                iconSet(icons)
                iconSet(icons)
            }
        }
    }

    @Test
    fun `an icon resolves to its symbol and a missing one to null`() {
        assertEquals(
            KotlinSymbol("androidx.compose.material.icons", "Icons.Filled.Home"),
            icons["Home"],
        )
        assertNull(icons["Missing"])
    }

    @Test
    fun `the joint registers the spec under its own id`() {
        val schema: TypeSchema<String, String, String, String> =
            Schema.build<String, String, String, String, TypeSpec> {
                type(alignSpec)
                type(userSpec)
            }

        assertSame(alignSpec, schema.types.require(TypeId("Align")))
        assertSame(userSpec, schema.types.require(TypeId("User")))
        assertEquals(listOf(alignSpec, userSpec), schema.types.all())
    }

    @Test
    fun `two specs on one key fail naming the key and the registry`() {
        val failure = assertFailsWith<SchemaBuildException> {
            Schema.build<String, String, String, String, TypeSpec> {
                type(alignSpec)
                type(alignSpec)
            }
        }

        assertTrue(failure.message?.contains("Align") == true)
        assertTrue(failure.message?.contains("types") == true)
    }

    @Test
    fun `an unknown type fails at read time`() {
        val types = registry()

        assertNull(types.enumType(TypeId("Missing")))
        assertFailsWith<NoSuchElementException> {
            types.types.require(TypeId("Missing"))
        }
    }
}
