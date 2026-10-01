package dev.rotalex.lutter.schema.registry

/**
 * One built registry: immutable, read-only, iterated in key order.
 *
 * Built once through [RegistryBuilder] and never mutated after, so a schema is safe to
 * share. Order is the keys' string form, which is the raw id for every id in `:engine:model`.
 */
public interface Registry<K : Any, V : Any> {
    /** The value for [key], or null when absent. Never throws; use [require] to fail. */
    public operator fun get(key: K): V?

    /** The value for [key]. Throws [NoSuchElementException] naming [key] when absent. */
    public fun require(key: K): V

    /** Whether [key] is registered. Total; never throws. */
    public operator fun contains(key: K): Boolean

    /** Every value, ordered by key. A copy; later builder writes change nothing. */
    public fun all(): List<V>
}

/**
 * A duplicate key, naming it. [SchemaBuilder] wraps this so a schema failure names the
 * registry too.
 */
public class DuplicateKeyException(message: String) : IllegalArgumentException(message)

/**
 * Builds one [Registry]. Duplicates fail here, not at read time: a silent override would
 * let two plugins claim one id with only ordering deciding the winner.
 */
public class RegistryBuilder<K : Any, V : Any> {
    private val entries: MutableMap<K, V> = mutableMapOf()

    /** Registers [value] under [key]. Throws [DuplicateKeyException] naming [key]. */
    public fun register(key: K, value: V): Unit {
        if (entries.containsKey(key)) throw DuplicateKeyException("Duplicate key '$key'")
        entries[key] = value
    }

    /** Registers every pair in order. The first duplicate throws; earlier pairs stay registered. */
    public fun registerAll(entries: List<Pair<K, V>>): Unit {
        entries.forEach { (key, value) -> register(key, value) }
    }

    /** Freezes the registry. Later registrations change nothing already built. */
    public fun build(): Registry<K, V> {
        // Sorted by hand, not `toSortedMap`: that function has no Wasm form, and this
        // module ships there. The order is the keys' string form, as [Registry] promises.
        val ordered = LinkedHashMap<K, V>(entries.size)
        for ((key, value) in entries.entries.sortedBy { it.key.toString() }) {
            ordered[key] = value
        }
        return MapRegistry(ordered)
    }
}

/** The single [Registry] implementation. Reachable only through [RegistryBuilder]. */
private class MapRegistry<K : Any, V : Any>(private val entries: Map<K, V>) : Registry<K, V> {
    override fun get(key: K): V? = entries[key]

    override fun require(key: K): V =
        entries[key] ?: throw NoSuchElementException("No entry for key '$key'")

    override fun contains(key: K): Boolean = entries.containsKey(key)

    override fun all(): List<V> = entries.values.toList()
}
