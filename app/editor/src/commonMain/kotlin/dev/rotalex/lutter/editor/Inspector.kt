package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EditorHints
import dev.rotalex.lutter.schema.component.PropertySpec

/**
 * One editable row of the inspector: a declared property, and what the document currently says
 * about it.
 *
 * The row carries both halves because they are the two things a field draws — the spec says
 * how to edit, the value says what is there — and a UI that fetched them separately would have
 * to keep them in step across every revision.
 */
public class PropertyRow(

    /** The property's key: what a commit names. */
    public val key: PropertyKey,

    /** The declared type, which decides the control when the hint does not. */
    public val type: TypeRef,

    /** Whether the document must carry it. A required row cannot be cleared. */
    public val required: Boolean,

    /** The spec's own help text, empty when it says nothing. */
    public val doc: String,

    /**
     * The field's initial text: the current value in canonical form, or the spec's default when
     * the document does not carry the property, or empty when there is neither.
     *
     * Empty is a real state and not a missing one: it is what an unset, undefaulted property
     * looks like, and [PropertyText.apply] refuses to turn it into a value.
     */
    public val text: String,

    /** Whether the current value is an expression rather than a literal (drives the hint chip). */
    public val isExpression: Boolean,

    /** The spec's editor affordance. One variant today; §9.2's column grows with the editor. */
    public val editor: EditorHints,
)

/** The inspector's answer for one node: its identity and its property rows. */
public class InspectorModel(

    /** The node these rows describe. */
    public val node: NodeId,

    /** Its component type. */
    public val type: ComponentType,

    /** The label a header shows. Falls back to the type's spelling when the spec is silent. */
    public val displayName: String,

    /** The spec's description, empty when it says nothing. */
    public val description: String,

    /** The declared properties, in the spec's declaration order. */
    public val properties: List<PropertyRow>,
)

/**
 * The property panel's model, from a document, a schema and a node id (§25.4's "Property panel →
 * `ComponentSpec.properties`, `PropertySpec.editor` hints, `TypeRegistry`").
 *
 * ### Why a node the schema does not know is null, not an error
 *
 * A document can outlive the schema that authored it: a remove plugin leaves nodes whose spec
 * is gone, and analysis reports that as a diagnostic rather than refusing to open the file. An
 * inspector that threw would make such a document unopenable in the editor; one that showed an
 * empty panel would claim the component has no properties. Null says "nothing to edit here",
 * which is the truth, and the canvas is where the diagnostic is already visible.
 */
public object Inspector {

    /** The rows for [node], or null when the document or the schema cannot describe it. */
    public fun of(
        document: UiDocument,
        schema: SchemaView<ComponentSpec, *, *, *, *>,
        node: NodeId,
    ): InspectorModel? {
        val row = document.nodes[node] ?: return null
        val spec = schema.components[row.type] ?: return null
        return InspectorModel(
            node = node,
            type = spec.type,
            displayName = spec.metadata.displayName.ifBlank { spec.type.value },
            description = spec.metadata.description,
            properties = spec.properties.map { property -> propertyRow(property, row.props[property.key]) },
        )
    }

    private fun propertyRow(
        spec: PropertySpec<*>,
        current: PropertyValue?,
    ): PropertyRow = PropertyRow(
        key = spec.key,
        type = spec.type,
        required = spec.required,
        doc = spec.doc,
        text = when (current) {
            null -> spec.default?.let { PropertyText.of(PropertyValue.Const(it)) }.orEmpty()
            else -> PropertyText.of(current)
        },
        isExpression = current is PropertyValue.Computed,
        editor = spec.editor,
    )
}
