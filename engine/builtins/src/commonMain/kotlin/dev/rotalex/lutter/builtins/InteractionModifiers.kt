package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.modifier.ModifierEmit
import dev.rotalex.lutter.schema.modifier.ModifierMetadata
import dev.rotalex.lutter.schema.modifier.ModifierSpec

/**
 * `interaction.clickable`: the press affordance — ripple, semantics and hit region.
 *
 * The handler is an empty lambda because actions arrive with events (Phase 7); what a
 * document can already ask for is the affordance, which is what makes a node tappable.
 */
public object ClickableModifier {
    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("interaction.clickable"),
        metadata = ModifierMetadata("Clickable", "Takes presses; the handler arrives with events"),
        params = emptyList(),
        emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation", "clickable")),
    )
}

/** The interaction family, in registration order. */
public object InteractionModifiers {
    public val all: List<ModifierSpec> = listOf(ClickableModifier.spec)
}
