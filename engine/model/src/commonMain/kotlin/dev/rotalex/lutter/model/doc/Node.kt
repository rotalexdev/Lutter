// No `@file:OptIn` on this file, and the absence is deliberate rather than an oversight.
//
// The four files in this module that declare a persisted union — `Value.kt`,
// `PropertyValue.kt`, `Expr.kt` and `TypeRef.kt` — each open with a file-scoped opt-in for
// exactly one experimental annotation: `@JsonClassDiscriminator`. Nothing here is a union, so
// nothing here needs it, and an opt-in a file does not use is a suppression bought for
// nothing. The longer argument — why a plain record must carry no discriminator at all, and
// why its absence here is a decision rather than a gap — is in the `Node` KDoc, next to the
// annotation it is about.

package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.expr.PropertyValue
import kotlinx.serialization.Serializable

/**
 * One instance of a component in the UI tree: the record every other part of the engine
 * reads.
 *
 * PLAN §5.3 declares seven fields. This class declares seven, in the plan's order, with the
 * plan's defaults, and adds nothing. What is worth the space below is not the field list — it
 * is the shape of three of those fields. A record that does not argue its own shape is a
 * record nobody can trust the first time somebody wants to change it.
 *
 * ### Children are ids, so this is not a tree
 *
 * `slots` holds `NodeId`s. A [Node] cannot contain another [Node], so the type is not
 * recursive, and the document is not a tree in the way a `ViewGroup` is a tree: it is a flat
 * table — §5.5's `NodeTable`, a `Map<NodeId, Node>` — plus an ordered list of child ids per
 * slot.
 *
 * That is a trade, and §5.6 tabulates it, so both columns are worth reporting rather than the
 * flattering one.
 *
 * **What the normalized form buys** — §5.6's chosen row: O(1) lookup by id, O(depth)
 * structural sharing, cheap moves and patches, stable identity, and references and selections
 * that *are* ids. The last of those carries the most weight, and it is why §6.2 says ids are
 * assigned once, never reused, and never derived from position or name: a selection, a focus,
 * an open dialog, a patch and a document diff all address a node the same way, and none of them
 * has to know where in the document it sits.
 *
 * **What it costs** — the cons column of the same row: invariants that have to hold (single
 * parent, no cycles, no orphans) and a parent index that has to exist. §6.2 states them
 * precisely — every node reachable from exactly one root and having exactly one parent, no
 * cycles, every `NodeId` in a slot naming a node that exists, roots existing — and none of them
 * is free. They are checked by the structural pass of §17.1 rather than by this class, because
 * no single node can see the table it belongs to and a check that only sees itself is a check
 * that can be fooled. §5.6's other half of the bargain is `DocumentIndex` — `parentOf`,
 * `ownerOf`, `pathTo`, `descendants` — lazily built, cached by document identity in the
 * editing layer, and never persisted.
 *
 * So the trade is: cheaper edits, cheaper patches, and a diff that compares ids, paid for with
 * invariants somebody has to check and an index somebody has to build. This class owes the
 * second half of that bargain nothing, and that is precisely why `slots` holds bare ids and
 * not nodes — the moment this record held nodes, the parent index *would* be the tree, and
 * §5.6's rejected option would be back with a new name.
 *
 * ### The property map is closed on both sides
 *
 * `props` is a `Map<PropertyKey, PropertyValue>`, and the thing to notice is that
 * **neither half is a bare string**. `PropertyKey` is one of §5.2's typed identifiers and
 * validates its own syntax in its constructor; `PropertyValue` is the two-arm closed union of
 * §5.4. A map with a bare `String` key and an `Any` value is the shape PLAN §23.4 forbids
 * outright, and the reason is not tidiness: it puts a document past the point where the type
 * system can help. Once a value is an `Any`, the analyzer cannot check it, the serializer
 * cannot infer a schema for it, and the failure arrives at runtime as somebody else's bug
 * report.
 *
 * The rule that enforces it is `NoUntypedStringMapTest` in `:tools:architecture-tests`, and
 * until this record existed it was passing partly by accident of there being no such map to
 * find. `props` is the first one this module would ever have contained, so it is worth being
 * precise about what the rule now protects: `ModifierEntry.args` and `ActionStep.args` are the
 * same shape, and a fourth and fifth untyped map elsewhere in the engine would be invisible to
 * every other check in the build. Note that the rule is a text scanner over source files, so
 * the forbidden shape must not be *spelled* in a comment here either — which is why this
 * paragraph describes it in words instead of writing it out.
 *
 * ### Two orderings, and only one of them is canonical
 *
 * `props` is annotated *sorted by key when written* and `modifiers` is annotated *order is
 * semantically significant*, and the asymmetry between those two comments is the design, not
 * an inconsistency between them. Sorting a map is a canonicalization: two documents that mean
 * the same thing must produce the same bytes, or every unrelated edit shows up as a diff
 * (§18's "sorted map keys, nodes sorted by id"; §6.2's "Deterministic serialization"). Sorting
 * a modifier list would change what it means — see [modifiers].
 *
 * ### What the annotation on this class is, and what it is not
 *
 * `@Serializable` and nothing else. There is no `@JsonClassDiscriminator` here, and there is
 * no `@SerialName` either, and both absences are decisions:
 *
 *  * **A discriminator distinguishes variants of a union.** `Value`, `PropertyValue`, `Expr`,
 *    `RefTarget` and `TypeRef` each have a `@JsonClassDiscriminator("type")` because each is a
 *    sealed hierarchy whose encoded form has to say *which* member it is. A [Node] is one shape
 *    with seven fields and no alternatives, so there is nothing to distinguish: its serializer
 *    writes a plain object, and adding the annotation would ask kotlinx to emit a tag that no
 *    reader of the document is expecting and no other field in this module spells.
 *  * **A `@SerialName` on a non-polymorphic class is inert.** It sets the serial name in the
 *    descriptor, and the descriptor's name is only consulted when the class is a member of a
 *    polymorphic scope. There is no such scope here, so `@SerialName("node")` would be a
 *    promise to readers that nothing enforces — the worst kind of annotation. The *fields* are
 *    a different matter: their Kotlin names are their wire names, so `id`, `type`, `name`,
 *    `props`, `modifiers`, `slots` and `events` are the format contract, and `NodeTest` reads
 *    each one back out of the encoded JSON for exactly that reason.
 *
 * The consequence is worth stating because it looks like a contradiction of a rule two other
 * files in this module state explicitly: **a property named `type` on this class is not the
 * collision those files warn about.** `Expr` and `TypeRef` both say no property may be called
 * `type`, because the discriminator owns that key in a polymorphic scope and kotlinx fails
 * schema construction when the two collide. That failure needs a discriminator. This class has
 * none, so `type` here is an ordinary field that writes `{"type":"core.Column"}` and means
 * "which component", and PLAN §30.2's example document writes it that way. The day this class
 * became a sealed hierarchy, or gained a polymorphic base, the rule would come back in force —
 * and `type` would be the first thing to have to move.
 *
 * @see ModifierEntry for the ordered pair this record holds, and why order is meaning.
 * @see Value for the seventeen things a [PropertyValue.Const] can carry, and for why the
 *   union is closed.
 */
@Serializable
public data class Node(

    // -----------------------------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------------------------

    /**
     * The id this node is addressed by, everywhere: in a parent's slot, in a patch, in a
     * selection, in a diagnostic.
     *
     * Required, and the only identity the record has. §6.2 is the rule that makes it work —
     * ids are assigned once, never reused, never derived from position or name, and are unique
     * across every page and component in the document — and §5.1's definition of a node is
     * "identified by `NodeId`" before anything else about it.
     *
     * **One thing to know before `NodeTable` is.** §5.5 wraps these in a `NodeTable`, which is
     * a `Map<NodeId, Node>`, and §5.5's own comment says the table encodes
     * `Map<NodeId, NodeBody>` — a type PLAN never declares anywhere. §30.2's example document
     * is written the other way: node bodies carry `type`, `props`, `modifiers`, `slots` and
     * `events` and *no* `id`, because the key already is the id. So `id` is required here by
     * §5.3 and redundant as a table key, and the two readings differ by one field on the wire.
     * §5.3 is the specification this class implements, and an id reachable from the value is
     * what `without(id)`, `DocumentIndex.parentOf` and a diagnostic with a `nodeId` want — but
     * the question of whether `NodeTableSerializer` writes `id` inside the body or only as the
     * key belongs to the work unit that owns the table, and it is a `FORMAT_VERSION` event when
     * it is answered.
     */
    public val id: NodeId,

    /**
     * Which component this node is an instance of, by [ComponentType].
     *
     * Namespaced, because a plugin has to be able to contribute a component without colliding
     * with a built-in, and the namespace is what makes that decidable: `core.Column`,
     * `m3.Text`, and `doc.<ComponentDeclId>` for an instance of a document-defined reusable
     * component (§5.7).
     *
     * **Nothing here checks that this component exists**, and nothing could: §23.3 records
     * `:engine:model` as depending on no other module, so the `ComponentRegistry` is not
     * visible from here and could not be consulted if it were. That is D4 rather than a gap —
     * a node written against a component this engine has never heard of still decodes, still
     * round-trips unchanged, and is reported by validation as a `component.unknown` diagnostic
     * with this node's id attached. `NodeTest` pins the round trip, because an older engine
     * opening a newer document and silently dropping the parts it does not understand would
     * destroy the newer engine's work.
     */
    public val type: ComponentType,

    /**
     * A human-facing label for this node, if the document gives it one.
     *
     * A hint and nothing more, and §5.3's own comment says so in three words. No lookup is
     * keyed on it, no reference names it, no generated identifier is derived from it, and two
     * nodes may carry the same one. It exists so a person reading a document, or a design tool
     * drawing a selection overlay, can tell a `core.Column` apart from its siblings.
     *
     * **It is not the `name` that `Page` and `ComponentDecl` have**, and that difference is
     * worth stating because the two fields share a name and do not share a job: §5.5 gives
     * `Page.name` and `ComponentDecl.name` the comment *"Kotlin identifier"* and makes it
     * load-bearing — it becomes a composable's name in generated code (§16.4's naming policy)
     * and an invalid one is a validation error. This one is a label: nullable, unvalidated, not
     * unique, and resolved by nothing. Renaming it cannot invalidate a reference, a selection
     * or an id, which is the whole of what it is for.
     */
    public val name: String? = null,

    // -----------------------------------------------------------------------------------
    // Content
    // -----------------------------------------------------------------------------------

    /**
     * The node's properties: [PropertyKey] to [PropertyValue].
     *
     * The shape §5.3 mandates and the reason this module has to land before any other: both
     * halves are closed domain types, so a decoder knows what an entry is without knowing which
     * component owns it (D4), and §23.4's rule against an untyped string map has nothing to
     * find here. It is the same currency as `ModifierEntry.args` and `ActionStep.args`, which is
     * what makes the three of them change together or not at all.
     *
     * Every entry is validated against the owning component's `PropertySpec`s — §5.3's last
     * clause, and deliberately *not* here: a value decodes without a schema, and a schema that
     * had to be consulted to read a document could not let a newer plugin's properties survive
     * an older engine. `ActionStep`'s KDoc makes the same argument for action arguments.
     *
     * ### What "sorted by key when written" can and cannot mean here
     *
     * §5.3 annotates this field *sorted by key when written*, and that promise is worth more
     * than a transcription — it is the difference between a document that diffs cleanly and one
     * that does not. What **this class** delivers is narrower and is all it can deliver: the
     * map's own iteration order is written, unchanged, and nothing here reorders it. Sorting is
     * not expressible at this level even in principle — [PropertyKey] is a `@JvmInline value
     * class` over `String` with no natural ordering, so there is no `sortedMapOf` to build here
     * and no `Comparator` to hand one.
     *
     * The obligation therefore belongs to whoever writes the document: §18's canonical writer,
     * which emits object keys in sorted order, UTF-16 lexicographic and locale-independent, and
     * which §6.2's "Deterministic serialization" row and `FORMAT_VERSION`'s own KDoc both name.
     * `NodeTest` pins this from both ends — that insertion order survives untouched here, and
     * that sorting the entries is what produces the canonical order — so a writer that forgets
     * to sort fails where it lives instead of being assumed correct by a record that never
     * promised it.
     */
    public val props: Map<PropertyKey, PropertyValue> = emptyMap(),

    /**
     * The modifier chain, in the order it is applied.
     *
     * **A [List] and not a set, not a map, and deliberately not sorted** — the asymmetry with
     * [props] above is the whole design and it is worth taking apart, because a reader
     * reasonably expects both collections in a record to be canonicalized the same way.
     *
     * `padding` then `background` and `background` then `padding` are different layouts: the
     * first pads inside the background, the second paints the background and then pads
     * outside it. Order is the meaning, which is why §5.1 defines a modifier entry as an
     * *"ordered (ModifierType, args) applied to a node"* and §5.3's comment on this field says
     * it outright. §7.3 is where it is consumed and says the same thing: the runtime folds
     * `node.modifiers` in order through its appliers, threading the scope bag.
     *
     * So sorting this list would not be a canonicalization; it would be a semantic change that
     * every consumer of the document would have to undo before rendering. The diff cost is real
     * and it is accepted: two modifiers swapped is one small hunk in one node's line, against a
     * formatter that reorders what an author wrote until the author's intent is no longer
     * recoverable from the file.
     *
     * A set would additionally lose duplicates, and a duplicate modifier is not noise — it is
     * two applications of the same thing, which is how `layout.padding` gets applied twice.
     */
    public val modifiers: List<ModifierEntry> = emptyList(),

    // -----------------------------------------------------------------------------------
    // Structure and behaviour
    // -----------------------------------------------------------------------------------

    /**
     * The node's children, by slot name and in render order.
     *
     * **The field that makes this a normalized record and not a tree.** The values are
     * [NodeId]s and not [Node]s, which is the entire argument of §5.6 in one signature: the
     * document is flat, a child is a reference, and every property §5.6 buys for the normalized
     * form follows from that and cannot be had without it.
     *
     * A map because slots are *named* content areas — `children`, `content`, `topBar` (§5.1) —
     * and a plugin component declares its own. A list per slot rather than one list because
     * `m3.Button` puts its label in `content` while the surrounding layout puts its items in
     * `children`, and collapsing them would make the document unable to say which is which.
     * A list rather than a set because order inside a slot is layout: `children[0]` is the
     * first item, and §15.4's runtime walks them in order.
     *
     * **Nothing resolves these ids**, for the same reason [type] resolves nothing: a child may
     * live in another page's table, or in a component declaration this file has never heard of,
     * and a slot entry is not obliged to name a node that exists until §17.1's structural pass
     * says so with a diagnostic. That is what buys cheap moves — moving a node between parents
     * edits two records and touches nothing else — and it is also why the ids here are the
     * reason §5.6 says a parent index has to exist.
     */
    public val slots: Map<SlotName, List<NodeId>> = emptyMap(),

    /**
     * The node's event handlers, by event key.
     *
     * §5.1's *"event handler: `EventKey → ActionSequence` on a node"*, and §11.1's line that a
     * UI event maps to a sequence *in the document*. `ActionSequence` is the same §11.2 type
     * `ActionStep` holds, and for the same reason it is data and not a lambda: it is
     * serializable, translatable into the generated Kotlin, and comparable in a document diff,
     * and none of those three survives a closure. `ActionSequence`'s own KDoc makes the
     * argument at length.
     *
     * A map rather than a list of pairs because the key is an [EventKey] — a checked simple
     * name — and a document that bound `onClick` twice under two spellings would be a document
     * two readers disagree about. An event a component does not declare still decodes, for the
     * D4 reason on [type].
     */
    public val events: Map<EventKey, ActionSequence> = emptyMap(),
)

/**
 * One modifier applied to a node: which one, and with what arguments.
 *
 * PLAN §5.1 defines this in one clause — *"Ordered (ModifierType, args) applied to a node"* —
 * and the pair exists because modifiers are registry entries with their own parameters rather
 * than a fixed set of fields on the node: §7.3 makes `padding`, `size`, `fillMaxSize`, `weight`,
 * `align`, `offset`, `background`, `clip` and `clickable` all `ModifierSpec`s, each with its
 * own arguments, its own scope rules and its own emission. The alternative — a `padding: Dp?`
 * field per modifier on [Node] — would put an open-ended extension point in a closed record,
 * and §5.4's D3 says the same thing about values: extensibility comes from the registry, not
 * from new fields.
 *
 * ### The two fields, and why only one of them is optional
 *
 * [type] has no default because a modifier entry without a modifier is not a modifier entry —
 * there is nothing to fill in later. [args] defaults to empty because most modifiers take
 * none, and §30.2's own example writes `{ "type": "layout.fillMaxSize" }` with no arguments at
 * all. A default that is not written is part of the wire contract, so that is a fact about the
 * format rather than a convenience: `layout.fillMaxSize` is one object on the page, and a
 * person reading a document is not asked to skip past an empty map to find out what a node
 * does.
 *
 * [args] is the same `Map<PropertyKey, PropertyValue>` as [Node.props] and `ActionStep.args`,
 * for the same reason and with the same consequences: both halves are closed domain types,
 * `NoUntypedStringMapTest` in `:tools:architecture-tests` has nothing to find here, and an
 * argument may be a `PropertyValue.Computed` as well as a constant — a padding that depends on
 * state is the ordinary case, not the exotic one.
 *
 * Nothing resolves [type] to a `ModifierSpec`, for the same reason [Node.type] resolves to
 * nothing: §23.3 puts the registry out of this module's reach, and D4 says an unknown modifier
 * type is preserved and reported rather than refused.
 *
 * @see Node.modifiers, where the order of a list of these is the meaning.
 */
@Serializable
public data class ModifierEntry(
    public val type: ModifierType,
    public val args: Map<PropertyKey, PropertyValue> = emptyMap(),
)
