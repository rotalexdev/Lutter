package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec

/**
 * One component a palette can offer, as the component's own metadata describes it (§25.4's
 * "Component palette → `schema.components.all()` metadata").
 *
 * A copy of the facts a palette shows rather than a `ComponentSpec`: a palette entry is read by
 * a UI that must not be able to reach the spec's rules, slots or codegen binding, and an entry
 * that held the spec would make the palette a second place that knows what a component is.
 * The inspector is the one that reads the spec, because it edits its properties.
 */
public class PaletteEntry(
    /** What an insertion names. */
    public val type: ComponentType,

    /** Label the palette shows. Falls back to the type's own spelling when the spec is silent. */
    public val displayName: String,

    /** Which group of the palette lists it. */
    public val category: Category,

    /** Tooltip text, empty when the spec says nothing. */
    public val description: String,

    /** What a search box matches against. */
    public val keywords: List<String>,

    /** Icon name the host resolves, or null when the component declares none. */
    public val icon: String?,
)

/** One palette group: a category and the entries that declare it. */
public class PaletteSection(

    /** The group. */
    public val category: Category,

    /** Its entries, in the order the schema registered them. */
    public val entries: List<PaletteEntry>,
)

/**
 * The component palette, derived from a schema and nothing else.
 *
 * ### Why the order is registration order, and not alphabetical
 *
 * A palette's order is a design decision — the built-in pack registers `Text` before `Column`
 * because a leaf is the first thing an author drags — and sorting by name would discard that
 * decision in exchange for a property nobody asked for. Registration order is also already
 * deterministic: `RegistryBuilder` preserves it, and plugins are installed in the order the
 * host named them (phase 16), so two runs over one schema list one palette.
 *
 * Sections follow [Category]'s declaration order for the same reason: the groups are the
 * engine's vocabulary, and the order it declares them in is the order it means.
 */
public object Palette {

    /**
     * Every component [schema] registers, grouped by category.
     *
     * An empty category is omitted rather than rendered as an empty heading, which is what a
     * `when` over the enum would produce.
     */
    public fun of(schema: SchemaView<ComponentSpec, *, *, *, *>): List<PaletteSection> {
        val entries: List<PaletteEntry> = schema.components.all().map(::entry)
        return Category.entries.mapNotNull { category ->
            val section = entries.filter { it.category == category }
            if (section.isEmpty()) null else PaletteSection(category, section)
        }
    }

    private fun entry(spec: ComponentSpec): PaletteEntry = PaletteEntry(
        type = spec.type,
        displayName = spec.metadata.displayName.ifBlank { spec.type.value },
        category = spec.metadata.category,
        description = spec.metadata.description,
        keywords = spec.metadata.keywords,
        icon = spec.metadata.icon,
    )
}
