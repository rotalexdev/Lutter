package dev.rotalex.lutter.serialization

import dev.rotalex.lutter.model.doc.UiDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/**
 * The JSON codec: canonical bytes through P1's envelope (PLAN §18.3, ADR-006).
 *
 * Everything goes through [encodeEnvelope]/[decodeEnvelope] and the module's single `Json`,
 * so canonical numbers keep the model's spelling with no second formatter. Migrations always
 * run on JSON, which is what keeps this object their only target.
 */
public object JsonDocumentCodec : DocumentCodec {
    override val formatId: String = FORMAT_ID

    override fun encode(document: UiDocument): ByteArray =
        encodeEnvelope(document).encodeToByteArray()

    /**
     * [DecodeResult] of [bytes], unmigrated: `migratedFrom` stays null until P3.
     *
     * @throws UnsupportedFutureVersion newer payload; [SerializationException] for the rest.
     */
    override fun decode(bytes: ByteArray, options: DecodeOptions): DecodeResult {
        val text = bytes.decodeToString()
        val document = if (options.lenient) decodeLenient(text) else decodeEnvelope(text)
        return DecodeResult(document = document)
    }

    // Lenient drops unknown header fields, then rejoins the strict path: one `Json` per module,
    // so the payload still decodes through the strict ForgeJson (see DecodeOptions).
    private fun decodeLenient(text: String): UiDocument {
        val root = ForgeJson.parseToJsonElement(text)
        if (root !is JsonObject) throw SerializationException("envelope must be a JSON object")
        val stripped = JsonObject(root.filterKeys { it in ENVELOPE_HEADER_KEYS })
        return decodeEnvelope(CanonicalJsonWriter.write(stripped))
    }
}

// The §18.1 header; the strip list for lenient reads, while P1's own set stays the check.
private val ENVELOPE_HEADER_KEYS: Set<String> = setOf("format", "formatVersion", "schemaVersion", "payload")
