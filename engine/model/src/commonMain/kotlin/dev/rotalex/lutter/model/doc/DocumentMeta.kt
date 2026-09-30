package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PluginId
import kotlinx.serialization.Serializable

/**
 * The app-level facts a document states about itself.
 *
 * PLAN §5.5 declares three fields and the middle one is the reason this record exists:
 * `componentVersions` is D9's contract versions, the map that tells an older engine what a
 * document was written against before it tries to render it. A `ComponentType` key rather
 * than a name, for the same reason every other key in this module is a typed identifier —
 * the map is looked up by component, and a key that was not checked is a lookup that can
 * miss silently.
 */
@Serializable
public data class DocumentMeta(

    /** The document's name. A label, not an id: nothing is keyed on it. */
    public val name: String,

    /** What the document was authored against, checked at load (§27.2). */
    public val plugins: List<PluginRequirement> = emptyList(),

    /** The contract version of each component the document uses (D9). */
    public val componentVersions: Map<ComponentType, Int> = emptyMap(),
)

/**
 * A plugin the document was authored against, and the version it was authored against *that*.
 *
 * §30.2 and §27.1 both spell the field `version` and one comment in §5.5 used to say
 * `versionRange`; the two sites against one won, and the comment is corrected in place.
 *
 * **What the string means is not decided here and is not derivable.** No section specifies a
 * grammar, and §27.2:1919 names the outcome — `plugin.version_mismatch` — without the
 * comparison. A `String` rather than a semver type the model would have to validate is what
 * keeps that decision with the loader, which is the only place that knows the answer.
 */
@Serializable
public data class PluginRequirement(

    /** Which plugin. */
    public val id: PluginId,

    /** The version the document was authored against. Its grammar belongs to the loader. */
    public val version: String,
)
