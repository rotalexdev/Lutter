package dev.rotalex.lutter.serialization

import dev.rotalex.lutter.model.CURRENT_SCHEMA_VERSION
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The migration contract: current passes through, steps transform, the future is a value.
 *
 * Envelopes come from [encodeEnvelope] with the version rewritten, never typed by hand.
 * The 1→2 step is synthetic: version 1 is unfrozen, so no real migration exists yet.
 */
class MigrationChainTest {

    private val document = UiDocument(
        meta = DocumentMeta("Demo"),
        app = AppSpec("com.example.demo", PageId("p_home")),
        pages = mapOf(
            PageId("p_home") to Page(PageId("p_home"), "HomeScreen", "/home", root = NodeId("n_root")),
        ),
        components = emptyMap(),
        nodes = NodeTable.EMPTY.with(Node(id = NodeId("n_root"), type = ComponentType("core.Column"))),
    )

    // Synthetic 1→2: tags the document name, leaves every other key untouched.
    private val synthetic12 = object : Migration {
        override val from: Int = 1
        override val to: Int = 2
        override fun apply(payload: JsonObject): JsonObject {
            val meta = payload["meta"] as? JsonObject
                ?: throw SerializationException("synthetic v1 to v2 needs object 'meta'")
            val name = (meta["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw SerializationException("synthetic v1 to v2 needs string 'meta.name'")
            return JsonObject(payload.toMap() + ("meta" to JsonObject(meta.toMap() + ("name" to JsonPrimitive("$name (migrated)")))))
        }
    }

    private fun envelopeOf(schemaVersion: Int): JsonObject {
        val root = ForgeJson.parseToJsonElement(encodeEnvelope(document)) as JsonObject
        return JsonObject(root.toMap() + ("schemaVersion" to JsonPrimitive(schemaVersion)))
    }

    @Test
    fun `a current document passes through and decodes unmigrated`() {
        val result = assertIs<MigrationResult.Current>(Migrations.migrate(envelopeOf(CURRENT_SCHEMA_VERSION)))

        assertEquals(document, ForgeJson.decodeFromJsonElement(UiDocument.serializer(), result.payload))
    }

    @Test
    fun `the synthetic step transforms the payload and the result decodes`() {
        val result = assertIs<MigrationResult.Migrated>(
            MigrationChain(listOf(synthetic12)).migrate(envelopeOf(1)),
        )

        assertEquals(1, result.migratedFrom)
        val decoded = ForgeJson.decodeFromJsonElement(UiDocument.serializer(), result.payload)
        assertEquals(document.copy(meta = document.meta.copy(name = "Demo (migrated)")), decoded)
    }

    @Test
    fun `a newer version returns the typed failure instead of throwing`() {
        val result = assertIs<MigrationResult.TooNew>(
            Migrations.migrate(envelopeOf(CURRENT_SCHEMA_VERSION + 1)),
        )

        assertEquals(CURRENT_SCHEMA_VERSION + 1, result.failure.found)
        assertEquals(CURRENT_SCHEMA_VERSION, result.failure.supported)
    }

    @Test
    fun `the registered chain starts empty`() {
        assertTrue(Migrations.migrations.isEmpty(), "no real migration exists before a second version")
    }

    @Test
    fun `gaps, old versions and malformed envelopes fail`() {
        val dangling = object : Migration {
            override val from: Int = 3
            override val to: Int = 4
            override fun apply(payload: JsonObject): JsonObject = payload
        }
        assertFailsWith<IllegalArgumentException> { MigrationChain(listOf(synthetic12, dangling)) }
        assertFailsWith<SerializationException> { Migrations.migrate(envelopeOf(0)) }
        assertFailsWith<SerializationException> { Migrations.migrate(JsonObject(emptyMap())) }
    }
}
