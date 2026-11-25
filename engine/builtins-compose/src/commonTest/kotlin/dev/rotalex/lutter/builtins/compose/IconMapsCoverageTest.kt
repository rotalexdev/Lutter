package dev.rotalex.lutter.builtins.compose

import dev.rotalex.lutter.builtins.MaterialIconSet
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The icon maps' bijection: the schema side and the runtime side must know the same names.
 *
 * §20:1599's two halves live on opposite sides of the module graph — `MaterialIconSet` maps
 * names to Kotlin symbols for validation and codegen, `MaterialIconImages` maps them to
 * vectors for composition — and nothing at construction time sees both. The registry
 * key is what they share, so that is what this holds: a name registered on one side only
 * would validate in one pipeline and render in the other.
 */
class IconMapsCoverageTest {
    @Test
    fun `the runtime map knows exactly the names the schema set knows`() {
        assertEquals(
            MaterialIconSet.set.icons.keys.sorted(),
            MaterialIconImages.images.keys.sorted(),
            "the schema set and the runtime resolver disagree — every name must live on both " +
                "sides or an icon renders in one pipeline and fails in the other",
        )
    }
}
