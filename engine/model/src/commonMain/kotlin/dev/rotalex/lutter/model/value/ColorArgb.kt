package dev.rotalex.lutter.model.value

import kotlin.jvm.JvmInline
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A colour, packed as four 8-bit channels in the order alpha, red, green, blue.
 *
 * PLAN §5.4 names this type without defining it, and §9.2 says all the design has to say about
 * it: the JSON form is `#AARRGGBB` and the generated code is `Color(0xFF6200EE)`. Those two
 * lines are the specification, and between them they decide the representation, which is
 * [argb]: one `Int` holding `0xAARRGGBB`.
 *
 * ### Why an `Int` and not a `ULong`
 *
 * Four arguments, and the first is the one that would have settled it if the others had not:
 *
 *  * **A value class over `ULong` does not erase to a primitive.** `Ids.kt` is explicit that a
 *    value class "erases to its `String` on the JVM and to the underlying type everywhere
 *    else" and that a map of them allocates nothing per key. That is true for `Int`, `Long` and
 *    `String`. It is *not* true for `ULong`: unsigned types are themselves value classes, so a
 *    value class over one is a value class over a value class, and on the JVM it boxes. The
 *    alternative would have made every colour in a document a heap object, to store 32 bits in
 *    64.
 *  * **It is the representation Compose already uses.** `Color(color: Int)` and
 *    `Color.toArgb(): Int` are both `Int`, and §9.2's own codegen column writes the literal as
 *    `0xFF6200EE`. Marshalling to Compose is `Color(color.argb)` — a reinterpretation, not a
 *    conversion — and no amount of care later can stop red and blue being swapped by somebody
 *    who assumed the model stored `0xAABBGGRR`. Making the model's layout the same as the
 *    consumer's removes a place for that mistake to live.
 *  * **The hex literal is the same literal.** `0xFF6200EE` is a valid Kotlin `Int` — Kotlin
 *    reads a hexadecimal literal that does not fit as the bit pattern it names, which is why
 *    `0xFFFFFFFF` is `-1` — so the model, the generated code and the wire form can all be
 *    written with one spelling of the number rather than three conversions between them.
 *  * **A channel is a byte, and a `ULong` is not what a byte needs.** Packing four channels
 *    into a wider integer buys nothing: `0xAARRGGBB` is 32 bits and there is no spare room to
 *    fill, so the extra 32 bits would be a permanent assertion that they are zero.
 *
 * ### Where the validation is, and why it is not in `init`
 *
 * This is the part of the design that a packed representation changes, and it is worth being
 * blunt about: **every one of the two-to-the-thirty-two `Int` values is a valid ARGB colour**,
 * so there is nothing for a constructor to refuse and an `init` block checking the packed form
 * would be a lie about doing work.
 *
 * The check that does real work is the one on the channels, and it lives where channels are
 * taken: [of] and [rgb] refuse anything outside `0..255`. That is the same principle as
 * `Ids.kt` — a malformed colour cannot be built, and the question is answered once at
 * construction rather than at every use — with one difference worth naming. An id has a
 * grammar, so its constructor can refuse; a packed colour has no invalid state, so the
 * factories are the whole of the validation. Declaring a private constructor and three
 * factory methods would be the same guarantee with more ceremony and one more way to get an
 * `Int` literal into a `ColorArgb` by accident, which is the mistake this type exists to
 * prevent.
 *
 * ### Two spellings, decided here
 *
 * The wire form is exactly `#` and eight hex digits. Input is case-insensitive, because a
 * document is written by tools *and* by hand and `ff` is not a different colour from `FF`.
 * Output is upper case, because a document that round-trips should be byte-identical and
 * `0xff` and `0xFF` in the same file is a diff nobody asked for. Anything else — a missing
 * `#`, seven digits, a non-hex digit, an empty string — is refused, with the offending text in
 * the message.
 *
 * ### Why the serializer is here and not in `:engine:serialization`
 *
 * ADR-005 puts `@Serializable`/`@SerialName` on the model and the codec in
 * `:engine:serialization`, so the natural question is why this type carries a serializer at
 * all. Because the answer is not a codec choice: the *only* acceptable wire form for a colour
 * is the eight-digit string, and if [ColorArgb] were `@Serializable` in the ordinary way the
 * encoder would write its underlying `Int` — `{"argb":-10203410}`. A document that cannot
 * express a colour readably is a document nobody can review, and a format where `#FF6200EE`
 * round-trips through a negative integer is a format where a human edits the file. The
 * serializer is a property of the type's wire form, exactly as `@SerialName` is, and it stays
 * on the class so that a use site cannot forget it: `Value.Color` and a future
 * `ColorSpec` both get `#AARRGGBB` without either of them saying so.
 *
 * Unlike [CanonicalFloat] this serializer is not restricted to JSON, and the difference is
 * deliberate: it writes an ordinary string through `encodeString`, which every format
 * supports, where a canonical number has to be injected as an unquoted literal that only
 * `JsonEncoder` can take.
 *
 * @see Value.Color, the variant that carries one.
 */
@Serializable(with = ColorArgbSerializer::class)
@JvmInline
public value class ColorArgb(public val argb: Int) {

    /** The alpha channel, `0` transparent to `255` opaque. */
    public val alpha: Int get() = (argb ushr 24) and CHANNEL_MASK

    /** The red channel, `0..255`. */
    public val red: Int get() = (argb ushr 16) and CHANNEL_MASK

    /** The green channel, `0..255`. */
    public val green: Int get() = (argb ushr 8) and CHANNEL_MASK

    /** The blue channel, `0..255`. */
    public val blue: Int get() = argb and CHANNEL_MASK

    /**
     * The wire form: `#` and eight upper-case hex digits, `0xAARRGGBB` spelled the readable
     * way.
     *
     * Identical to [toString], which is the point: a diagnostic, a diff and a document all
     * say the same thing about a colour, for the same reason an id reads as itself in a log
     * rather than as `NodeId(value=n_1)`.
     */
    public fun toHexString(): String = "#" + toHex(alpha) + toHex(red) + toHex(green) + toHex(blue)

    override fun toString(): String = toHexString()

    public companion object {

        /**
         * A colour from its four channels.
         *
         * The one place a channel is taken as a number, and the one place its range is
         * checked. Everything else in this file is arithmetic on an already-valid `Int`.
         *
         * @throws IllegalArgumentException if a channel is outside `0..255`, naming the
         *   channel and the value so the caller knows which argument was wrong.
         */
        public fun of(alpha: Int, red: Int, green: Int, blue: Int): ColorArgb {
            requireChannel(alpha, "alpha")
            requireChannel(red, "red")
            requireChannel(green, "green")
            requireChannel(blue, "blue")
            return ColorArgb(
                (alpha shl 24) or (red shl 16) or (green shl 8) or blue,
            )
        }

        /**
         * An opaque colour from three channels.
         *
         * The case a document writes ninety-nine times, and the one where forgetting the alpha
         * channel would be a bug rather than a decision.
         *
         * @throws IllegalArgumentException if a channel is outside `0..255`.
         */
        public fun rgb(red: Int, green: Int, blue: Int): ColorArgb = of(255, red, green, blue)

        /**
         * Reads the wire form, `#AARRGGBB`.
         *
         * Strict on purpose. The alternative — a parser that tolerates a missing `#`, three or
         * six digits, or a bare name like `red` — is a parser with more than one spelling for
         * a colour, and §5.4 spends its whole budget keeping one spelling per value.
         *
         * @throws IllegalArgumentException if [text] is not `#` followed by exactly eight hex
         *   digits, whatever their case.
         */
        public fun parse(text: String): ColorArgb {
            require(HEX.matches(text)) {
                "'$text' is not a colour; expected '#' and eight hex digits, as in '#FF6200EE'"
            }
            // Exact, and the shape is already known good: eight hex digits cannot exceed
            // 0xFFFFFFFF, and the top bit becomes the sign of the Int. That is the packing
            // this type is defined by, not an accident of the conversion, so the `toInt()` is
            // the reinterpreting step and nothing is lost here.
            return ColorArgb(text.substring(1).toLong(radix = 16).toInt())
        }

        private val HEX: Regex = Regex("^#[0-9a-fA-F]{8}$")

        /** Masks one channel out of a packed colour. 255 rather than `0xFF` spelled long-hand. */
        private const val CHANNEL_MASK: Int = 0xFF

        /**
         * Two hex digits, always two.
         *
         * A channel is `0..255`, so its hex is one or two characters and the padding is one
         * `if` — no `padStart`, whose `CharSequence` return type would have meant a second
         * conversion immediately after the one this function exists to avoid.
         */
        private fun toHex(channel: Int): String {
            val digits = channel.toString(radix = 16).uppercase()
            return if (digits.length == 1) "0$digits" else digits
        }

        private fun requireChannel(value: Int, name: String) {
            require(value in 0..255) {
                "colour channel '$name' is $value; every channel is 0..255"
            }
        }
    }
}

/**
 * Reads and writes [ColorArgb] as the eight-digit string, never as the packed `Int`.
 *
 * `@Serializable(with = …)` on the type means every use site gets this without saying so,
 * which is the property worth having: the one place a colour could be written as a bare
 * integer is the one place the wire form is decided.
 */
public class ColorArgbSerializer : KSerializer<ColorArgb> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(SERIAL_NAME, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ColorArgb): Unit =
        encoder.encodeString(value.toHexString())

    override fun deserialize(decoder: Decoder): ColorArgb = ColorArgb.parse(decoder.decodeString())

    private companion object {
        const val SERIAL_NAME = "dev.rotalex.lutter.model.value.ColorArgb"
    }
}
