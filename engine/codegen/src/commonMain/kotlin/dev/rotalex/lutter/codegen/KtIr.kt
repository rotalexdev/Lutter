package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.function.FunctionPrecedence

/**
 * The Kotlin subset the generator speaks: calls with named args, trailing lambdas,
 * member chains, literals and spec-authored snippets. Symbols, never text, so imports compute.
 *
 * Every node binds at a level of [KtBinding], and that level is what decides a parenthesis:
 * an operand binding looser than the expression around it is printed inside one.
 */
public sealed interface KtExpr {
    /** Escaped text from [LiteralPrinter]; [imports] is what the text needs (`dp`, `Color`). */
    public data class Literal(public val text: String, public val imports: List<KotlinSymbol> = emptyList()) : KtExpr

    /** A name resolved lexically: a parameter, a declared function, a lambda-free callee. */
    public data class Name(public val name: String) : KtExpr

    /** A member read on a receiver: `modifier`, a theme group, a pattern's qualifier. */
    public data class Member(
        public val receiver: KtExpr,
        public val name: String,
        public val safe: Boolean = false,
    ) : KtExpr

    /** A library reference: renders as the simple name and records the import. */
    public data class Ref(public val symbol: KtSymbolRef) : KtExpr

    /** A call: named args plus an optional trailing content lambda. */
    public data class Call(
        public val callee: KtExpr,
        public val args: List<KtArg>,
        public val trailing: Lambda? = null,
    ) : KtExpr

    /**
     * A call spelled by a `FunctionEmit` template: the pattern, the arguments that fill its
     * `{0}` placeholders, and what the whole thing binds as. Its own node because only the
     * pattern knows the binding, and neither a callee nor an argument does.
     */
    public data class PatternCall(
        public val pattern: String,
        public val args: List<KtExpr>,
        public val imports: List<KotlinSymbol> = emptyList(),
        public val precedence: FunctionPrecedence = FunctionPrecedence.Call,
    ) : KtExpr

    /**
     * A `by` delegate: [holder] plus the operators Kotlin resolves `by` through.
     *
     * Separate because the two name different imports, and which operators a `by` needs is the
     * delegate's type's business — a fact only the strategy that chose that type holds.
     */
    public data class Delegate(
        public val holder: KtExpr,
        public val operators: List<KotlinSymbol>,
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

    /** `a + b`, `flag && other`. The operator carries its own level, which is the parentheses. */
    public data class Binary(
        public val op: KtOp,
        public val left: KtExpr,
        public val right: KtExpr,
    ) : KtExpr

    /** `!flag`, `-amount`. A prefix binds tighter than every binary operator. */
    public data class Unary(public val op: KtOp, public val operand: KtExpr) : KtExpr

    /**
     * `if (c) a else b`.
     *
     * Both branches are mandatory because an `if` without one is a statement, and a statement in
     * a typed position is the implicit conversion §10.2 excludes.
     */
    public data class IfElse(
        public val cond: KtExpr,
        public val then: KtExpr,
        public val otherwise: KtExpr,
    ) : KtExpr

    /** `"Hello ${name}"`: literal text and interpolations, in order. */
    public data class StringTemplate(public val parts: List<KtTemplatePart>) : KtExpr

    /**
     * `base<args…>`: `List<T>`, `Map<K, V>`.
     *
     * Separate from [Nullable] because Kotlin's grammar has `?` postfix and `<>` infix, so the
     * two compose and reach `Map<String, Int?>`, which one node with a `nullable` flag cannot:
     * the flag would have to be repeated per argument (§4.6's D16).
     */
    public data class TypeApplication(public val base: KtExpr, public val args: List<KtExpr>) : KtExpr

    /** `inner?`, postfix. Two nodes rather than one `nullable` flag — [TypeApplication] says why. */
    public data class Nullable(public val inner: KtExpr) : KtExpr
}

/**
 * Kotlin's binding levels, loosest first, as the one ladder every precedence resolves into.
 *
 * Plain numbers because the two families that have to be compared declare different things —
 * [KtOp] names an operator, `FunctionPrecedence` names a template's binding — and converting
 * between them is the whole of the parenthesisation rule.
 *
 * `?:` binds tighter than `==` and `<` and looser than `+`, so [Elvis] sits above [Comparison].
 * An elvis beside a comparison therefore needs no parentheses, and reading the other way puts
 * `a ?: b == c` where Kotlin reads `(a ?: b) == c`.
 */
internal object KtBinding {
    const val Conditional: Int = 0
    const val Disjunction: Int = 1
    const val Conjunction: Int = 2
    const val Equality: Int = 3
    const val Comparison: Int = 4
    const val Elvis: Int = 5
    const val Additive: Int = 6
    const val Multiplicative: Int = 7
    const val Prefix: Int = 8
    const val Postfix: Int = 9
    const val Atom: Int = 10
}

/**
 * A Kotlin operator and the level it binds at, as one fact.
 *
 * An enum rather than a symbol plus a number because a level chosen per use site could put `&&`
 * at the additive level, and a misparenthesised expression is a file that compiles and is wrong.
 */
public enum class KtOp(
    public val symbol: String,
    internal val precedence: Int,
) {
    Add("+", KtBinding.Additive),
    Sub("-", KtBinding.Additive),
    Mul("*", KtBinding.Multiplicative),
    Eq("==", KtBinding.Equality),
    Neq("!=", KtBinding.Equality),
    Lt("<", KtBinding.Comparison),
    Le("<=", KtBinding.Comparison),
    Gt(">", KtBinding.Comparison),
    Ge(">=", KtBinding.Comparison),
    And("&&", KtBinding.Conjunction),
    Or("||", KtBinding.Disjunction),
    Not("!", KtBinding.Prefix),
    Neg("-", KtBinding.Prefix),
}

/**
 * One piece of a string template: literal text, or an expression interpolated into it.
 *
 * [Text] arrives escaped. Escape belongs to `LiteralPrinter` and the printer never escapes a
 * literal it is handed, so a `$` a document wrote reaches the output as `\$`.
 */
public sealed interface KtTemplatePart {
    /** Already-escaped text, written between the quotes as it stands. */
    public data class Text(public val text: String) : KtTemplatePart

    /** An expression, always written `${...}`: one spelling, and §16.3 cannot reflow a literal. */
    public data class Interpolation(public val expr: KtExpr) : KtTemplatePart
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
    /**
     * A function. [type] is null for the `Unit`-returning shape, which is why it is null rather
     * than absent: §12.1's `rememberHomeScreenState()` hands a screen its state and cannot be
     * written without one.
     */
    public data class Function(
        public val name: String,
        public val annotations: List<KotlinSymbol>,
        public val type: KtExpr?,
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
