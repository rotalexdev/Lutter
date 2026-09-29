package dev.rotalex.lutter.model.type

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a reference points at — the four things in a document a reference is allowed to name.
 *
 * PLAN §5.4 names the four in a comment on `Value.Ref`: *"page, component, resource,
 * dataModel"*. That comment is the whole specification, and the wire spelling of each entry
 * is taken from it verbatim, including the camel case of `dataModel`. A list of four words in
 * a comment is not much of a specification, which is exactly why every entry carries an
 * explicit `@SerialName`: the spelling is in code now, where a rename cannot quietly take it
 * with it.
 *
 * ### Why the kind is on the value and not only on the type
 *
 * A document may state a property's type as `TypeRef.Ref(RefKind.Page)`, and then the value
 * says `Value.Ref(RefKind.Page, "p_home")`. The kind appears in both. That redundancy is
 * deliberate, and it is the price of D4: a `Value` has to decode without consulting the
 * schema, and the schema is where the property's type lives. Redundancy that D4 requires is
 * not the same as a second source of truth — the analyzer's ReferencePass checks that the two
 * agree and reports a mismatch, rather than trusting either.
 *
 * ### Why the id is a bare `String`
 *
 * `Value.Ref` carries `id: String`, not a union of the four typed ids, and that is PLAN's
 * spelling rather than an oversight of mine. The kind decides which id type the target is —
 * `PageId`, `ComponentDeclId`, `ResourceId`, `DataModelId` — and a document may legitimately
 * point at a page that this engine has never heard of, which is a `PageId` whether or not the
 * page exists. Narrowing the string to the typed id at decode time would mean guessing the
 * kind's id class during decoding, and a guess is a schema lookup. A closed union of four id
 * types would encode the same information and cost a second hierarchy to keep in step with
 * this one; it is a format change, not a model change, and it is not mine to make.
 *
 * @see TokenKind for the other persisted kind enum.
 */
@Serializable
public enum class RefKind {

    /** A page: `Value.Ref`'s target is a `PageId`. */
    @SerialName("page")
    Page,

    /** A document-defined component: the target is a `ComponentDeclId`. */
    @SerialName("component")
    Component,

    /** A resource declaration: the target is a `ResourceId`. */
    @SerialName("resource")
    Resource,

    /** A document-declared data model: the target is a `DataModelId`. */
    @SerialName("dataModel")
    DataModel,
}
