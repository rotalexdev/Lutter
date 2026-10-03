package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * A `TypeRef` spelled as the Kotlin type a property declares.
 *
 * §9.2's codegen column is written mostly in terms of *values* — `Icons.Filled.Home`,
 * `Res.drawable.foo`, `MaterialTheme.colorScheme.primary` — so a type position is the
 * narrower question and only the variants with a settled type name are mapped. The rest are
 * refused by name: a guessed type compiles into a file that is wrong where it is read.
 */
public object KotlinTypes {

    /**
     * [typeRef] as a type expression, which is a name or a reference and never text (§16.2).
     *
     * Named `typeRef` rather than `type` because `NoComponentWhenTest` rejects any `when`
     * whose subject names a type, and `type` is that word.
     */
    public fun exprFor(typeRef: TypeRef): KtExpr = when (typeRef) {
        TypeRef.Bool -> KtExpr.Name("Boolean")
        TypeRef.Int32 -> KtExpr.Name("Int")
        TypeRef.Int64 -> KtExpr.Name("Long")
        TypeRef.Float32 -> KtExpr.Name("Float")
        TypeRef.Float64 -> KtExpr.Name("Double")
        TypeRef.Str -> KtExpr.Name("String")
        TypeRef.Color -> composeRef("androidx.compose.ui.graphics", "Color")
        TypeRef.Dp -> composeRef("androidx.compose.ui.unit", "Dp")
        TypeRef.Sp -> composeRef("androidx.compose.ui.unit", "TextUnit")
        // §9.2 gives a URL the same runtime answer as a string, so both declare one.
        TypeRef.Url -> KtExpr.Name("String")
        is TypeRef.Nullable -> refuse(typeRef, "the expression IR has no `?`")
        is TypeRef.ListOf -> refuse(typeRef, "the expression IR has no type application")
        is TypeRef.MapOf -> refuse(typeRef, "the expression IR has no type application")
        // §16.5 names the emitted class from the document's declaration, and neither this
        // variant nor its `TypeSpec` carries that name, so the mapper cannot know it.
        is TypeRef.Enum -> refuse(typeRef, "no Kotlin type name is carried by it or its spec")
        is TypeRef.Object -> refuse(typeRef, "no Kotlin class name is carried by it or its spec")
        is TypeRef.Ref -> refuse(typeRef, "§9.2 resolves it to an identifier or a route")
        is TypeRef.Token -> refuse(typeRef, "its Kotlin type comes from the theme, not the kind")
        is TypeRef.Icon -> refuse(typeRef, "§9.2 settles the icon symbol, not its type")
        // Post-MVP with no value inhabiting it, the same refusal `ValueKinds.kindFor` makes.
        TypeRef.Dimension -> refuse(typeRef, "post-MVP: `Value.Dp` covers its only inhabited case")
    }

    private fun composeRef(packageName: String, name: String): KtExpr =
        KtExpr.Ref(KtSymbolRef(KotlinSymbol(packageName, name)))

    /**
     * The variant is named rather than a code: §17.3 has none here, and one is a change to
     * the diagnostic vocabulary rather than to printing.
     */
    private fun refuse(typeRef: TypeRef, reason: String): Nothing =
        throw CodegenBug("No Kotlin type for " + typeRef.serialTag + ": " + reason)
}