package dev.rotalex.lutter.serialization

import dev.rotalex.lutter.model.CURRENT_SCHEMA_VERSION
import dev.rotalex.lutter.model.FORMAT_VERSION
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The envelope contract: canonical bytes, pinned payload type, strict versions.
 *
 * Envelope inputs are built from [JsonObject], never typed by hand, so every rejection path
 * runs against a value the real parser produced.
 */
class EnvelopeTest {

    private val document = UiDocument(
        meta = DocumentMeta("Demo"),
        app = AppSpec("com.example.demo", PageId("p_home")),
        pages = mapOf(
            PageId("p_home") to Page(PageId("p_home"), "HomeScreen", "/home", root = NodeId("n_root")),
        ),
        components = emptyMap(),
        nodes = NodeTable.EMPTY.with(Node(id = NodeId("n_root"), type = ComponentType("core.Column"))),
    )

    private fun headerOf(vararg entries: Pair<String, JsonElement>): String =
        CanonicalJsonWriter.write(JsonObject(mapOf(*entries)))

    private fun payloadOf(document: UiDocument): JsonObject =
        ForgeJson.encodeToJsonElement(UiDocument.serializer(), document) as JsonObject

    private fun validHeader(payload: JsonElement): String = headerOf(
        "format" to JsonPrimitive(FORMAT_ID),
        "formatVersion" to JsonPrimitive(FORMAT_VERSION),
        "schemaVersion" to JsonPrimitive(CURRENT_SCHEMA_VERSION),
        "payload" to payload,
    )

    @Test
    fun `an envelope round-trips a document as canonical text`() {
        val text = encodeEnvelope(document)

        assertEquals(listOf("format", "formatVersion", "payload", "schemaVersion"), headerKeys(text))
        assertTrue(text.endsWith("\n"), "canonical text ends with a newline")
        assertEquals(document, decodeEnvelope(text))
        // The payload decodes through the pinned base-type serializer, never an inferred one.
        assertEquals(document, ForgeJson.decodeFromJsonElement(UiDocument.serializer(), payloadOf(document)))
    }

    @Test
    fun `a newer schema version fails as unsupported future`() {
        val text = validHeader(payloadOf(document)).replace(
            "\"schemaVersion\": $CURRENT_SCHEMA_VERSION",
            "\"schemaVersion\": ${CURRENT_SCHEMA_VERSION + 1}",
        )

        val failure = assertFailsWith<UnsupportedFutureVersion> { decodeEnvelope(text) }
        assertEquals(CURRENT_SCHEMA_VERSION + 1, failure.found)
        assertEquals(CURRENT_SCHEMA_VERSION, failure.supported)
    }

    @Test
    fun `an older schema version fails with its number stated`() {
        val text = headerOf(
            "format" to JsonPrimitive(FORMAT_ID),
            "formatVersion" to JsonPrimitive(FORMAT_VERSION),
            "schemaVersion" to JsonPrimitive(0),
            "payload" to payloadOf(document),
        )

        val failure = assertFailsWith<SerializationException> { decodeEnvelope(text) }
        assertTrue("0" in (failure.message ?: ""), "the refusal names the version: ${failure.message}")
    }

    @Test
    fun `a wrong format id or layout version fails before the payload decodes`() {
        val badFormat = validHeader(payloadOf(document)).replace(FORMAT_ID, "other.format")
        val formatFailure = assertFailsWith<SerializationException> { decodeEnvelope(badFormat) }
        assertTrue(FORMAT_ID in (formatFailure.message ?: ""), "the refusal names the expected id")

        val badLayout = headerOf(
            "format" to JsonPrimitive(FORMAT_ID),
            "formatVersion" to JsonPrimitive(FORMAT_VERSION + 1),
            "schemaVersion" to JsonPrimitive(CURRENT_SCHEMA_VERSION),
            "payload" to payloadOf(document),
        )
        assertFailsWith<SerializationException> { decodeEnvelope(badLayout) }
    }

    @Test
    fun `unknown envelope fields and unknown model fields are errors`() {
        val extraEnvelope = JsonObject(
            (ForgeJson.parseToJsonElement(validHeader(payloadOf(document))) as JsonObject).toMap() +
                ("extra" to JsonPrimitive(1)),
        )
        assertFailsWith<SerializationException> { decodeEnvelope(CanonicalJsonWriter.write(extraEnvelope)) }

        val extraModel = JsonObject(payloadOf(document).toMap() + ("unknownField" to JsonPrimitive(1)))
        assertFailsWith<SerializationException> { decodeEnvelope(validHeader(extraModel)) }
    }

    @Test
    fun `a missing payload or a non-object envelope fails`() {
        val noPayload = headerOf(
            "format" to JsonPrimitive(FORMAT_ID),
            "formatVersion" to JsonPrimitive(FORMAT_VERSION),
            "schemaVersion" to JsonPrimitive(CURRENT_SCHEMA_VERSION),
        )
        assertFailsWith<SerializationException> { decodeEnvelope(noPayload) }
        assertFailsWith<SerializationException> { decodeEnvelope("[]\n") }
    }

    private fun headerKeys(text: String): List<String> =
        (ForgeJson.parseToJsonElement(text) as JsonObject).keys.toList()
}
