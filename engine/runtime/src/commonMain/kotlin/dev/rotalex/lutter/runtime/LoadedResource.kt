package dev.rotalex.lutter.runtime

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily

/**
 * What a resource load produced, per [ResourceKind]'s wave-2 reading (§20:1598).
 *
 * The decode is the provider's job, not the renderer's: Compose ships no common
 * byte-level image decoder, and a font family is built from platform machinery, so
 * the host hands the engine finished values and every renderer reads the same shape.
 *
 * The arms are the three kinds a load can satisfy. `String` has [ResourceProvider.stringFor]
 * instead — a string needs a locale argument and is not bytes — and `Icon` never goes through
 * a load at all: it resolves through an `IconSet` in the `TypeRegistry` (§20:1560).
 */
public sealed interface LoadedResource {

    /** A decoded image. */
    public data class Image(public val bitmap: ImageBitmap) : LoadedResource

    /** A loaded font family. */
    public data class Font(public val family: FontFamily) : LoadedResource

    /** The raw bytes of any other file. */
    public data class File(public val bytes: ByteArray) : LoadedResource {

        override fun equals(other: Any?): Boolean = other is File && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** The provider has nothing for the id. Renderers draw nothing rather than guess. */
    public data object Missing : LoadedResource
}
