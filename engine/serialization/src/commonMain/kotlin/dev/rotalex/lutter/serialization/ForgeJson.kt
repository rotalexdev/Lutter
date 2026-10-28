package dev.rotalex.lutter.serialization

import kotlinx.serialization.json.Json

/**
 * The project's single JSON configuration (PLAN §18.2).
 *
 * Every encode and decode goes through this instance, so the format has one source of truth.
 * Key order and text layout belong to [CanonicalJsonWriter], never to a second `Json {}`.
 *
 * ### `explicitNulls = false` is half of a pair
 *
 * It takes a null key off the wire, so the read side has to cope without one: kotlinx tolerates
 * an absent nullable property under this flag and refuses it under the default one, and that
 * tolerance rather than any record's default is what the 21 defaultless nullable properties in
 * the format depend on. `ExplicitNullsTest` is what keeps the two this module owns honest; the
 * nineteen in `:engine:editing` are covered by `PatchWireFormatTest`, which reaches them
 * through kotlinx's default `Json` because this instance is not visible from there, so their
 * wire form is pinned and their reading under this configuration is not.
 */
// Hierarchies pin their own discriminator (`type`); this is the default for ones that do not.
internal val ForgeJson: Json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = false
    classDiscriminator = "k"
}
