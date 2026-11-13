package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * The `m3.Surface` spec: a Material container that draws its elevation, its color and its
 * content.
 *
 * v1 keeps the skeleton Compose signature carries — the container color and the two
 * elevations, plus the single content slot — and leaves `shape` and `border` for later:
 * documents express the corner today through `layout.clip` and `layout.border` over the same
 * node, so a second path on the spec would be one placement answered twice. The three
 * properties are optional: Compose answers each absence with its own surface default, and the
 * renderers read the same way.
 */
public object SurfaceSpec {
    /** The container color; absent reads Material's surface color. */
    public val color: PropertySpec<ColorArgb> = prop("color", TypeRef.Color)

    /** The tonal elevation, in dp; absent reads Material's default. */
    public val tonalElevation: PropertySpec<Float> = prop("tonalElevation", TypeRef.Dp)

    /** The shadow elevation, in dp; absent reads Material's default. */
    public val shadowElevation: PropertySpec<Float> = prop("shadowElevation", TypeRef.Dp)

    /** The content, one child deep: a surface is a container, not a layout. */
    public val content: SlotSpec = SlotSpec(
        SlotName("content"),
        Cardinality.ExactlyOne,
        provides = setOf(LayoutScopes.Column),
    )

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.Surface"), version = 1) {
        metadata(displayName = "Surface", category = Category.Layout)
        property(color)
        property(tonalElevation)
        property(shadowElevation)
        slot(content)
        composeCall(KotlinSymbol("androidx.compose.material3", "Surface")) {
            param("color", from = color)
            param("tonalElevation", from = tonalElevation)
            param("shadowElevation", from = shadowElevation)
            slot("content", LambdaTarget.Trailing)
        }
    }
}
