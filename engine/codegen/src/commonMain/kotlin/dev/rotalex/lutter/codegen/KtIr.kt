package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * The Kotlin subset the generator speaks: calls with named args, trailing lambdas,
 * member chains, literals and spec-authored snippets. Symbols, never text, so imports compute.
 *
 * Operators and conditionals arrive with expression emission; this stays the skeleton shape.
 */
public sealed interface KtExpr {
    /** Escaped text from [LiteralPrinter]; [imports] is what the text needs (`dp`, `Color`). */
    public data class Literal(public val text: String, public val imports: List<KotlinSymbol> = emptyList()) : KtExpr

    /** A name resolved lexically: a parameter, a declared function, a lambda-free callee. */
    public data class Name(public val name: String) : KtExpr

    /** A member read on a receiver: `modifier`, a theme group, a pattern's qualifier. */
    public data class Member(public val receiver: KtExpr, public val name: String) : KtExpr

    /** A library reference: renders as the simple name and records the import. */
    public data class Ref(public val symbol: KtSymbolRef) : KtExpr

    /** A call: named args plus an optional trailing content lambda. */
    public data class Call(
        public val callee: KtExpr,
        public val args: List<KtArg>,
        public val trailing: Lambda? = null,
    ) : KtExpr

    /** A modifier chain: one dotted call per line off a root receiver. */
    public data class Chain(public val receiver: KtExpr, public val calls: List<KtCall>) : KtExpr

    /** A content lambda: slot children, in order. Receivers arrive with scoped slots. */
    public data class Lambda(public val params: List<String>, public val body: List<KtStmt>) : KtExpr

    /**
     * Binding-authored expression text with its declared imports (a `ValueEmit` pattern
     * after substitution). Spec-trusted, like a symbol; the printer records [symbols].
     */
    public data class Snippet(public val text: String, public val symbols: List<KotlinSymbol>) : KtExpr
}

/** A library symbol plus an optional member: `MaterialTheme` alone, or `Arrangement.spacedBy`. */
public data class KtSymbolRef(
    public val symbol: KotlinSymbol,
    public val member: String? = null,
)

/** One chained call in a [KtExpr.Chain]: its function plus the call's own args. */
public data class KtCall(public val function: KotlinSymbol, public val args: List<KtArg>)

/** One argument: named, or positional when the binding shortens to one. */
public data class KtArg(public val name: String?, public val value: KtExpr)

/** A statement: today only an expression, since actions emit nothing yet. */
public sealed interface KtStmt {
    public data class Expr(public val expr: KtExpr) : KtStmt
}

/** A top-level declaration: today only a composable function; models arrive with data. */
public sealed interface KtDeclaration {
    public data class Function(
        public val name: String,
        public val annotations: List<KotlinSymbol>,
        public val params: List<KtParam>,
        public val body: List<KtStmt>,
    ) : KtDeclaration
}

/** One parameter: its type is a symbol so the import computes from the signature too. */
public data class KtParam(
    public val name: String,
    public val type: KotlinSymbol,
    public val default: KtExpr?,
)

/** One file: its package plus declarations. Imports are computed, never listed. */
public data class KtFile(
    public val pkg: String,
    public val header: String?,
    public val declarations: List<KtDeclaration>,
)
