package dev.rotalex.lutter.schema.migration

import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.ids.ComponentType

/**
 * One component contract step: `type` from `from` to `to` (D9, §19.3).
 *
 * Single-step by construction: a gap migrates as a chain of these, so each pass stays a
 * reviewable patch. The transform is per node and pure; the editing pass lifts it into
 * a patch, which is where `NodePatchScope` will live rather than here.
 */
public class SpecMigration(
    public val type: ComponentType,
    public val from: Int,
    public val to: Int,
    public val apply: (Node) -> Node,
) {
    init {
        require(to == from + 1) {
            "Migration for '$type' must step one version, got v$from to v$to"
        }
    }
}

/**
 * The known contract steps, chained across a version gap.
 *
 * Built once from a list; two steps from the same version of one component fail, since
 * only one history per component can be true.
 */
public class SpecMigrationRegistry(migrations: List<SpecMigration>) {
    private val steps: Map<Pair<ComponentType, Int>, SpecMigration> =
        LinkedHashMap<Pair<ComponentType, Int>, SpecMigration>(migrations.size).also { index ->
            for (migration in migrations) {
                val key = migration.type to migration.from
                require(!index.containsKey(key)) {
                    "Duplicate migration for '${migration.type}' from v${migration.from}"
                }
                index[key] = migration
            }
        }

    /**
     * The version [type] is at once every known step has been applied, or `null` when none is.
     *
     * §19.3's pass needs this as its destination, and [plan] cannot supply it: it takes `to` as
     * an argument precisely because this class has no opinion about where a component is *now*.
     * §19.1 puts that with the component author, and the steps below are only ever as new as
     * their author made them.
     *
     * **Derived from the steps, never stored.** The answer is one past the highest `from` this
     * registry holds for [type], and that is the only definition that stays true as migrations
     * are released: §19.2's rule is that a released migration is immutable, so the registry
     * grows and nothing in it is rewritten. A field recording "the current version" would be a
     * second source of truth that a step added without it would silently contradict.
     *
     * **`null` means "no steps known", not "version zero".** A component that has never changed
     * its contract has no entries here, and its current version is whatever the schema says it
     * is. A caller that wants a total map folds the schema's own versions in for the nulls —
     * see `SpecMigrationPass`'s KDoc for why the pass takes that map rather than a registry.
     */
    public fun latest(type: ComponentType): Int? =
        steps.keys.asSequence()
            .filter { it.first == type }
            .map { it.second + 1 }
            .maxOrNull()

    /**
     * The steps carrying [type] from [from] to [to], in order. Empty when already there.
     * Refuses backwards gaps and uncovered steps: a silent skip would leave nodes behind.
     */
    public fun plan(type: ComponentType, from: Int, to: Int): List<SpecMigration> {
        require(from <= to) { "Cannot migrate '$type' backwards from v$from to v$to" }
        val plan = mutableListOf<SpecMigration>()
        var version = from
        while (version < to) {
            val step = steps[type to version]
                ?: throw NoSuchElementException(
                    "No migration for '$type' from v$version to v${version + 1}",
                )
            plan += step
            version = step.to
        }
        return plan
    }

    /** [node] carried across its own type's gap. The node's type selects the chain. */
    public fun migrate(node: Node, from: Int, to: Int): Node =
        plan(node.type, from, to).fold(node) { current, step -> step.apply(current) }
}
