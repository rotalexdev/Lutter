package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.Positional
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * The `m3.Text` spec, PLAN §7.4's worked example transcribed.
 *
 * Handles stay on the object so the spec and its renderer read the same keys.
 */
public object TextSpec {
    /** The literal. Required, so a Text always says something. */
    public val text: PropertySpec<String> = prop("text", TypeRef.Str, required = true)

    /** A literal colour override; tokens stay on [style]. */
    public val color: PropertySpec<ColorArgb?> = prop("color", TypeRef.Nullable(TypeRef.Color))

    /** A typography token from the selected theme. */
    public val style: PropertySpec<TokenName?> =
        prop("style", TypeRef.Nullable(TypeRef.Token(TokenKind.Typography)))

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.Text"), version = 1) {
        metadata(displayName = "Text", category = Category.Basic)
        property(text)
        property(color)
        property(style)
        composeCall(KotlinSymbol("androidx.compose.material3", "Text")) {
            param("text", from = text, positional = Positional.WhenSole)
            param("color", from = color)
            param("style", from = style)
        }
    }
}
