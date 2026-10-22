package dev.rotalex.lutter.serialization

import dev.rotalex.lutter.model.CURRENT_SCHEMA_VERSION
import dev.rotalex.lutter.testsupport.Golden
import dev.rotalex.lutter.testsupport.classpathGoldenSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The `resources/migrations` policy of §19.5, made real for the version that exists today.
 *
 * `v1.json` and `v1.migrated.json` are kept forever. Today `schemaVersion = 1` is the only
 * version there is, so the migration is a pass-through and the two files carry the same
 * bytes: the migrated golden is not a copy the test trusts, it is an output the chain
 * produces and the test compares against committed text. When a real 1→2 step lands,
 * `v2.json` is added and `v1.migrated.json` is rewritten to the migrated form — the fixture
 * itself never moves, which is the half of §19.5 that stops a schema change from rewriting
 * history.
 *
 * ### Why the fixture's bytes are the committed canonical golden's
 *
 * They are copied from `goldens/counter-document.golden`, whose bytes
 * `CanonicalJsonGoldenTest` already pins against the writer. Nothing here hand-types a new
 * shape: the fixture only has to be a valid envelope at the current version, and copying a
 * byte-proven one keeps every row's failure meaning a real defect instead of a typo in
 * fixture text. The rows still judge the copied bytes themselves — the canonical row has
 * the codec rewrite them and compare, so a wrong copy fails loudly rather than passing by
 * construction.
 *
 * ### Why a future-version row runs on the committed envelope
 *
 * `MigrationChainTest` covers the chain's behavior with envelopes the test builds from
 * `encodeEnvelope`. The row here bumps the version on the committed fixture's envelope, so
 * the refusal is exercised on bytes a maintainer was handed rather than bytes the test
 * assembled: the same header a real v2 file would carry, through the codec's throwing path.
 */
class MigrationTests {

    @Test
    fun `the v1 fixture migrates to the golden committed next to it`() {
        val envelope = fixture(FIXTURE)
        val migrated = assertIs<MigrationResult.Current>(
            Migrations.migrate(envelope),
            "the current version passes through unchanged until a real migration exists",
        )

        // The header is spliced in unmodified, so the rebuilt envelope carries exactly the
        // format and versions the fixture was handed with.
        val rebuilt = JsonObject(envelope.toMap() + ("payload" to migrated.payload))

        Golden.assertEquals(MIGRATED_GOLDEN, CanonicalJsonWriter.write(rebuilt), classpathGoldenSource())
    }

    @Test
    fun `the committed v1 fixture is canonical bytes the codec rewrites unchanged`() {
        val text = Golden.read(FIXTURE, classpathGoldenSource())
        val document = decodeEnvelope(text)

        assertEquals(
            text,
            encodeEnvelope(document),
            "the codec rewrote the committed fixture bytes; the fixture is not canonical text",
        )
    }

    @Test
    fun `the v1 fixture survives decode, encode, and decode again`() {
        val text = Golden.read(FIXTURE, classpathGoldenSource())
        val decoded = decodeEnvelope(text)

        assertEquals(
            decoded,
            decodeEnvelope(encodeEnvelope(decoded)),
            "a second round trip through the codec changed the fixture's document",
        )
    }

    @Test
    fun `a future schemaVersion on the fixture envelope is refused with UnsupportedFutureVersion`() {
        val envelope = fixture(FIXTURE)
        val tooNew = JsonObject(
            envelope.toMap() + ("schemaVersion" to JsonPrimitive(CURRENT_SCHEMA_VERSION + 1)),
        )

        val failure = assertFailsWith<UnsupportedFutureVersion> { decodeEnvelope(CanonicalJsonWriter.write(tooNew)) }

        assertEquals(CURRENT_SCHEMA_VERSION + 1, failure.found)
        assertEquals(CURRENT_SCHEMA_VERSION, failure.supported)
    }

    /** The fixture at [name], or the test-authoring error [Golden.read] raises when absent. */
    private fun fixture(name: String): JsonObject =
        assertIs<JsonObject>(
            ForgeJson.parseToJsonElement(Golden.read(name, classpathGoldenSource())),
            "$name must be a JSON envelope object",
        )

    private companion object {
        const val FIXTURE = "migrations/v1.json"
        const val MIGRATED_GOLDEN = "migrations/v1.migrated.json"
    }
}
