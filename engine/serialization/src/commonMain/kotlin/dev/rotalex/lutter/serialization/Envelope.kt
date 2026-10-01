package dev.rotalex.lutter.serialization

import dev.rotalex.lutter.model.CURRENT_SCHEMA_VERSION
import dev.rotalex.lutter.model.FORMAT_VERSION
import dev.rotalex.lutter.model.doc.UiDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Envelope `format` id (PLAN §18.1). The codec reuses it as its `formatId` in P2. */
public const val FORMAT_ID: String = "forge.ui-document"

/**
 * The envelope: format identity, two versions, and the payload (PLAN §18.1).
 *
 * `formatVersion` moves only with the envelope layout; `schemaVersion` versions the payload
 * and is owned here (§19.1). Both stay on the wire: defaults are never encoded (§18.2).
 */
@Serializable
public data class Envelope(
    public val format: String,
    public val formatVersion: Int,
    public val schemaVersion: Int,
    public val payload: UiDocument,
)

/**
 * A document newer than this engine reads (PLAN §19.2).
 *
 * Thrown, not returned: P1 has no migration chain to hand it to, and P3 reuses the type as
 * its result payload. Carries both numbers so the message never has to be parsed.
 */
public class UnsupportedFutureVersion public constructor(
    public val found: Int,
    public val supported: Int,
) : SerializationException("unsupported schemaVersion $found (supported $supported)")

/** Canonical envelope text of [document] at the current versions. */
public fun encodeEnvelope(document: UiDocument): String =
    CanonicalJsonWriter.encode(
        Envelope.serializer(),
        Envelope(FORMAT_ID, FORMAT_VERSION, CURRENT_SCHEMA_VERSION, document),
    )

/**
 * [UiDocument] from envelope [text].
 *
 * The header is checked before the payload decodes, so a version disagreement fails on the
 * versions and never on a payload shape. Unknown envelope fields are errors (§19.4).
 *
 * @throws UnsupportedFutureVersion newer payload; [SerializationException] for the rest.
 */
public fun decodeEnvelope(text: String): UiDocument {
    val root = ForgeJson.parseToJsonElement(text)
    if (root !is JsonObject) throw SerializationException("envelope must be a JSON object")
    val unknown = root.keys - ENVELOPE_KEYS
    if (unknown.isNotEmpty()) throw SerializationException("unknown envelope field(s): $unknown")
    val format = (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw SerializationException("envelope has no string 'format'")
    if (format != FORMAT_ID) throw SerializationException("unsupported format '$format' (expected '$FORMAT_ID')")
    val formatVersion = (root["formatVersion"] as? JsonPrimitive)?.intOrNull
        ?: throw SerializationException("envelope has no integer 'formatVersion'")
    if (formatVersion != FORMAT_VERSION) {
        throw SerializationException("unsupported formatVersion $formatVersion (supported $FORMAT_VERSION)")
    }
    val schemaVersion = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
        ?: throw SerializationException("envelope has no integer 'schemaVersion'")
    if (schemaVersion > CURRENT_SCHEMA_VERSION) {
        throw UnsupportedFutureVersion(found = schemaVersion, supported = CURRENT_SCHEMA_VERSION)
    }
    if (schemaVersion != CURRENT_SCHEMA_VERSION) {
        throw SerializationException("unsupported schemaVersion $schemaVersion (supported $CURRENT_SCHEMA_VERSION)")
    }
    val payload = root["payload"]
        ?: throw SerializationException("envelope has no 'payload'")
    if (payload !is JsonObject) throw SerializationException("envelope 'payload' must be a JSON object")
    return ForgeJson.decodeFromJsonElement(UiDocument.serializer(), payload)
}

// The four header keys §18.1 declares; anything else is an unknown envelope field.
private val ENVELOPE_KEYS: Set<String> = setOf("format", "formatVersion", "schemaVersion", "payload")
