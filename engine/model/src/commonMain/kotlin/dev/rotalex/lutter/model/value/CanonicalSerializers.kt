package dev.rotalex.lutter.model.value

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * The serializers that make PLAN §5.4 rule D1 true on the wire.
 *
 * Both are referenced by the `Value` union as `@Serializable(CanonicalFloat::class)` and
 * `@Serializable(CanonicalDouble::class)`, which is why they are classes with a no-argument
 * constructor rather than `object`s — the compiler plugin instantiates the class it is
 * pointed at, and `@Serializable(SomeObject::class)` is not a thing it accepts.
 *
 * ### The one line that matters
 *
 * Neither serializer calls `Encoder.encodeFloat` or `Encoder.encodeDouble`. Not as a
 * shortcut, not because the value has already been canonicalized — because the JSON encoder
 * writes `writer.write(value.toString())` for a floating-point value, and `toString()` is the
 * platform-dependent formatting this whole design exists to avoid. The text is correct by the
 * time it leaves `canonicalText`, and calling `encodeDouble` would hand it to a function
 * that discards it and substitutes `Double.toString()`.
 *
 * Writing text into a JSON document without quoting it is not something `Encoder` offers, so
 * the text goes in as an unquoted literal through [JsonEncoder.encodeJsonElement]. That is
 * the supported way to tell the format "these exact characters, and they are already valid
 * JSON". Everything the encoder normally does — comma placement, indentation, quoting —
 * still happens around it; what it is told is only that the number is spelled this way.
 *
 * Decoding reads a number and hands it to [canonicalize], so a document is canonicalized on
 * the way in as well as on the way out. Both ends, deliberately: canonicalizing at only one
 * end means trusting one of them, and a document written by an older or buggier writer
 * should still land in the model as a value this engine can write back unchanged.
 *
 * ### Why JSON only
 *
 * The format is JSON, and anything else is an explicit failure rather than a silent
 * fallback. A serializer that quietly wrote a different representation under a different
 * format would produce documents only one reader understands, and that surfaces as a bug
 * report about a document rather than as an exception at the point of encoding.
 *
 * @see canonicalize for the canonical form itself, and for why `toString()` is refused.
 */

// ---------------------------------------------------------------------------------------
// Writing
// ---------------------------------------------------------------------------------------

/**
 * Writes [text] into a JSON document as a bare literal rather than a quoted string.
 *
 * A bare number is the whole point: `16.5` is a number a document can hold, and `"16.5"` is
 * a string that happens to look like one. The encoder quotes every string it is handed, which
 * is right for every other value in the model and wrong for this one, so the text is
 * introduced as a literal the encoder has been told not to quote.
 */
private fun writeCanonical(encoder: Encoder, text: String) {
    val json = encoder as? JsonEncoder
        ?: throw SerializationException(
            "Canonical numerics can only be encoded by the Json format",
        )
    json.encodeJsonElement(JsonUnquotedLiteral(text))
}

// ---------------------------------------------------------------------------------------
// The serializers
// ---------------------------------------------------------------------------------------

/**
 * Writes a `Float` in canonical form, and reads one back canonicalized.
 *
 * Backs `Value.Float32`, `Value.Dp` and `Value.Sp` in PLAN §5.4.
 *
 * ```kotlin
 * @Serializable
 * @SerialName("dp")
 * public data class Dp(@Serializable(CanonicalFloat::class) public val v: Float) : Value
 * ```
 */
public class CanonicalFloat : KSerializer<Float> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(SERIAL_NAME, PrimitiveKind.FLOAT)

    override fun serialize(encoder: Encoder, value: Float): Unit =
        writeCanonical(encoder, canonicalText(value))

    override fun deserialize(decoder: Decoder): Float = canonicalize(decoder.decodeFloat())

    private companion object {
        const val SERIAL_NAME = "dev.rotalex.lutter.model.value.CanonicalFloat"
    }
}

/**
 * Writes a `Double` in canonical form, and reads one back canonicalized.
 *
 * Backs `Value.Float64` in PLAN §5.4. The same rules as [CanonicalFloat], and the same
 * refusal of a magnitude outside [MAX_CANONICAL_MAGNITUDE] — the two differ in what they can
 * hold, not in what they are allowed to write.
 */
public class CanonicalDouble : KSerializer<Double> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(SERIAL_NAME, PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Double): Unit =
        writeCanonical(encoder, canonicalText(value))

    override fun deserialize(decoder: Decoder): Double = canonicalize(decoder.decodeDouble())

    private companion object {
        const val SERIAL_NAME = "dev.rotalex.lutter.model.value.CanonicalDouble"
    }
}
