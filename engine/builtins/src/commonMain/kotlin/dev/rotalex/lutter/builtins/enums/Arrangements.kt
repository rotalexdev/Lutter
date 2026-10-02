package dev.rotalex.lutter.builtins.enums

import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.types.EnumEntrySpec
import dev.rotalex.lutter.schema.types.EnumTypeSpec

/**
 * The document's `VerticalArrangement` enum: where a `Column` puts its children on the main axis.
 *
 * Three names, one owner: `Arrangement` carries every entry either axis needs, so a `Column` and
 * a `Row` read one vocabulary instead of two that could disagree.
 */
public object VerticalArrangementSpec {
    /** The document enum id. First: [Type] reads it. */
    public val Id: TypeId = TypeId("VerticalArrangement")

    /** The type a property declares to draw its entries from. */
    public val Type: TypeRef = TypeRef.Enum(Id)

    /** Every entry, in reading order. */
    public val entries: List<EnumEntrySpec> = listOf("Top", "Center", "Bottom").map(::onArrangement)

    /** The schema-side declaration of the enum. */
    public val spec: EnumTypeSpec = EnumTypeSpec(Id, entries)
}

/**
 * The document's `HorizontalArrangement` enum: where a `Row` puts its children on the main axis.
 *
 * `Center` is the one name both axes share, and Compose types it for both, so the shared entry is
 * one entry here rather than a second name for the same arrangement.
 */
public object HorizontalArrangementSpec {
    /** The document enum id. First: [Type] reads it. */
    public val Id: TypeId = TypeId("HorizontalArrangement")

    /** The type a property declares to draw its entries from. */
    public val Type: TypeRef = TypeRef.Enum(Id)

    /** Every entry, in reading order. */
    public val entries: List<EnumEntrySpec> = listOf("Start", "Center", "End").map(::onArrangement)

    /** The schema-side declaration of the enum. */
    public val spec: EnumTypeSpec = EnumTypeSpec(Id, entries)
}

/**
 * One member-qualified symbol for an entry, the shape codegen splits into import plus text.
 *
 * Compose puts all of them on `Arrangement`, so there is nothing per entry to compute and a
 * shared builder is the honest shape rather than a repeated literal per line.
 */
private fun onArrangement(entry: String): EnumEntrySpec =
    EnumEntrySpec(entry, KotlinSymbol("androidx.compose.foundation.layout", "Arrangement." + entry))
