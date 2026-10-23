package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.schema.component.EmitCase
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.modifier.ModifierEmit
import dev.rotalex.lutter.schema.modifier.ModifierMetadata
import dev.rotalex.lutter.schema.modifier.ModifierSpec

/**
 * `interaction.clickable`: the press affordance — ripple, semantics and hit region.
 *
 * The handler is an empty lambda because actions arrive with events (Phase 7); what a
 * document can already ask for is the affordance, which is what makes a node tappable.
 *
 * The emission is a pattern rather than the bare function for one reason: Compose has two
 * `clickable` overloads, and the argument-less one is ill-formed — `interactionSource` and
 * `indication` have no defaults on the long overload, and the short one wants its handler as
 * a lambda, not as an absent argument. `ClickableApplier` writes `modifier.clickable { }`,
 * the trailing empty lambda being the inert handler both backends share, so the pattern here
 * is that call spelled the way the printer splices patterns: no case keys, so nothing fills
 * a placeholder and the snippet carries the one symbol the import lives by.
 */
public object ClickableModifier {
    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("interaction.clickable"),
        metadata = ModifierMetadata("Clickable", "Takes presses; the handler arrives with events"),
        params = emptyList(),
        emit = ModifierEmit(
            KotlinSymbol("androidx.compose.foundation", "clickable"),
            listOf(EmitCase(emptySet(), "clickable { }")),
        ),
    )
}

/** The interaction family, in registration order. */
public object InteractionModifiers {
    public val all: List<ModifierSpec> = listOf(ClickableModifier.spec)
}
