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
