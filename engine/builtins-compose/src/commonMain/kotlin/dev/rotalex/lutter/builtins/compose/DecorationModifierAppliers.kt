package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.builtins.AlphaModifier
import dev.rotalex.lutter.builtins.BackgroundModifier
import dev.rotalex.lutter.builtins.BorderModifier
import dev.rotalex.lutter.builtins.ClipModifier
import dev.rotalex.lutter.runtime.ModifierApplier
import dev.rotalex.lutter.runtime.ResolvedArgs
import dev.rotalex.lutter.runtime.ScopeBag
import dev.rotalex.lutter.runtime.reader
import dev.rotalex.lutter.runtime.toCompose

/** The decoration appliers. A colour is reinterpreted, never recomputed: see `ColorArgb`. */
internal object BackgroundApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier {
        val color = args.reader()[BackgroundModifier.color] ?: return modifier
        return modifier.background(color.toCompose())
    }
}

internal object ClipApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier {
        val entry = args.reader()[ClipModifier.shape] ?: return modifier
        return modifier.clip(ShapeMaps.of(entry))
    }
}

internal object BorderApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier {
        val props = args.reader()
        val width = props[BorderModifier.width] ?: return modifier
        val color = props[BorderModifier.color] ?: return modifier
        return modifier.border(width.dp, color.toCompose())
    }
}

internal object AlphaApplier : ModifierApplier {
    override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier {
        val alpha = args.reader()[AlphaModifier.alpha] ?: return modifier
        return modifier.alpha(alpha)
    }
}
