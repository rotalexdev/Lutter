package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.NodeId
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Every node in the document, in one table: `Map<NodeId, Node>`, wrapped.
 *
 * PLAN §5.5 declares this class and §5.6 argues for the shape it has. The wrapper earns its
 * keep three times over, and each is a member below rather than a comment: [ids] owns an
 * order the backing map does not, [equals] is structural where a bare `class` would give
 * identity, and [EMPTY] is the zero-length instance a document with no nodes still needs.
 *
 * ### The persistent map is invisible, and that is the design
 *
 * The backing type is `kotlinx-collections-immutable`'s `PersistentMap` and it appears in no
 * public signature: the constructor is `internal` and every member below takes or returns a
 * domain type. §5.5's own note, §24.1 and P4 all say the same thing, and `NodeTableSerializer`
 * being `internal` is the same argument applied to the serializer.
 *
 * `with` and [without] are the two mutation primitives, and they are written directly on the
 * library's KEEP-0459 participial API — `putting` and `removing`, not `put` and `remove`. The
 * old names are warnings in 0.5.x and are gone in 0.7, so this is the one place in the module
 * where a missed rename would show up at all.
 *
 * ### Equality is over the map, and that is why this is not a data class
 *
 * A `data class` would generate the equality this class needs — the wrapped map is the only
 * field — and would also put a library type in the generated `toString` and a `copy` that
 * hands out a table. §6.2 requires `NodeTable.equals` to be order-independent, and
 * delegating to the map satisfies it for free: `PersistentMap` *is* a [Map], and two maps
 * holding the same pairs are equal however they were built.
 *
 * ### `ids()` is a contract, not a consequence
 *
 * Ascending by id, which is the order §18.2 writes and the order §6.2's equality assumes.
 * It cannot be `map.keys`, because `persistentHashMapOf` — the backing type §29.1 names —
 * documents its iteration order as *unspecified*: an implementation that returned the map's
 * own keys would compile and violate the format, and nothing would say so until two engines
 * disagreed about a document's bytes.
 *
 * The comparison is on the id's string form, and [NodeId] is a value class over `String`
 * with no `Comparable`, so there is no `sortedBy` on the id to delegate to. `String`'s own
 * order is the one §18.2 states for keys, and `IdSyntax` restricts an id to
 * `[A-Za-z0-9_]`, so the UTF-16 code-unit and code-point readings cannot differ here.
 * Materialising the order costs `O(n log n)` per call; a table used for traversal should
 * keep a sorted key sequence beside the map, and that cache is an implementation detail
 * rather than a member.
 */
@Serializable(with = NodeTableSerializer::class)
public class NodeTable internal constructor(private val map: PersistentMap<NodeId, Node>) {

    public companion object {

        /**
         * The empty table.
         *
         * `nodes` on `UiDocument` carries no default, so without a zero-length instance there
         * is no way to build a document at all — and the builder DSL needs somewhere to start
         * before it has a first node.
         */
        public val EMPTY: NodeTable = NodeTable(persistentHashMapOf())
    }

    /** The node with this id, or `null`. */
    public operator fun get(id: NodeId): Node? = map[id]

    /**
     * The node with this id.
     *
     * @throws IllegalStateException if the table has no such node, naming the id and the size
     *   so the caller can tell a missing node from a wrong document.
     */
    public fun require(id: NodeId): Node =
        checkNotNull(map[id]) { "no node '$id' in a table of $size node(s)" }

    /** Whether the table holds this id. */
    public operator fun contains(id: NodeId): Boolean = map.containsKey(id)

    /** How many nodes the table holds. */
    public val size: Int get() = map.size

    /**
     * Every id, ascending. Not the backing map's iteration order — see the class KDoc.
     *
     * Lazy: the sequence is produced from the keys when it is consumed, and sorting is the
     * only work done per call.
     */
    public fun ids(): Sequence<NodeId> = map.keys.asSequence().sortedBy { it.value }

    /**
     * This table with [node] added, or with the node of the same id replaced.
     *
     * Replace and not refuse, for two reasons that are the same reason. §26.2's
     * `PatchOp.SetProp` carries a whole replacement `Node` and a property edit has no other
     * way to reach the table, so making this refuse an existing id would force every edit
     * path to test membership first and leave the editing layer holding a second mutation
     * primitive. And replacement keeps the key set identical, so [ids] is unchanged and both
     * the order and the structural sharing survive the update.
     */
    public fun with(node: Node): NodeTable = NodeTable(map.putting(node.id, node))

    /** This table without the node of this id. An absent id returns the same table. */
    public fun without(id: NodeId): NodeTable = NodeTable(map.removing(id))

    /** Order-independent, by §6.2. See the class KDoc for why this is not a data class. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is NodeTable && map == other.map)

    override fun hashCode(): Int = map.hashCode()

    override fun toString(): String = "NodeTable(size=$size)"
}

/**
 * Reads and writes a [NodeTable] as a JSON object keyed by id, entries in ascending order.
 *
 * `internal` and it lives here rather than in `:engine:serialization`, for the reason
 * §5.5:388 gives: the annotation on `NodeTable` names it by unqualified symbol, so it has to
 * be resolvable from the module that declares the annotated type, and §23.3 forbids this
 * module from depending on `:engine:serialization`. `internal` is not a leak — the persistent
 * map is already an internal detail, so nothing in `NodeTable`'s public surface names a type
 * from the library.
 *
 * ### The entries are `Node`, id included
 *
 * §5.5's own comment used to say the encoding was `Map<NodeId, NodeBody>`, a type the plan
 * never declares, and §30.2 wrote the bodies with no `id` because the key already was it.
 * §5.3:218 requires the id in the record, and an id reachable from the value is what
 * `without`, `DocumentIndex.parentOf` and a diagnostic with a `nodeId` want. So the id is
 * written in the body and the key repeats it.
 *
 * That makes the redundancy a fact about documents rather than an invariant this class
 * checks: **the key is derived from `Node.id`**, and a hand-written object whose key and
 * body disagree decodes to the body's id, because §5.3 makes the id the identity and the
 * section that checks invariants is §17.1's, not this one. An engine-written document cannot
 * disagree with itself — [serialize] writes the key from the same field it writes the body.
 *
 * ### Ordering, and where it is decided
 *
 * §18.2 requires the table sorted by id, and the sort is [NodeTable.ids]' rather than a
 * second one here, so the order exists in exactly one place. The map handed to the surrogate
 * is a [LinkedHashMap] for the same reason: `MapSerializer` writes a map in its own iteration
 * order, and a `PersistentHashMap` has none to promise.
 */
internal class NodeTableSerializer : KSerializer<NodeTable> {

    /**
     * The surrogate's own descriptor, because the wire form *is* a `Map<NodeId, Node>`.
     *
     * Its serial name is the surrogate's rather than `NodeTable`'s. That is the one cosmetic
     * cost of not hand-writing a descriptor, and it buys not having to keep 30 lines of
     * `SerialDescriptor` in step with an interface that has changed shape before.
     */
    override val descriptor: SerialDescriptor = SURROGATE.descriptor

    override fun serialize(encoder: Encoder, value: NodeTable): Unit {
        val ordered = LinkedHashMap<NodeId, Node>(value.size)
        for (id in value.ids()) ordered[id] = value.require(id)
        SURROGATE.serialize(encoder, ordered)
    }

    override fun deserialize(decoder: Decoder): NodeTable {
        val decoded = SURROGATE.deserialize(decoder)
        var table = NodeTable.EMPTY
        for (node in decoded.values) table = table.with(node)
        return table
    }

    private companion object {
        val SURROGATE: KSerializer<Map<NodeId, Node>> =
            MapSerializer(NodeId.serializer(), Node.serializer())
    }
}
