package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import kotlinx.serialization.Serializable

/**
 * A structured type the document declares for itself, and the entries of an enum it declares.
 *
 * These two records are the same mechanism from §5.4's D3 — extensibility through
 * *data-defined types* rather than through new union variants — and they are declared here
 * rather than in `:engine:schema` because a document is the only place that can name them:
 * §5.4:269 and §9.1:750 both provide for a document-declared type, and §23.3 puts the
 * registry that would otherwise own them out of this module's reach.
 *
 * PLAN §33.1 has no row for either, and the concern is this file's: the two maps on
 * `UiDocument` are the only place they appear, they share a `TypeId` value space (§5.5:366),
 * and a reader looking for what a document can declare finds both here.
 */
@Serializable
public data class DataModelDecl(

    /** The type's id, interchangeable with [EnumTypeDecl.id] — one value space (§5.5:366). */
    public val id: DataModelId,

    /** A Kotlin identifier, emitted as the generated data class's name (§16.6:1307). */
    public val name: String,

    /** The fields, in declaration order. */
    public val fields: List<FieldDecl> = emptyList(),
)

/**
 * One field of a [DataModelDecl]: the key a `Value.Obj` writes it under, and its type.
 *
 * The shape is not a design space, and the plan is explicit that it is not: §16.6:1307 emits
 * `data class User(val name: String, val age: Int)`, so the generator's output *is* the
 * specification of the record. `Nullable` becomes `?` and `ListOf` becomes `List<T>` by the
 * same rule.
 *
 * [name] is a [PropertyKey] and not a bare string because a data model's fields are written
 * as the keys of `Value.Obj(typeId, fields)` — the same rule §5.3 states for `Node.props`.
 * There is no optionality flag: [TypeRef.Nullable] already is that, and a second one would
 * be a second source of truth for the same fact.
 */
@Serializable
public data class FieldDecl(

    /** The key a value's `Obj` entry is written under, checked as an identifier. */
    public val name: PropertyKey,

    /** The declared type. */
    public val type: TypeRef,
)

/**
 * An enum type the document declares for itself, named by the same `TypeId` a data model uses.
 *
 * [id] is what `TypeRef.Enum(id)` refers to, so a property typed `TypeRef.Enum(TypeId("…"))`
 * and a declaration keyed `TypeId("…")` are the same fact said twice, in the two places the
 * plan puts them.
 */
@Serializable
public data class EnumTypeDecl(

    /** The type's id, interchangeable with [DataModelDecl.id] — one value space (§5.5:366). */
    public val id: TypeId,

    /** A Kotlin identifier, emitted as the generated enum's name (§16.6:1301). */
    public val name: String,

    /** The entries, in declaration order. */
    public val entries: List<EnumEntryDecl> = emptyList(),
)

/**
 * One entry of an [EnumTypeDecl], which is a name and nothing else.
 *
 * `Value.Enum(entry: String)` is the whole value a document can write, so an entry that
 * carried a value would have nowhere to put it: the payload would be a second fact about the
 * entry, and the value union has no field for it. §16.6:1301's `EnumEntrySpec.kotlin` symbol
 * belongs to the schema's `EnumTypeSpec` (§33.2), not here — a document enum emits a
 * generated enum whose entry name is its own symbol, and a plugin enum that needs per-entry
 * values registers a spec in `:engine:schema` instead.
 */
@Serializable
public data class EnumEntryDecl(

    /** The entry name, which is also the symbol the generated enum uses. */
    public val name: String,
)
