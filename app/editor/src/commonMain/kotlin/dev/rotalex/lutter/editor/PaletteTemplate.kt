package dev.rotalex.lutter.editor

import dev.rotalex.lutter.editing.template.NodeTemplate
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.PropertySpec
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
 * - every *required* property gets the smallest value its type admits.
 *
 * So `core.Column` gets a `Text` child, `m3.Button` gets a `Text` child in `content`, and
 * `m3.Text` — which has no slot and a required `text` — gets its `text`.
 *
 * ### Required properties are not optional
 *
 * Filling only the required `Str` ones was the bug behind "the Code tab is full of errors": a
 * `m3.Switch` needs `checked`, a `core.LazyColumn` needs `items`, a `m3.Dialog` needs `visible` —
 * and the insert left each of them empty, so the document the palette produced did not analyze.
 * The author saw a component that rendered nothing, and a Code tab reporting a missing property
 * for a node they had just added with one tap. The rule is therefore structural: a palette entry
 * either inserts a node the analyzer accepts, or it is not in the palette (`Palette.of` asks this
 * function's property half before listing anything).
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
    schema: SchemaView<ComponentSpec, *, *, *, *>,
    type: ComponentType,
    root: NodeId,
    fresh: () -> NodeId,
): NodeTemplate {
    val components: Registry<ComponentType, ComponentSpec> = schema.components
    val spec: ComponentSpec = components[type]
        ?: return NodeTemplate(listOf(Node(id = root, type = type)))

    val label: String = labelOf(spec)
    val child: Node? = labelChild(components, spec, label, fresh)
    val slot: SlotSpec? = if (child == null) {
        null
    } else {
        spec.slots.firstOrNull { admits(it, LABEL_TYPE) }
    }

    val node: Node = Node(
        id = root,
        type = spec.type,
        props = templateProps(schema, spec).orEmpty(),
        slots = if (slot != null && child != null) mapOf(slot.name to listOf(child.id)) else emptyMap(),
    )
    return NodeTemplate(if (child == null) listOf(node) else listOf(node, child))
}

/**
 * The properties a fresh instance of [spec] carries, or `null` when one of them cannot be filled.
 *
 * `null` is the palette's answer to "is this component placeable": no value this editor can invent
 * makes the node legal, so offering it would be offering a door into a wall. The refusal is per
 * property type, not per component name, which is what keeps a plugin's component out of the
 * palette for the right reason — its required `source` is a resource reference, and this editor
 * ships no resources to point at.
 *
 * A spec's own `default` wins over all of this: an author who wrote down what the value should be
 * outranks a rule about what an empty one could be.
 */
internal fun templateProps(
    schema: SchemaView<ComponentSpec, *, *, *, *>,
    spec: ComponentSpec,
): Map<PropertyKey, PropertyValue>? {
    if (spec.type == ICON_TYPE) return iconProps(schema, spec)

    val props = mutableMapOf<PropertyKey, PropertyValue>()
    for (property: PropertySpec<*> in spec.properties) {
        if (!property.required) continue
        val value: Value = property.default
            // A visible: false dialog is a node the author cannot see, select, or delete without
            // undo — which is not a component in a palette, it is a landmine.
            ?: if (property.key == VISIBLE) Value.Bool(true) else fill(property.type, labelOf(spec)) ?: return null
        props[property.key] = PropertyValue.Const(value)
    }
    return props
}

/** The smallest value [type] admits, or `null` when nothing smaller than the author's choice exists. */
private fun fill(type: TypeRef, label: String): Value? = when (type) {
    TypeRef.Str -> Value.Str(label)
    TypeRef.Bool -> Value.Bool(false)
    TypeRef.Int32 -> Value.Int32(0)
    TypeRef.Int64 -> Value.Int64(0)
    TypeRef.Float32 -> Value.Float32(0f)
    TypeRef.Float64 -> Value.Float64(0.0)
    TypeRef.Dp -> Value.Dp(0f)
    TypeRef.Sp -> Value.Sp(0f)
    is TypeRef.ListOf -> Value.ListOf(emptyList())
    is TypeRef.Nullable -> fill(type.inner, label)
    else -> null
}

/**
 * `m3.Icon`'s two required strings are a lookup, not a label.
 *
 * Writing the entry's name into both produced `Icon/Icon`, which analysis refuses because no set
 * has an icon by that name — so an icon was the one palette row that could never be placed. The
 * first registered set and its first name are the only pair this editor can pick without a picker.
 */
private fun iconProps(
    schema: SchemaView<ComponentSpec, *, *, *, *>,
    spec: ComponentSpec,
): Map<PropertyKey, PropertyValue>? {
    val set = schema.icons.all().firstOrNull() ?: return null
    val name = set.icons.keys.firstOrNull() ?: return null
    val props = mutableMapOf<PropertyKey, PropertyValue>()
    for (property: PropertySpec<*> in spec.properties) {
        if (!property.required) continue
        val value: Value = when (property.key) {
            ICON_SET -> Value.Str(set.name)
            ICON_NAME -> Value.Str(name)
            else -> property.default ?: fill(property.type, labelOf(spec)) ?: return null
        }
        props[property.key] = PropertyValue.Const(value)
    }
    return props
}

/** What the palette calls [spec], and the word its placeholder child reads. */
internal fun labelOf(spec: ComponentSpec): String =
    spec.metadata.displayName.ifBlank { spec.type.value }

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

/** The two required strings of the one component whose values are a registered lookup. */
private val ICON_TYPE: ComponentType = ComponentType("m3.Icon")
private val ICON_SET: PropertyKey = PropertyKey("set")
private val ICON_NAME: PropertyKey = PropertyKey("name")

/** A dialog, a sheet or a drawer that opens invisible cannot be found again. */
private val VISIBLE: PropertyKey = PropertyKey("visible")
