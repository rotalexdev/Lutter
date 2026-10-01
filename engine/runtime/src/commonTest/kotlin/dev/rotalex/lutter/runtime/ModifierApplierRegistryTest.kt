package dev.rotalex.lutter.runtime

import androidx.compose.ui.Modifier
import dev.rotalex.lutter.model.ids.ModifierType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Same contract as the renderers: misses are null, duplicates fail. */
class ModifierApplierRegistryTest {

    @Test
    fun `an unknown modifier resolves to nothing, not a throw`() {
        val registry = ModifierApplierRegistryBuilder().build()

        assertNull(registry[ModifierType("layout.ghost")])
    }

    @Test
    fun `a registered applier reads back out`() {
        val registry = ModifierApplierRegistryBuilder()
            .apply { register(ModifierType("layout.padding"), StubApplier) }
            .build()

        assertSame(StubApplier, registry[ModifierType("layout.padding")])
        assertTrue(ModifierType("layout.padding") in registry)
    }

    @Test
    fun `a second registration for one type fails naming it`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ModifierApplierRegistryBuilder().apply {
                register(ModifierType("layout.padding"), StubApplier)
                register(ModifierType("layout.padding"), StubApplier)
            }
        }

        assertTrue(failure.message?.contains("layout.padding") == true)
    }

    private object StubApplier : ModifierApplier {
        override fun apply(
            modifier: Modifier,
            args: ResolvedArgs,
            scopes: ScopeBag,
        ): Modifier = modifier
    }
}
