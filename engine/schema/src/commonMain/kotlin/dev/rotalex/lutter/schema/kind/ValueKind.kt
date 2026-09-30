package dev.rotalex.lutter.schema.kind

import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.BoolValueKind
import dev.rotalex.lutter.model.value.ColorValueKind
import dev.rotalex.lutter.model.value.DpValueKind
import dev.rotalex.lutter.model.value.EnumEntryValueKind
import dev.rotalex.lutter.model.value.Float32ValueKind
import dev.rotalex.lutter.model.value.Float64ValueKind
import dev.rotalex.lutter.model.value.Int32ValueKind
import dev.rotalex.lutter.model.value.Int64ValueKind
import dev.rotalex.lutter.model.value.SpValueKind
import dev.rotalex.lutter.model.value.StringValueKind
import dev.rotalex.lutter.model.value.UrlValueKind
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.model.value.ValueKind

/** `Nullable(inner)` → the inner value, or null. Null is the one value needing no kind. */
public class NullableValueKind<T>(public val inner: ValueKind<T>) : ValueKind<T?> {
    override val type: TypeRef = TypeRef.Nullable(inner.type)

    override fun accepts(value: Value): Boolean = value is Value.Null || inner.accepts(value)

    override fun decode(value: Value): T? = if (value is Value.Null) null else inner.decode(value)
}

/** `ListOf(element)` → the decoded items. Every item must satisfy the element kind. */
public class ListValueKind<T>(public val element: ValueKind<T>) : ValueKind<List<T>> {
    override val type: TypeRef = TypeRef.ListOf(element.type)

    override fun accepts(value: Value): Boolean =
        value is Value.ListOf && value.items.all(element::accepts)

    override fun decode(value: Value): List<T> =
        (value as? Value.ListOf)?.items?.map(element::decode) ?: wrongKind(this, value)
}

/** `MapOf(value)` → the decoded entries. Only entries are checked; see `Value.MapOf`. */
public class MapValueKind<T>(public val entry: ValueKind<T>) : ValueKind<Map<PropertyKey, T>> {
    override val type: TypeRef = TypeRef.MapOf(entry.type)

    override fun accepts(value: Value): Boolean =
        value is Value.MapOf && value.entries.values.all(entry::accepts)

    override fun decode(value: Value): Map<PropertyKey, T> =
        (value as? Value.MapOf)?.entries?.mapValues { (_, item) -> entry.decode(item) }
            ?: wrongKind(this, value)
}

/**
 * `Object(id)` → the fields. The id must match: unlike an enum entry, the value names its
 * own type, so agreement is checkable without a registry.
 */
public class ObjectValueKind(public val id: TypeId) : ValueKind<Map<PropertyKey, Value>> {
    override val type: TypeRef = TypeRef.Object(id)

    override fun accepts(value: Value): Boolean = value is Value.Obj && value.typeId == id

    override fun decode(value: Value): Map<PropertyKey, Value> =
        (value as? Value.Obj)?.takeIf { it.typeId == id }?.fields ?: wrongKind(this, value)
}

/** `Ref(kind)` → the reference itself. The kinds must agree; analysis resolves the target. */
public class ReferenceValueKind(public val kind: RefKind) : ValueKind<Value.Ref> {
    override val type: TypeRef = TypeRef.Ref(kind)

    override fun accepts(value: Value): Boolean = value is Value.Ref && value.kind == kind

    override fun decode(value: Value): Value.Ref =
        (value as? Value.Ref)?.takeIf { it.kind == kind } ?: wrongKind(this, value)
}

/** `Token(kind)` → the token itself. Same agreement rule as [ReferenceValueKind]. */
public class ThemeTokenValueKind(public val kind: TokenKind) : ValueKind<Value.Token> {
    override val type: TypeRef = TypeRef.Token(kind)

    override fun accepts(value: Value): Boolean = value is Value.Token && value.kind == kind

    override fun decode(value: Value): Value.Token =
        (value as? Value.Token)?.takeIf { it.kind == kind } ?: wrongKind(this, value)
}

/** `Icon(set)` → the icon itself. A null set accepts any; a named one only its own. */
public class IconValueKind(public val set: String?) : ValueKind<Value.Icon> {
    override val type: TypeRef = TypeRef.Icon(set)

    override fun accepts(value: Value): Boolean =
        value is Value.Icon && (set == null || value.set == set)

    override fun decode(value: Value): Value.Icon =
        (value as? Value.Icon)?.takeIf { set == null || it.set == set } ?: wrongKind(this, value)
}

/**
 * The schema side of the kind table: one kind for every inhabitable [TypeRef].
 *
 * The eleven schema-free kinds are reused from `:engine:model`, not redeclared. `Dimension`
 * has no kind because no value inhabits it; [kindFor] throws naming it.
 */
public object ValueKinds {
    /**
     * The kind for [typeRef]. Throws for `Dimension`, which is post-MVP with no inhabitant —
     * returning a wrong kind would be worse than refusing.
     */
    @Suppress("UNCHECKED_CAST")
    public fun kindFor(typeRef: TypeRef): ValueKind<*> = when (typeRef) {
        TypeRef.Bool -> BoolValueKind
        TypeRef.Int32 -> Int32ValueKind
        TypeRef.Int64 -> Int64ValueKind
        TypeRef.Float32 -> Float32ValueKind
        TypeRef.Float64 -> Float64ValueKind
        TypeRef.Str -> StringValueKind
        TypeRef.Color -> ColorValueKind
        TypeRef.Dp -> DpValueKind
        TypeRef.Sp -> SpValueKind
        TypeRef.Url -> UrlValueKind
        is TypeRef.Nullable -> NullableValueKind(kindFor(typeRef.inner) as ValueKind<Any?>)
        is TypeRef.ListOf -> ListValueKind(kindFor(typeRef.element) as ValueKind<Any?>)
        is TypeRef.MapOf -> MapValueKind(kindFor(typeRef.value) as ValueKind<Any?>)
        is TypeRef.Enum -> EnumEntryValueKind(typeRef)
        is TypeRef.Object -> ObjectValueKind(typeRef.id)
        is TypeRef.Ref -> ReferenceValueKind(typeRef.kind)
        is TypeRef.Token -> ThemeTokenValueKind(typeRef.kind)
        is TypeRef.Icon -> IconValueKind(typeRef.set)
        TypeRef.Dimension -> throw IllegalArgumentException(
            "'dimension' has no value kind: only its Dp case is expressible and Value.Dp covers it",
        )
    }
}

/** Refusal message in the model's shape: expected tag, offending variant, no payload. */
private fun wrongKind(kind: ValueKind<*>, value: Value): Nothing =
    throw IllegalArgumentException("'${kind.type.serialTag}' expected, got ${value::class.simpleName}")
