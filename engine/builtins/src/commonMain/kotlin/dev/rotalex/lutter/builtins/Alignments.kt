package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.types.EnumEntrySpec
import dev.rotalex.lutter.schema.types.EnumTypeSpec

/**
 * The document's `Alignment` enum: the nine alignments Compose names, as one vocabulary.
 *
 * An entry's symbol is member-qualified (`Alignment.Center`): the qualifier is what codegen
 * splits off to write the import, and the bias forms are values, which is what a closed
 * runtime map needs.
 */
public object AlignmentSpec {
    /** The document enum id. First: [Type] reads it. */
    public val Id: TypeId = TypeId("Alignment")

    /** The type a property declares to draw its entries from. */
    public val Type: TypeRef = TypeRef.Enum(Id)

    /** Every entry, in reading order. */
    public val entries: List<EnumEntrySpec> = listOf(
        "TopLeft", "TopCenter", "TopRight",
        "CenterLeft", "Center", "CenterRight",
        "BottomLeft", "BottomCenter", "BottomRight",
    ).map { EnumEntrySpec(it, KotlinSymbol("androidx.compose.ui", "Alignment." + it)) }

    /** The schema-side declaration of the enum. */
    public val spec: EnumTypeSpec = EnumTypeSpec(Id, entries)
}
