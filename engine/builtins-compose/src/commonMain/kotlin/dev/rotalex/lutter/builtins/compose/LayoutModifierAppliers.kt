package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.builtins.AlignModifier
import dev.rotalex.lutter.builtins.FillMaxHeightModifier
import dev.rotalex.lutter.builtins.FillMaxSizeModifier
import dev.rotalex.lutter.builtins.FillMaxWidthModifier
import dev.rotalex.lutter.builtins.HeightModifier
import dev.rotalex.lutter.builtins.PaddingModifier
import dev.rotalex.lutter.builtins.SizeModifier
import dev.rotalex.lutter.builtins.WeightModifier
import dev.rotalex.lutter.builtins.WidthModifier
import dev.rotalex.lutter.runtime.ModifierApplier
import dev.rotalex.lutter.runtime.ResolvedArgs
import dev.rotalex.lutter.runtime.ScopeBag
import dev.rotalex.lutter.runtime.ScopeHandle
import dev.rotalex.lutter.runtime.reader

/**
 * The layout appliers, one per spec.
 *
 * An argument the document did not write leaves the modifier untouched rather than applying
 * a zero: a modifier with nothing to say should not sit in the chain at all.
 */
internal object FillMaxSizeApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier =
        modifier.fillMaxSize()
}

internal object FillMaxWidthApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier =
        modifier.fillMaxWidth()
}

internal object FillMaxHeightApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier =
        modifier.fillMaxHeight()
}

internal object PaddingApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier {
        val props = args.reader()
        val all = props[PaddingModifier.all]
        if (all != null) return modifier.padding(all.dp)
        val horizontal = props[PaddingModifier.horizontal]
        val vertical = props[PaddingModifier.vertical]
        if (horizontal == null && vertical == null) return modifier
        return modifier.padding(horizontal = (horizontal ?: 0f).dp, vertical = (vertical ?: 0f).dp)
    }
}

internal object SizeApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier {
        val props = args.reader()
        val width = props[SizeModifier.width] ?: return modifier
        val height = props[SizeModifier.height] ?: return modifier
        return modifier.size(width.dp, height.dp)
    }
}

internal object WidthApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier {
        val width = args.reader()[WidthModifier.value] ?: return modifier
        return modifier.width(width.dp)
    }
}

internal object HeightApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier {
        val height = args.reader()[HeightModifier.value] ?: return modifier
        return modifier.height(height.dp)
    }
}

/**
 * Applies against the row receiver the enclosing `Row` opened; elsewhere it does nothing.
 *
 * A scope the analyzer proved but no renderer opened is the one case that returns untouched:
 * the gate already reported it, and applying against a receiver nobody has would crash.
 */
internal object WeightApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier {
        val row = scopes.handle as? ScopeHandle.Row ?: return modifier
        val share = args.reader()[WeightModifier.weight] ?: return modifier
        return with(row.scope) { modifier.weight(share) }
    }
}

/** The entry projects onto whichever axis the open scope aligns. */
internal object AlignApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier {
        val entry = args.reader()[AlignModifier.alignment] ?: return modifier
        val handle = scopes.handle
        // A row's children move vertically and a column's horizontally, so the scope decides
        // which projection of the entry is the right one rather than the entry deciding.
        return when (handle) {
            is ScopeHandle.Row -> with(handle.scope) { modifier.align(AlignmentMaps.vertical(entry)) }
            is ScopeHandle.Column -> with(handle.scope) { modifier.align(AlignmentMaps.horizontal(entry)) }
            is ScopeHandle.Box -> with(handle.scope) { modifier.align(AlignmentMaps.box(entry)) }
            null -> modifier
        }
    }
}
