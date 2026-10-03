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

    /**
     * A modifier chain: one entry per line off a root receiver.
     *
     * Entries are expressions because one of them is spec-authored text: a case-selected
     * modifier fills its own pattern (`padding(horizontal = {horizontal})`) rather than a call.
     */
    public data class Chain(public val receiver: KtExpr, public val calls: List<KtExpr>) : KtExpr

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

/**
 * One chain entry written as a call: a modifier's function plus that call's own args.
 *
 * A subtype of [KtExpr] rather than a shape beside it, because a chain holds expressions and a
 * case-selected entry is spec text rather than a call this IR can name. [imported] is false for
 * a scope member's name, which resolves through a receiver rather than an FQN.
 */
public data class KtCall(
    public val function: KotlinSymbol,
    public val args: List<KtArg>,
    public val imported: Boolean = true,
) : KtExpr

/** One argument: named, or positional when the binding shortens to one. */
public data class KtArg(public val name: String?, public val value: KtExpr)

/**
 * A statement inside a body: an expression, a local declaration, or an assignment.
 *
 * The split follows Kotlin's grammar rather than convenience. `get()` is legal on a property
 * and illegal on a function-local, so the getter field belongs to
 * [KtDeclaration.Property] alone and [LocalProperty] has none. That absence is the shape.
 */
public sealed interface KtStmt {
    public data class Expr(public val expr: KtExpr) : KtStmt

    /** A function-local property. No getter: Kotlin has no `get()` on a local. */
    public data class LocalProperty(
        public val name: String,
        public val type: KtExpr?,
        public val mutable: Boolean,
        public val initializer: KtExpr?,
        public val delegate: KtExpr?,
    ) : KtStmt

    public data class Assign(public val target: KtExpr, public val value: KtExpr) : KtStmt
}

/**
 * A declaration: a screen or component, state and models as properties, generated models as
 * classes. The same three are legal at file scope and inside a class body, so one node each.
 */
public sealed interface KtDeclaration {
    public data class Function(
        public val name: String,
        public val annotations: List<KotlinSymbol>,
        public val params: List<KtParam>,
        public val body: List<KtStmt>,
    ) : KtDeclaration

    /**
     * A property, at file scope or as a class member.
     *
     * [initializer] and [delegate] are mutually exclusive in Kotlin, and a node carrying both
     * is an invariant breach the printer refuses rather than one it silently drops.
     */
    public data class Property(
        public val name: String,
        public val annotations: List<KotlinSymbol>,
        public val type: KtExpr?,
        public val mutable: Boolean,
        public val initializer: KtExpr?,
        public val delegate: KtExpr?,
        public val getter: KtExpr?,
    ) : KtDeclaration

    /** A class and its members; a nested class arrives as a member that is itself a class. */
    public data class Class(
        public val name: String,
        public val annotations: List<KotlinSymbol>,
        public val members: List<KtDeclaration>,
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
