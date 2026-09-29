package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.ColorArgbSerializer
import kotlinx.serialization.Serializable

/**
 * One colour role's light and dark values, §14.1.
 *
 * A pair rather than a nullable, because "this role has no dark value" and "this role is not
 * in the theme" are different facts, and only one of them is expressible as an absent entry in
 * `ThemeDecl.colors`.
 *
 * The two serializer annotations sit on the properties rather than on `ColorArgb` itself,
 * which is this project's decision and not a preference: `@Serializable(with = …)` on the
 * value class compiles and then throws on Wasm, so the wire form is declared where a use site
 * cannot forget it. `Value.Color` already carries the same two annotations, and `#AARRGGBB` is
 * the form §9.2's Serialization column specifies.
 *
 * `ThemeDecl` and the other two specs §14.1 declares are not here: `ThemeBase`, `ColorRole`,
 * `TextRole`, `ShapeRole`, `TokenName`, `TextStyleSpec` and `ShapeSpec` are named in §14.1
 * and declared nowhere in the plan.
 */
@Serializable
public data class ColorSpec(

    /** The light appearance, written `#AARRGGBB`. */
    public @Serializable(ColorArgbSerializer::class) val light: ColorArgb,

    /** The dark appearance. Omitted from the wire form when there is not one. */
    public @Serializable(ColorArgbSerializer::class) val dark: ColorArgb? = null,
)
