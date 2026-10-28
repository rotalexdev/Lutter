package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.modifier.ModifierEmit
import dev.rotalex.lutter.schema.modifier.ModifierMetadata
import dev.rotalex.lutter.schema.modifier.ModifierSpec

/** `decoration.background`: a colour painted behind the content, square by default. */
public object BackgroundModifier {
    public val color: PropertySpec<ColorArgb> = prop("color", TypeRef.Color, required = true)

    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("decoration.background"),
        metadata = ModifierMetadata("Background", "A colour painted behind the content"),
        params = listOf(color),
        emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation", "background")),
    )
}

/** `decoration.clip`: the shape the content is cut to. */
public object ClipModifier {
    /** One of the entries of `Shape`. */
    public val shape: PropertySpec<String> = prop("shape", ShapeSpec.Type, required = true)

    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("decoration.clip"),
        metadata = ModifierMetadata("Clip", "Cuts the content to a shape"),
        params = listOf(shape),
        emit = ModifierEmit(KotlinSymbol("androidx.compose.ui.draw", "clip")),
    )
}

/** `decoration.border`: a stroke drawn on the edge, square like a background without a shape. */
public object BorderModifier {
    public val width: PropertySpec<Float> = prop("width", TypeRef.Dp, required = true)

    public val color: PropertySpec<ColorArgb> = prop("color", TypeRef.Color, required = true)

    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("decoration.border"),
        metadata = ModifierMetadata("Border", "A stroke drawn on the edge"),
        params = listOf(width, color),
        emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation", "border")),
    )
}

/** `decoration.alpha`: the whole subtree drawn at a fraction of its opacity. */
public object AlphaModifier {
    public val alpha: PropertySpec<Float> = prop("alpha", TypeRef.Float32, required = true)

    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("decoration.alpha"),
        metadata = ModifierMetadata("Alpha", "Draws the subtree at a fraction of its opacity"),
        params = listOf(alpha),
        emit = ModifierEmit(KotlinSymbol("androidx.compose.ui.draw", "alpha")),
    )
}

/** The decoration family, in registration order. */
public object DecorationModifiers {
    public val all: List<ModifierSpec> = listOf(
        BackgroundModifier.spec,
        ClipModifier.spec,
        BorderModifier.spec,
        AlphaModifier.spec,
    )
}
