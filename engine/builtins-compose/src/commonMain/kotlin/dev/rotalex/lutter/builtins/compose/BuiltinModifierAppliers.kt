package dev.rotalex.lutter.builtins.compose

import dev.rotalex.lutter.builtins.AlignModifier
import dev.rotalex.lutter.builtins.AlphaModifier
import dev.rotalex.lutter.builtins.BackgroundModifier
import dev.rotalex.lutter.builtins.BorderModifier
import dev.rotalex.lutter.builtins.ClipModifier
import dev.rotalex.lutter.builtins.ClickableModifier
import dev.rotalex.lutter.builtins.FillMaxHeightModifier
import dev.rotalex.lutter.builtins.FillMaxSizeModifier
import dev.rotalex.lutter.builtins.FillMaxWidthModifier
import dev.rotalex.lutter.builtins.HeightModifier
import dev.rotalex.lutter.builtins.PaddingModifier
import dev.rotalex.lutter.builtins.SizeModifier
import dev.rotalex.lutter.builtins.WeightModifier
import dev.rotalex.lutter.builtins.WidthModifier
import dev.rotalex.lutter.runtime.ModifierApplierRegistryBuilder

/**
 * The runtime half of the modifier pair: one applier per spec in the same order.
 *
 * The schema half is `registerBuiltinModifiers` in `:engine:builtins`. Keying by
 * `spec.type` is the whole wiring — a modifier added to a family without an applier here is
 * what `RuntimeCoverage` refuses at runtime construction.
 */
public fun ModifierApplierRegistryBuilder.registerBuiltinModifierAppliers(): Unit {
    register(FillMaxSizeModifier.spec.type, FillMaxSizeApplier)
    register(FillMaxWidthModifier.spec.type, FillMaxWidthApplier)
    register(FillMaxHeightModifier.spec.type, FillMaxHeightApplier)
    register(PaddingModifier.spec.type, PaddingApplier)
    register(SizeModifier.spec.type, SizeApplier)
    register(WidthModifier.spec.type, WidthApplier)
    register(HeightModifier.spec.type, HeightApplier)
    register(WeightModifier.spec.type, WeightApplier)
    register(AlignModifier.spec.type, AlignApplier)
    register(BackgroundModifier.spec.type, BackgroundApplier)
    register(ClipModifier.spec.type, ClipApplier)
    register(BorderModifier.spec.type, BorderApplier)
    register(AlphaModifier.spec.type, AlphaApplier)
    register(ClickableModifier.spec.type, ClickableApplier)
}
