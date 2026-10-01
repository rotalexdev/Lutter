package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import dev.rotalex.lutter.runtime.ModifierApplier
import dev.rotalex.lutter.runtime.ResolvedArgs
import dev.rotalex.lutter.runtime.ScopeBag

/**
 * The press affordance: ripple, semantics and hit region.
 *
 * The empty lambda is the whole handler question — actions arrive with events, and a node
 * that takes presses is already tappable before it does anything.
 */
internal object ClickableApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier =
        modifier.clickable { }
}
