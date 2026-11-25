package dev.rotalex.lutter.editor

import dev.rotalex.lutter.editing.template.NodeTemplate
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.registry.Registry

/**
 * The rows a palette entry inserts: the component, plus the least it can carry and still be seen.
 *
 * ### Why this is not one bare node
 *
 * Inserting a node with nothing but its id and type is correct about *properties* — defaults are
 * `analysis`'s to apply and a required property is `analysis`'s to complain about — and it was
 * wrong about *structure*. A `m3.Button` is a pill that draws whatever is in its `content` slot;
 * with an empty slot it draws an empty pill, and a `m3.Text` with no `text` draws nothing at all.
 * On a phone the author tapped the palette seven times and got seven purple pills and no words,
 * which is indistinguishable from a broken app, and it was the app being literal.
 *
 * `NodeTemplate`'s own KDoc already says which way this goes: *"a palette entry ('Login form') is
 * a snapshot of rows, and instantiating it is an editing command"*. A palette entry is a template,
 * not a type, so the click inserts a subtree. What it must never do is invent a *value* the author
 * did not ask for; a label reading `Button` is the author's own word for the thing they just
 * dragged in, and it is the placeholder the inspector then edits.
 *
 * ### Where the shape comes from
 *
 * From the schema, never from a list of type names:
 *
 * - a component with slots gets one [label] child, in the first slot that admits it;
 * - a component with a required `Str` property gets that property set to the entry's name.
 *
 * So `core.Column` gets a `Text` child, `m3.Button` gets a `Text` child in `content`, and
 * `m3.Text` — which has no slot and a required `text` — gets its `text`. A component that wants
 * neither is inserted bare, exactly as before.
 *
 * The slot's cardinality is not consulted, deliberately: `Cardinality.Many` takes the placeholder
 * as readily as `ExactlyOne`, and `core.Column` — the container an author reaches for first —
 * declares `many`.
 *
 * ### The one literal
 *
 * [LABEL_TYPE] is spelled here instead of imported, because `:app:editor` has no `commonMain` edge
 * to the builtin schema (`ModuleGraphRules.ALLOWED` keeps the editor free of it). The editor is
 * handed a schema and speaks whatever it is given, so when the schema does not contain that type —
 * a test's synthetic vocabulary, a plugin's own — there is no placeholder to add and the insert
 * degrades to the bare node rather than guessing at some other type to put in a button.
 */
internal fun paletteSubtree(
    components: Registry<ComponentType, ComponentSpec>,
    type: ComponentType,
    root: NodeId,
    fresh: () -> NodeId,
): NodeTemplate {
    val spec: ComponentSpec = components[type]
        ?: return NodeTemplate(listOf(Node(id = root, type = type)))

    val label: String = spec.metadata.displayName
    val child: Node? = labelChild(components, spec, label, fresh)
    val slot: SlotSpec? = if (child == null) {
        null
    } else {
        spec.slots.firstOrNull { admits(it, LABEL_TYPE) }
    }

    val props: Map<PropertyKey, PropertyValue> = spec.properties
        .asSequence()
        .filter { it.required && it.type == TypeRef.Str }
        .associate { it.key to PropertyValue.Const(Value.Str(label)) }

    val node: Node = Node(
        id = root,
        type = spec.type,
        props = props,
        slots = if (slot != null && child != null) mapOf(slot.name to listOf(child.id)) else emptyMap(),
    )
    return NodeTemplate(if (child == null) listOf(node) else listOf(node, child))
}

/**
 * The placeholder child, or `null` when this type is the placeholder or there is none to make.
 *
 * A `m3.Text` inside a `m3.Text` would be a slot the type does not have, and a schema without the
 * label type is a schema this editor does not know the vocabulary of.
 */
private fun labelChild(
    components: Registry<ComponentType, ComponentSpec>,
    spec: ComponentSpec,
    label: String,
    fresh: () -> NodeId,
): Node? {
    if (spec.type == LABEL_TYPE || components[LABEL_TYPE] == null) return null
    if (spec.slots.none { admits(it, LABEL_TYPE) }) return null
    return Node(
        id = fresh(),
        type = LABEL_TYPE,
        props = mapOf(LABEL_KEY to PropertyValue.Const(Value.Str(label))),
    )
}

/** Whether [slot]'s filter admits [type]. The same three arms `ChildFilter` declares. */
private fun admits(slot: SlotSpec, type: ComponentType): Boolean = when (val filter: ChildFilter = slot.accepts) {
    ChildFilter.Any -> true
    is ChildFilter.Only -> type in filter.types
    is ChildFilter.Except -> type !in filter.types
}

/** The builtin vocabulary's text leaf, and the property that makes it visible. */
private val LABEL_TYPE: ComponentType = ComponentType("m3.Text")
private val LABEL_KEY: PropertyKey = PropertyKey("text")
