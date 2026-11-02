package dev.rotalex.lutter.builtins.enums

import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.types.EnumEntrySpec
import dev.rotalex.lutter.schema.types.EnumTypeSpec

/**
 * The document's `ContentScale` enum: how an image fills the box its layout gave it.
 *
 * The seven names are Compose's own `ContentScale` object's, one symbol per entry —
 * there is no shared prefix to trim, so the member-qualified spelling is per entry.
 */
public object ContentScaleSpec {
    /** The document enum id. First: [Type] reads it. */
    public val Id: TypeId = TypeId("ContentScale")

    /** The type a property declares to draw its entries from. */
    public val Type: TypeRef = TypeRef.Enum(Id)

    /** Every entry, in reading order. */
    public val entries: List<EnumEntrySpec> =
        listOf("Crop", "FillBounds", "FillWidth", "FillHeight", "Fit", "FillMaxDimensions", "Inside")
            .map(::onContentScale)

    /** The schema-side declaration of the enum. */
    public val spec: EnumTypeSpec = EnumTypeSpec(Id, entries)
}

/** One member-qualified symbol for an entry, the shape codegen splits into import plus text. */
private fun onContentScale(entry: String): EnumEntrySpec =
    EnumEntrySpec(entry, KotlinSymbol("androidx.compose.ui.layout", "ContentScale." + entry))
