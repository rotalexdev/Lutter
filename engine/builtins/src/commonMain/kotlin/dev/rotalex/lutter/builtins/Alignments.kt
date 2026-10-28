package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.types.EnumEntrySpec
import dev.rotalex.lutter.schema.types.EnumTypeSpec

/**
 * The document's `Alignment` enum: the nine alignments Compose names, as one vocabulary.
 *
 * An entry's symbol is member-qualified (`AbsoluteAlignment.TopLeft`): the qualifier is what
 * codegen splits off to write the import, and the bias forms are values, which is what a closed
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
    ).map { EnumEntrySpec(it, symbolOf(it)) }

    /**
     * Compose splits its own vocabulary in two, so the owner follows the name: an entry naming
     * a side is absolute, one naming a band is bias-aligned.
     */
    private fun symbolOf(entry: String): KotlinSymbol {
        val owner = if (entry.endsWith("Left") || entry.endsWith("Right")) {
            "AbsoluteAlignment"
        } else {
            "Alignment"
        }
        return KotlinSymbol("androidx.compose.ui", "$owner.$entry")
    }

    /** The schema-side declaration of the enum. */
    public val spec: EnumTypeSpec = EnumTypeSpec(Id, entries)
}
