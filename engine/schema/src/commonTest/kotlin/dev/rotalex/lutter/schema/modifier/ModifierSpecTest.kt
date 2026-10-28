package dev.rotalex.lutter.schema.modifier

import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuildException
import dev.rotalex.lutter.schema.component.EmitCase
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.ScopeId
import dev.rotalex.lutter.schema.component.prop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The S3 modifier surface: the §7.3 shape, its scope gate, and the S1 joint it binds.
 *
 * Each promise carries its own failure: the scope default, the unconditional emit, and the
 * duplicate key are asserted alongside the shapes they guard.
 */
class ModifierSpecTest {

    private val paddingSpec: ModifierSpec = ModifierSpec(
        type = ModifierType("layout.padding"),
        metadata = ModifierMetadata("Padding"),
        params = listOf(
            prop<Int>("all", TypeRef.Nullable(TypeRef.Dp)),
            prop<Int>("horizontal", TypeRef.Nullable(TypeRef.Dp)),
            prop<Int>("vertical", TypeRef.Nullable(TypeRef.Dp)),
        ),
        emit = ModifierEmit(
            function = KotlinSymbol("androidx.compose.foundation.layout", "padding"),
            cases = listOf(
                EmitCase(setOf(PropertyKey("all")), "padding({all})"),
                EmitCase(
                    setOf(PropertyKey("horizontal"), PropertyKey("vertical")),
                    "padding(horizontal = {horizontal}, vertical = {vertical})",
                ),
            ),
        ),
    )

    private val weightSpec: ModifierSpec = ModifierSpec(
        type = ModifierType("layout.weight"),
        metadata = ModifierMetadata("Weight", since = 1),
        params = listOf(prop<Float>("weight", TypeRef.Float32, required = true)),
        requiresScope = setOf(ScopeId("compose.RowScope")),
        emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation.layout", "weight")),
    )

    @Test
    fun `padding carries its params and its case split`() {
        assertEquals(ModifierType("layout.padding"), paddingSpec.type)
        assertEquals("Padding", paddingSpec.metadata.displayName)
        assertEquals(
            listOf(PropertyKey("all"), PropertyKey("horizontal"), PropertyKey("vertical")),
            paddingSpec.params.map { it.key },
        )
        assertEquals(2, paddingSpec.emit.cases.size)
        assertEquals(
            setOf(PropertyKey("horizontal"), PropertyKey("vertical")),
            paddingSpec.emit.cases[1].whenPresent,
        )
    }

    @Test
    fun `a fresh spec requires no scope and documents nothing`() {
        assertEquals(emptyList(), paddingSpec.emit.cases.first().imports)
        assertEquals(emptySet(), paddingSpec.requiresScope)
        assertEquals("", paddingSpec.metadata.description)
        assertEquals(1, paddingSpec.metadata.since)
    }

    @Test
    fun `weight is gated on the row scope and emits unconditionally`() {
        assertEquals(setOf(ScopeId("compose.RowScope")), weightSpec.requiresScope)
        assertEquals(emptyList(), weightSpec.emit.cases)
        assertEquals(
            KotlinSymbol("androidx.compose.foundation.layout", "weight"),
            weightSpec.emit.function,
        )
    }

    @Test
    fun `a fresh emit imports its function and names no scope`() {
        assertFalse(weightSpec.emit.scopeMember)
        assertEquals(emptyMap(), weightSpec.emit.scopeEntries)
    }

    @Test
    fun `a scope member declares the entry each scope reads`() {
        val rowScope = ScopeId("compose.RowScope")
        val emit = ModifierEmit(
            function = KotlinSymbol("androidx.compose.foundation.layout", "align"),
            cases = listOf(EmitCase(setOf(PropertyKey("alignment")), "align({alignment})")),
            scopeMember = true,
            scopeEntries = mapOf(
                rowScope to mapOf("BottomRight" to KotlinSymbol("androidx.compose.ui", "Alignment.Bottom")),
            ),
        )

        assertTrue(emit.scopeMember)
        assertEquals(
            KotlinSymbol("androidx.compose.ui", "Alignment.Bottom"),
            emit.scopeEntries.getValue(rowScope).getValue("BottomRight"),
        )
    }

    @Test
    fun `the joint registers the spec under its own type`() {
        val schema: ModifierSchema<String, String, String, String> =
            Schema.build<String, ModifierSpec, String, String, String> {
                modifier(paddingSpec)
                modifier(weightSpec)
            }

        assertSame(paddingSpec, schema.modifiers.require(ModifierType("layout.padding")))
        assertSame(weightSpec, schema.modifiers.require(ModifierType("layout.weight")))
        assertEquals(listOf(paddingSpec, weightSpec), schema.modifiers.all())
    }

    @Test
    fun `two specs on one key fail naming the key and the registry`() {
        val failure = assertFailsWith<SchemaBuildException> {
            Schema.build<String, ModifierSpec, String, String, String> {
                modifier(paddingSpec)
                modifier(paddingSpec)
            }
        }

        assertTrue(failure.message?.contains("layout.padding") == true)
        assertTrue(failure.message?.contains("modifiers") == true)
    }

    @Test
    fun `an unknown modifier fails at read time`() {
        val schema: ModifierSchema<String, String, String, String> =
            Schema.build<String, ModifierSpec, String, String, String> {
                modifier(paddingSpec)
            }

        assertFailsWith<NoSuchElementException> {
            schema.modifiers.require(ModifierType("layout.weight"))
        }
    }
}
