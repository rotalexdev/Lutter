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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The codec contract: canonical bytes, pinned base type, typed failures.
 *
 * Fixture envelopes are built from [JsonObject], never typed by hand, so every rejection path
 * runs against a value the real parser produced. No test builds a `Json {}`.
 */
class JsonDocumentCodecTest {

    private val document = UiDocument(
        meta = DocumentMeta("Demo"),
        app = AppSpec("com.example.demo", PageId("p_home")),
        pages = mapOf(
            PageId("p_home") to Page(PageId("p_home"), "HomeScreen", "/home", root = NodeId("n_root")),
        ),
        components = emptyMap(),
        nodes = NodeTable.EMPTY.with(Node(id = NodeId("n_root"), type = ComponentType("core.Column"))),
    )

    @Test
    fun `encode decodes back to the same document with no migration`() {
        val decoded = JsonDocumentCodec.decode(JsonDocumentCodec.encode(document))

        assertEquals(document, decoded.document)
        assertNull(decoded.migratedFrom, "bytes at the current version migrate from nothing")
        assertTrue(decoded.warnings.isEmpty(), "a schema-free decode diagnoses nothing here")
    }

    @Test
    fun `encoding is byte-deterministic under the codec identity`() {
        assertEquals(FORMAT_ID, JsonDocumentCodec.formatId)

        val first = JsonDocumentCodec.encode(document)
        assertContentEquals(first, JsonDocumentCodec.encode(document))
        assertTrue(first.decodeToString().endsWith("\n"), "canonical text ends with a newline")
    }

    @Test
    fun `a newer schema version throws unsupported future with both numbers`() {
        val text = encodeEnvelope(document).replace(
            "\"schemaVersion\": $CURRENT_SCHEMA_VERSION",
            "\"schemaVersion\": ${CURRENT_SCHEMA_VERSION + 1}",
        )

        val failure = assertFailsWith<UnsupportedFutureVersion> {
            JsonDocumentCodec.decode(text.encodeToByteArray())
        }
        assertEquals(CURRENT_SCHEMA_VERSION + 1, failure.found)
        assertEquals(CURRENT_SCHEMA_VERSION, failure.supported)
    }

    @Test
    fun `unknown envelope fields fail strictly and decode leniently`() {
        val root = ForgeJson.parseToJsonElement(encodeEnvelope(document)) as JsonObject
        val bytes = CanonicalJsonWriter.write(
            JsonObject(root.toMap() + ("trace" to JsonPrimitive("debug"))),
        ).encodeToByteArray()

        assertFailsWith<SerializationException> { JsonDocumentCodec.decode(bytes) }
        assertEquals(document, JsonDocumentCodec.decode(bytes, DecodeOptions(lenient = true)).document)
    }

    @Test
    fun `unknown model fields, old versions and malformed bytes fail as serialization errors`() {
        val envelope = ForgeJson.parseToJsonElement(encodeEnvelope(document)) as JsonObject
        val payload = envelope["payload"] as JsonObject
        val extraModel = JsonObject(
            envelope.toMap() + ("payload" to JsonObject(payload.toMap() + ("unknownField" to JsonPrimitive(1)))),
        )
        assertFailsWith<SerializationException> {
            JsonDocumentCodec.decode(CanonicalJsonWriter.write(extraModel).encodeToByteArray())
        }

        val old = JsonObject(envelope.toMap() + ("schemaVersion" to JsonPrimitive(0)))
        assertFailsWith<SerializationException> {
            JsonDocumentCodec.decode(CanonicalJsonWriter.write(old).encodeToByteArray())
        }

        assertFailsWith<SerializationException> { JsonDocumentCodec.decode("not json{".encodeToByteArray()) }
        assertFailsWith<SerializationException> { JsonDocumentCodec.decode("[]\n".encodeToByteArray()) }
    }
}
