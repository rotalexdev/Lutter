package dev.rotalex.lutter.serialization

import dev.rotalex.lutter.model.CURRENT_SCHEMA_VERSION
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * One document schema step over the envelope payload (PLAN §19.2).
 *
 * Pure JSON in, pure JSON out: the step never touches model classes, so it still runs
 * after those classes change. Unknown payload keys pass through untouched (§19.4);
 * a step owns only the keys it rewrites.
 */
public interface Migration {
    public val from: Int
    public val to: Int
    public fun apply(payload: JsonObject): JsonObject
}

/**
 * The outcome of [MigrationChain.migrate].
 *
 * Newer-than-supported arrives as [TooNew] carrying P1's [UnsupportedFutureVersion],
 * so callers match on a value instead of catching a throw. Nothing here is thrown.
 */
public sealed interface MigrationResult {
    /** Payload carried across at least one step; [migratedFrom] is where it started. */
    public data class Migrated(public val payload: JsonObject, public val migratedFrom: Int) : MigrationResult

    /** Payload already at the chain end, returned untouched. */
    public data class Current(public val payload: JsonObject) : MigrationResult

    /** Newer than the chain end; the [failure] carries both version numbers. */
    public data class TooNew(public val failure: UnsupportedFutureVersion) : MigrationResult
}

/**
 * The known document steps across a version gap (PLAN §19.2).
 *
 * Steps must join with no gaps: each starts where the previous ends, always forward.
 * An empty chain supports only the current version, which is the registry's state
 * until a second schema version exists.
 */
public class MigrationChain(migrations: List<Migration>) {
    /** The registered steps, frozen at construction: released migrations are immutable. */
    public val migrations: List<Migration> = migrations.toList()

    init {
        for (migration in migrations) {
            require(migration.to > migration.from) {
                "migration v${migration.from}->v${migration.to} must move forward"
            }
        }
        for ((previous, next) in migrations.zipWithNext()) {
            require(next.from == previous.to) {
                "migrations must join: v${previous.from}->v${previous.to} then v${next.from}->v${next.to}"
            }
        }
    }

    /**
     * [envelope] carried to the chain end. Too old has no path and throws; too new
     * returns [MigrationResult.TooNew]. The header is checked before this runs.
     */
    public fun migrate(envelope: JsonObject): MigrationResult {
        val from = (envelope["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw SerializationException("envelope has no integer 'schemaVersion'")
        val payload = envelope["payload"] as? JsonObject
            ?: throw SerializationException("envelope 'payload' must be a JSON object")
        val end = migrations.lastOrNull()?.to ?: CURRENT_SCHEMA_VERSION
        if (from > end) return MigrationResult.TooNew(UnsupportedFutureVersion(found = from, supported = end))
        val start = migrations.firstOrNull()?.from ?: CURRENT_SCHEMA_VERSION
        if (from < start) throw SerializationException("no migration from schemaVersion $from (earliest $start)")
        if (from == end) return MigrationResult.Current(payload)
        var current = payload
        for (migration in migrations) {
            if (migration.from >= from) current = migration.apply(current)
        }
        return MigrationResult.Migrated(payload = current, migratedFrom = from)
    }
}
