package dev.rotalex.lutter.serialization

import kotlinx.serialization.json.Json

/**
 * The project's single JSON configuration (PLAN §18.2).
 *
 * Every encode and decode goes through this instance, so the format has one source of truth.
 * Key order and text layout belong to [CanonicalJsonWriter], never to a second `Json {}`.
 */
// Hierarchies pin their own discriminator (`type`); this is the default for ones that do not.
internal val ForgeJson: Json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = false
    classDiscriminator = "k"
}
