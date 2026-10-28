package dev.rotalex.lutter.serialization

import dev.rotalex.lutter.model.doc.UiDocument

/**
 * Bytes in, documents out (PLAN §18.3).
 *
 * The codec never touches the domain model's shape: it reads and writes it. Byte movement is
 * P4's storage; older payloads are P3's migrations; neither belongs in this interface.
 */
public interface DocumentCodec {
    /** Envelope `format` id this codec reads and writes. */
    public val formatId: String

    /** Canonical bytes of [document] at the current versions. */
    public fun encode(document: UiDocument): ByteArray

    /** [DecodeResult] of [bytes]; failures throw rather than arriving as values. */
    public fun decode(bytes: ByteArray, options: DecodeOptions = DecodeOptions()): DecodeResult
}

/**
 * How [DocumentCodec.decode] reads.
 *
 * Strict by default: unknown envelope or model fields are errors (§19.4). `lenient` is the
 * tools-only escape hatch over unknown envelope fields; the payload stays strict below.
 */
public data class DecodeOptions(public val lenient: Boolean = false)

/**
 * A successful decode (PLAN §18.3).
 *
 * Failures are typed throws, not values here: a newer-than-supported payload throws
 * [UnsupportedFutureVersion], and malformed input throws SerializationException. Codes in
 * [warnings] stay strings because diagnosis consults the schema, which this module cannot.
 */
public data class DecodeResult(
    public val document: UiDocument,
    public val migratedFrom: Int? = null,
    public val warnings: List<String> = emptyList(),
)
