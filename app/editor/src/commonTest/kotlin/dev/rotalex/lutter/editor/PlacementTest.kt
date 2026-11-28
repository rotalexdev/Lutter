package dev.rotalex.lutter.editor

import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.model.type.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The canvas names `layout.offset` itself, because the builtin schema is a test-scope edge of this
 * module. This is the test that keeps that name honest: it runs where the schema is visible and
 * fails if the modifier is renamed, moved, or loses an axis.
 */
class PlacementTest {

    @Test
    fun `the installed schema declares the offset the canvas writes`() {
        val spec = BuiltinEngine.schema().modifiers[Placement.offset]

        assertNotNull(spec, "the builtin schema declares no ${Placement.offset.value}")
        assertEquals(listOf(Placement.x, Placement.y), spec.params.map { it.key })
        assertTrue(spec.params.all { it.type == TypeRef.Dp }, "both axes are dp: ${spec.params}")
    }
}
