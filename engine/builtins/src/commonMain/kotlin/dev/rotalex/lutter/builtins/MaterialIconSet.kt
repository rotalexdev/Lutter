package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EmitCase
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.ValueEmit
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.types.IconSet

/**
 * The builtin Material icon set, §20:1599's "Material Icons *core* only in MVP".
 *
 * Keys are the names a document writes; values are the filled-family symbols the generator
 * emits. A small common core rather than the whole artifact: every entry here must exist in
 * BOTH this map and the runtime resolver's map, and the coverage test holding them together
 * is what keeps a name from rendering empty on one side.
 */
public object MaterialIconSet {
    /** The set name documents write in `m3.Icon`'s `set` property. */
    public const val NAME: String = "material"

    /** The core icons: name → filled-family symbol. */
    public val set: IconSet = IconSet(
        NAME,
        mapOf(
            "home" to KotlinSymbol("androidx.compose.material.icons.filled", "Home"),
            "search" to KotlinSymbol("androidx.compose.material.icons.filled", "Search"),
            "settings" to KotlinSymbol("androidx.compose.material.icons.filled", "Settings"),
            "close" to KotlinSymbol("androidx.compose.material.icons.filled", "Close"),
            "add" to KotlinSymbol("androidx.compose.material.icons.filled", "Add"),
            "check" to KotlinSymbol("androidx.compose.material.icons.filled", "Check"),
            "delete" to KotlinSymbol("androidx.compose.material.icons.filled", "Delete"),
            "menu" to KotlinSymbol("androidx.compose.material.icons.filled", "Menu"),
            "arrow_back" to KotlinSymbol("androidx.compose.material.icons.filled", "ArrowBack"),
            "favorite" to KotlinSymbol("androidx.compose.material.icons.filled", "Favorite"),
            "star" to KotlinSymbol("androidx.compose.material.icons.filled", "Star"),
            "share" to KotlinSymbol("androidx.compose.material.icons.filled", "Share"),
        ),
    )
}

/**
 * The `m3.Icon` spec, §10:796's row: the `icon{set,name}` shape as two required strings,
 * validated jointly against the registered set (§10:796's validation column) and emitted
 * through it. `contentDescription` has no Compose default, so the spec spells it as the
 * decorative literal `null` — an accessibility label arrives as a version bump with the
 * §9.2 editor row.
 */
public object IconSpec {
    /** Which registered set the icon resolves through. MVP ships exactly one. */
    public val set: PropertySpec<String> = prop("set", TypeRef.Str, required = true)

    /** The icon's name inside that set, e.g. `home`. */
    public val name: PropertySpec<String> = prop("name", TypeRef.Str, required = true)

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.Icon"), version = 1) {
        metadata(displayName = "Icon", category = Category.Basic)
        property(set)
        property(name)
        composeCall(KotlinSymbol("androidx.compose.material3", "Icon")) {
            param(
                "imageVector",
                from = listOf(set.key, name.key),
                emit = ValueEmit.Icon(set.key, name.key),
            )
            param(
                "contentDescription",
                from = listOf(),
                emit = ValueEmit.Literal("null"),
            )
        }
    }
}
