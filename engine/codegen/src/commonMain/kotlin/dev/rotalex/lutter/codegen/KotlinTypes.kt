package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * A [TypeRef] as the Kotlin a property declares: either the expression that spells it, or the
 * reason there is none.
 *
 * A result rather than a `KtExpr`, because §16.7 makes an unmappable type a diagnostic and not a
 * throw — the type arrives from the user's document, so refusing it is a finding to report rather
 * than an invariant breach. [Spelled] and [Unspelled] are the whole of it and the split is
 * exhaustive over [TypeRef], so a variant added to the model fails to compile here.
 */
public sealed interface TypeSpelling {

    /** [expr] is a name or a reference and never text (§16.2). */
    public data class Spelled(public val expr: KtExpr) : TypeSpelling

    /** [reason] names what has to change before [TypeRef.serialTag] can be written as Kotlin. */
    public data class Unspelled(public val reason: String) : TypeSpelling
}

/**
 * A `TypeRef` spelled as the Kotlin type a property declares.
 *
 * §9.2's codegen column is written mostly in terms of *values* — `Icons.Filled.Home`,
 * `Res.drawable.foo`, `MaterialTheme.colorScheme.primary` — so a type position is the
 * narrower question and only the variants with a settled type name are mapped. The rest are
 * reported by name: a guessed type compiles into a file that is wrong where it is read.
 *
 * The composites map as IR rather than as text, which is what they need: `Map<String, Int?>`
 * is `?` inside `<>`, and §4.6's D16 makes those two nodes precisely because one node with a
 * nullable flag cannot reach it.
 */
public object KotlinTypes {

    /**
     * [typeRef] as a type expression.
     *
     * Named `typeRef` rather than `type` because `NoComponentWhenTest` rejects any `when`
     * whose subject names a type, and `type` is that word.
     */
    public fun spellingFor(typeRef: TypeRef): TypeSpelling = when (typeRef) {
        TypeRef.Bool -> spelled("Boolean")
        TypeRef.Int32 -> spelled("Int")
        TypeRef.Int64 -> spelled("Long")
        TypeRef.Float32 -> spelled("Float")
        TypeRef.Float64 -> spelled("Double")
        TypeRef.Str -> spelled("String")
        TypeRef.Color -> TypeSpelling.Spelled(composeRef("androidx.compose.ui.graphics", "Color"))
        TypeRef.Dp -> TypeSpelling.Spelled(composeRef("androidx.compose.ui.unit", "Dp"))
        TypeRef.Sp -> TypeSpelling.Spelled(composeRef("androidx.compose.ui.unit", "TextUnit"))
        // §9.2 gives a URL the same runtime answer as a string, so both declare one.
        TypeRef.Url -> spelled("String")
        // The inner decides, so `Nullable` of an unmappable type stays unmappable and says so.
        is TypeRef.Nullable -> wrapped(typeRef.inner) { inner -> KtExpr.Nullable(inner) }
        is TypeRef.ListOf -> wrapped(typeRef.element) { inner -> applied("List", inner) }
        // The key is hardcoded: §9.1 declares this type with `String` keys and carries no other.
        is TypeRef.MapOf -> wrapped(typeRef.value) { inner ->
            applied("Map", KtExpr.Name("String"), inner)
        }
        // §16.6 names the emitted class from the document's declaration, and neither this
        // variant nor its `TypeSpec` carries that name, so the mapper cannot know it.
        is TypeRef.Enum -> unspelled("no Kotlin type name is carried by it or its spec")
        is TypeRef.Object -> unspelled("no Kotlin class name is carried by it or its spec")
        is TypeRef.Ref -> unspelled("§9.2 resolves it to an identifier or a route")
        is TypeRef.Token -> unspelled("its Kotlin type comes from the theme, not the kind")
        is TypeRef.Icon -> unspelled("§9.2 settles the icon symbol, not its type")
        // Post-MVP with no value inhabiting it, the same refusal `ValueKinds.kindFor` makes.
        TypeRef.Dimension -> unspelled("post-MVP: `Value.Dp` covers its only inhabited case")
    }

    private fun spelled(name: String): TypeSpelling = TypeSpelling.Spelled(KtExpr.Name(name))

    private fun unspelled(reason: String): TypeSpelling = TypeSpelling.Unspelled(reason)

    private fun composeRef(packageName: String, name: String): KtExpr =
        KtExpr.Ref(KtSymbolRef(KotlinSymbol(packageName, name)))

    /** `base<args…>` over default-imported collection names, so no import is written. */
    private fun applied(base: String, vararg args: KtExpr): KtExpr =
        KtExpr.TypeApplication(KtExpr.Name(base), args.toList())

    /**
     * A composite built by [build] over [inner]'s spelling, or the whole thing unspelled.
     *
     * The inner's own reason travels out rather than a new one: "no Kotlin type name is carried
     * by it or its spec" says what to fix, where a `List` repeating that it is a type application
     * names an IR gap that closed with D16.
     */
    private fun wrapped(inner: TypeRef, build: (KtExpr) -> KtExpr): TypeSpelling =
        when (val spelling = spellingFor(inner)) {
            is TypeSpelling.Spelled -> TypeSpelling.Spelled(build(spelling.expr))
            is TypeSpelling.Unspelled -> spelling
        }
}