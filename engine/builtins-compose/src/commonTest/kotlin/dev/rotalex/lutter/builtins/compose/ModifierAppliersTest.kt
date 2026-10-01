package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.layout.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.ui.Modifier
import dev.rotalex.lutter.analysis.resolved.PropOrigin
import dev.rotalex.lutter.analysis.resolved.ResolvedProp
import dev.rotalex.lutter.builtins.AlignModifier
import dev.rotalex.lutter.builtins.AlphaModifier
import dev.rotalex.lutter.builtins.BackgroundModifier
import dev.rotalex.lutter.builtins.BorderModifier
import dev.rotalex.lutter.builtins.ClipModifier
import dev.rotalex.lutter.builtins.DecorationModifiers
import dev.rotalex.lutter.builtins.FillMaxHeightModifier
import dev.rotalex.lutter.builtins.FillMaxSizeModifier
import dev.rotalex.lutter.builtins.FillMaxWidthModifier
import dev.rotalex.lutter.builtins.HeightModifier
import dev.rotalex.lutter.builtins.InteractionModifiers
import dev.rotalex.lutter.builtins.LayoutModifiers
import dev.rotalex.lutter.builtins.LayoutScopes
import dev.rotalex.lutter.builtins.PaddingModifier
import dev.rotalex.lutter.builtins.SizeModifier
import dev.rotalex.lutter.builtins.WeightModifier
import dev.rotalex.lutter.builtins.WidthModifier
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.runtime.ModifierApplier
import dev.rotalex.lutter.runtime.ModifierApplierRegistryBuilder
import dev.rotalex.lutter.runtime.ResolvedArgs
import dev.rotalex.lutter.runtime.ScopeBag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The applier pair: every spec is wired, and an applier folds or leaves the chain alone.
 *
 * A `Modifier` chain is opaque — there is no public API that reads one back — so what a
 * common test can prove is identity: an applier that applied something returns a new chain
 * and one that had nothing to apply returns the same instance. What a folded chain does to
 * a laid-out tree is W4's conformance work, which has a composition to measure.
 */
class ModifierAppliersTest {

    private val registry = ModifierApplierRegistryBuilder()
        .apply { registerBuiltinModifierAppliers() }
        .build()

    @Test
    fun `every builtin modifier resolves to an applier`() {
        for (spec in LayoutModifiers.all + DecorationModifiers.all + InteractionModifiers.all) {
            assertNotNull(registry[spec.type], "${spec.type}")
        }
    }

    @Test
    fun `outside the pair nothing resolves`() {
        assertNull(registry[ModifierType("layout.ghost")])
    }

    @Test
    fun `a modifier missing its required arguments leaves the chain alone`() {
        val bare = ScopeBag(emptySet(), null)

        assertSame(Modifier, PaddingApplier.apply(Modifier, emptyMap(), bare))
        assertSame(
            Modifier,
            SizeApplier.apply(Modifier, argsOf(SizeModifier.width.key to Value.Dp(10f)), bare),
            "size without a height",
        )
        assertSame(
            Modifier,
            BorderApplier.apply(Modifier, argsOf(BorderModifier.width.key to Value.Dp(1f)), bare),
            "border without a colour",
        )
        assertSame(
            Modifier,
            ClipApplier.apply(Modifier, emptyMap(), bare),
        )
        assertSame(
            Modifier,
            AlphaApplier.apply(Modifier, emptyMap(), bare),
        )
    }

    @Test
    fun `a scoped modifier without an open scope leaves the chain alone`() {
        val proved = ScopeBag(setOf(LayoutScopes.Row), null)

        assertSame(
            Modifier,
            WeightApplier.apply(Modifier, argsOf(WeightModifier.weight.key to Value.Float32(1f)), proved),
            "the analyzer proved the scope, but no renderer opened a receiver",
        )
        assertSame(
            Modifier,
            AlignApplier.apply(Modifier, argsOf(AlignModifier.alignment.key to Value.Enum("Center")), proved),
        )
    }

    @Test
    fun `an alignment entry projects onto the axis its own name states`() {
        // A row moves its children vertically, a column horizontally, so `TopLeft` is Top in
        // a row and Left in a column. Reading one form for the other is the bug this pins.
        assertEquals(Alignment.Vertical.Top, AlignmentMaps.vertical("TopLeft"))
        assertEquals(Alignment.Vertical.Bottom, AlignmentMaps.vertical("BottomRight"))
        assertEquals(Alignment.Horizontal.Left, AlignmentMaps.horizontal("TopLeft"))
        assertEquals(Alignment.Horizontal.Right, AlignmentMaps.horizontal("BottomRight"))
        assertEquals(Alignment.CenterRight, AlignmentMaps.box("CenterRight"))
    }

    @Test
    fun `an entry no map knows falls back to the centre of its axis`() {
        assertEquals(Alignment.Center, AlignmentMaps.box("Sideways"))
        assertEquals(Alignment.Horizontal.Center, AlignmentMaps.horizontal("Sideways"))
        assertEquals(Alignment.Vertical.Center, AlignmentMaps.vertical("Sideways"))
    }

    @Test
    fun `a shape entry names the shape, and an unknown one is the rectangle`() {
        assertEquals(CircleShape, ShapeMaps.of("Circle"))
        assertEquals(RectangleShape, ShapeMaps.of("Rectangle"))
        assertEquals(RectangleShape, ShapeMaps.of("Trapezoid"))
    }

    @Test
    fun `an applier with its arguments folds something onto the chain`() {
        val bare = ScopeBag(emptySet(), null)
        val folds = listOf<Pair<ModifierApplier, ResolvedArgs>>(
            FillMaxSizeApplier to emptyMap(),
            FillMaxWidthApplier to emptyMap(),
            FillMaxHeightApplier to emptyMap(),
            PaddingApplier to argsOf(PaddingModifier.all.key to Value.Dp(8f)),
            SizeApplier to argsOf(
                SizeModifier.width.key to Value.Dp(10f),
                SizeModifier.height.key to Value.Dp(20f),
            ),
            WidthApplier to argsOf(WidthModifier.value.key to Value.Dp(10f)),
            HeightApplier to argsOf(HeightModifier.value.key to Value.Dp(10f)),
            BackgroundApplier to argsOf(BackgroundModifier.color.key to Value.Color(RED)),
            ClipApplier to argsOf(ClipModifier.shape.key to Value.Enum("Circle")),
            BorderApplier to argsOf(
                BorderModifier.width.key to Value.Dp(1f),
                BorderModifier.color.key to Value.Color(RED),
            ),
            AlphaApplier to argsOf(AlphaModifier.alpha.key to Value.Float32(0.5f)),
            ClickableApplier to emptyMap(),
        )

        for ((applier, args) in folds) {
            assertNotSame(Modifier, applier.apply(Modifier, args, bare), "$applier folded nothing")
        }
    }

    @Test
    fun `padding reads every shape of itself`() {
        val bare = ScopeBag(emptySet(), null)
        val horizontal = argsOf(PaddingModifier.horizontal.key to Value.Dp(8f))
        val vertical = argsOf(PaddingModifier.vertical.key to Value.Dp(8f))
        val both = argsOf(
            PaddingModifier.horizontal.key to Value.Dp(8f),
            PaddingModifier.vertical.key to Value.Dp(4f),
        )

        assertNotSame(Modifier, PaddingApplier.apply(Modifier, horizontal, bare))
        assertNotSame(Modifier, PaddingApplier.apply(Modifier, vertical, bare))
        assertNotSame(Modifier, PaddingApplier.apply(Modifier, both, bare))
    }

    private fun argsOf(vararg entries: Pair<PropertyKey, Value>): ResolvedArgs =
        entries.associate { (key, value) ->
            key to ResolvedProp(key, PropertyValue.Const(value), PropOrigin.Specified, null)
        }

    private companion object {
        val RED: ColorArgb = ColorArgb.parse("#FFFF0000")
    }
}
