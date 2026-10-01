package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.types.EnumEntrySpec
import dev.rotalex.lutter.schema.types.EnumTypeSpec

/**
 * The document's `Shape` enum: the two shapes a clip can name without a token.
 *
 * A corner radius is a number rather than an entry, so the entries are the shapes that are
 * a constant: a rectangle and a circle. Material's shape scale is theme data, and a theme
 * token is not something an enum entry can carry.
 */
public object ShapeSpec {
    /** The document enum id. First: [Type] reads it. */
    public val Id: TypeId = TypeId("Shape")

    /** The type a property declares to draw its entries from. */
    public val Type: TypeRef = TypeRef.Enum(Id)

    /** Every entry, with the shape function each one names. */
    public val entries: List<EnumEntrySpec> = listOf(
        EnumEntrySpec("Rectangle", KotlinSymbol("androidx.compose.foundation.shape", "RectangleShape")),
        EnumEntrySpec("Circle", KotlinSymbol("androidx.compose.foundation.shape", "CircleShape")),
    )

    /** The schema-side declaration of the enum. */
    public val spec: EnumTypeSpec = EnumTypeSpec(Id, entries)
}
