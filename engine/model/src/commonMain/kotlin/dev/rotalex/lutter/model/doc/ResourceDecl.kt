@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

// The opt-in above is for exactly one thing: `@JsonClassDiscriminator`, which
// kotlinx.serialization still marks experimental. It has to be a file annotation and not a
// per-use `@OptIn` because Kotlin requires file annotations to precede the `package`
// declaration, and putting one after the imports is a syntax error rather than a warning.

package dev.rotalex.lutter.model.doc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Where a resource's bytes come from: the closed union of §20.
 *
 * Transcribed variant for variant, with the tags PLAN spells. The `@JsonClassDiscriminator` is
 * this project's decision and not PLAN's — §20 writes the union in the same compressed form
 * §9.1 and §10.1 write theirs, and every persisted union in this module names the key
 * explicitly so a library default can never rename it underneath a stored document.
 *
 * The other three types §20's paragraph declares — `ResourceDecl`, `ResourceVariant` and
 * `Qualifier` — are named there and nowhere else in the plan, and `ResourceVariant` cannot be
 * written without `Qualifier`. This union is the whole of the resource system that can be
 * built without inventing a wire contract.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface ResourceSource {

    /** Text held in the document itself. §20's MVP end-to-end case. */
    @Serializable
    @SerialName("text")
    public data class Text(public val value: String) : ResourceSource

    /** A path relative to the project root. */
    @Serializable
    @SerialName("bundled")
    public data class Bundled(public val path: String) : ResourceSource

    /** An absolute URL, fetched by `ResourceProvider`. */
    @Serializable
    @SerialName("remote")
    public data class Remote(public val url: String) : ResourceSource

    /** A content-addressed blob, by hash. §20 marks this post-MVP. */
    @Serializable
    @SerialName("embedded")
    public data class Embedded(public val hash: String) : ResourceSource
}
