@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

// The opt-in above is for exactly one thing: `@JsonClassDiscriminator`, which
// kotlinx.serialization still marks experimental. It has to be a file annotation and not a
// per-use `@OptIn` because Kotlin requires file annotations to precede the `package`
// declaration, and putting one after the imports is a syntax error rather than a warning.

package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.ResourceId
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
 * The records around it — `ResourceDecl`, `ResourceVariant`, `ResourceKind` and `Qualifier` —
 * are the rest of this file, per §33.1's `doc/ResourceDecl.kt` row.
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

/**
 * Something a document ships that is not code: a string, an image, a font, an icon, a file.
 *
 * PLAN §20 declares five entries. **There is no `Color`**, and §20's own comment used to list
 * one: the MVP bullet says *"Colors/typography are tokens (§14), not resources"*, and that is
 * the stronger of the two statements because it is categorical and names the owner. Three
 * things agree with it — §14.1 gives colours a home, §9.2 gives the editor a colour picker
 * rather than a resource picker, and [ResourceSource] has no arm that would carry a colour:
 * its four are text, a path, a URL and a hash.
 *
 * An unknown tag fails to decode, so a sixth entry is a `FORMAT_VERSION` event.
 */
@Serializable
public enum class ResourceKind {

    /** Text held in the document. The only kind end-to-end in MVP (§20). */
    @SerialName("string")
    String,

    /** A bitmap or vector image. Modeled and validated now, implemented in wave 2. */
    @SerialName("image")
    Image,

    /** A font file. Modeled and validated now, implemented in wave 2. */
    @SerialName("font")
    Font,

    /** An icon, resolved through an `IconSet` in the `TypeRegistry` (§20). */
    @SerialName("icon")
    Icon,

    /** Any other file. Modeled and validated now, implemented in wave 2. */
    @SerialName("file")
    File,
}

/**
 * A resource: one declaration, several ways of getting at it.
 *
 * The core never references Android resources or `Res` (§20). A reference is
 * `Value.Ref(RefKind.Resource, id)`, which is why [id] is the thing a `ResourceProvider`
 * is asked for and why renaming a file is not a document edit.
 */
@Serializable
public data class ResourceDecl(

    /** The id references use. */
    public val id: ResourceId,

    /** A label, so a person can tell two resources apart in a list. */
    public val name: String,

    /** What kind of thing this is. */
    public val kind: ResourceKind,

    /** The ways of reaching it. Empty is a declaration no provider can satisfy. */
    public val variants: List<ResourceVariant>,
)

/**
 * One way of reaching a resource: a set of qualifiers and the source they select.
 *
 * **[qualifiers] is a [Set] and stays one, and the determinism obligation goes to the
 * canonical writer.** §18.2 specifies the order of object keys and of arrays and says
 * nothing about sets, so a set's iteration order — which differs per target — would otherwise
 * reach the bytes and let variant selection differ between a JVM and a Wasm build of the same
 * document. Making it a `List` would fix the bytes and introduce a worse problem: qualifiers
 * are a *predicate* over the environment, their order carries no meaning, and a list invites
 * every reader to read priority into a position selection may not honour. §18.2's rule — a
 * set is written as an array ordered by each element's own canonical encoding — closes it
 * without lying about the type, and selection is set membership against the environment's
 * qualifier set, which is order-free by construction.
 */
@Serializable
public data class ResourceVariant(

    /** The qualifiers this variant answers to. A predicate, so a set and not a list. */
    public val qualifiers: Set<Qualifier>,

    /** Where the bytes come from. */
    public val source: ResourceSource,
)

/**
 * The environment a resource variant answers to: a locale, a density, or a theme variant.
 *
 * §20's comment names exactly these three, and each payload is the minimum that
 * distinguishes it and nothing more: a BCP-47 tag because `stringFor(id, locale)` needs one,
 * an integer `dpi` because density is a number and the alternative is a second closed
 * vocabulary, and an open `variant` string for theme — the same open-string situation as
 * §14.1's roles, with the same answer.
 *
 * A `theme` qualifier is **not** light/dark for colours: [ColorSpec] already carries that
 * pair, and duplicating it would give colour resolution two sources.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface Qualifier {

    /** A BCP-47 language tag. */
    @Serializable
    @SerialName("locale")
    public data class Locale(val tag: String) : Qualifier

    /** A screen density in dots per inch. */
    @Serializable
    @SerialName("density")
    public data class Density(val dpi: Int) : Qualifier

    /** A theme variant, `light` or `dark` or whatever the host defines. */
    @Serializable
    @SerialName("theme")
    public data class Theme(val variant: String) : Qualifier
}
