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

    /**
     * `() -> HomeViewModel`: a function spelled as a type, which is what a container's map is
     * keyed by rather than something it calls.
     *
     * A type and not a lambda: Metro's ViewModel map holds `() -> ViewModel` builders, so the
     * declaration that fills it names that type in its return position. [params] exists even
     * though nothing emitted so far takes one — a builder that needed an argument would be a
     * different declaration, and an empty list says that instead of a comment claiming it.
     */
    public data class FunctionType(
        public val params: List<KtExpr>,
        public val returns: KtExpr,
    ) : KtExpr

    /**
     * `out ViewModel`: one type argument carrying its own variance.
     *
     * A node rather than a prefix inside [TypeApplication] because the variance belongs to the
     * argument, not to the application: `Map<KClass<out ViewModel>, () -> ViewModel>` is two
     * arguments, one projected and one not, and a list of expressions could not say which.
     */
    public data class TypeProjection(
        public val variance: TypeVariance,
        public val inner: KtExpr,
    ) : KtExpr

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
/**
 * Kotlin's two declaration-site variance markers, spelled where a type argument is projected.
 *
 * Only the two Kotlin has: a class's own declaration-site variance is a property of a type this
 * module does not declare, so it never needs a node.
 */
public enum class TypeVariance {
    /** `in T`. */
    In,

    /** `out T`. */
    Out,
}

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

    /**
     * `when (subject) { value -> … }`, the statement shape.
     *
     * A statement rather than an expression because an arm this generator writes holds screen
     * calls, and a `when` whose arms are `Unit` is not a value. The printer does not add an
     * `else` of its own: a non-exhaustive `when` over a sealed subject is legal in statement
     * position, so a missing arm would render nothing rather than fail to compile, and the
     * generator builds both lists from one page list so an arm cannot go missing.
     */
    public data class When(
        public val subject: KtExpr,
        public val branches: List<Branch>,
        public val otherwise: List<KtStmt>?,
    ) : KtStmt {

        /** One `value ->` arm. The subject is compared by equality, so the value is an operand. */
        public data class Branch(public val value: KtExpr, public val body: List<KtStmt>)
    }

    /**
     * `if (cond) { … }`, the statement shape: a call that renders only while a condition holds.
     *
     * A statement, not the [KtExpr.IfElse] expression, because a guarded call has no else to
     * answer with — the node simply does not render. Kotlin accepts the missing branch in
     * statement position, which is exactly where a slot child sits.
     */
    public data class If(public val cond: KtExpr, public val then: List<KtStmt>) : KtStmt
}

/**
 * A declaration: a screen or component, state and models as properties, generated models as
 * classes, the routes a navigator walks. Kotlin's own grammar, one node per production, because
 * a node reused in two positions Kotlin keeps distinct buys a flag and loses the spelling.
 */
public sealed interface KtDeclaration {
    /**
     * A function. [type] is null for the `Unit`-returning shape, which is why it is null rather
     * than absent: §12.1's `rememberHomeScreenState()` hands a screen its state and cannot be
     * written without one. [suspending] is §11.5's flag and nothing else decides it.
     */
    public data class Function(
        public val name: String,
        public val annotations: List<KotlinSymbol>,
        public val type: KtExpr?,
        public val params: List<KtParam>,
        public val body: List<KtStmt>,
        public val suspending: Boolean = false,
        /**
         * The opt-in markers the body composes behind, spelled `@OptIn(marker::class)` rather
         * than as the marker itself: an annotation this file applies propagates the
         * requirement to every caller of this function, while `@OptIn` keeps the opt-in local
         * to the body and leaves the callers' requirements untouched.
         */
        public val optIns: List<KotlinSymbol> = emptyList(),
    ) : KtDeclaration

    /**
     * A property, at file scope, as a class member or in a primary constructor.
     *
     * [initializer] and [delegate] are mutually exclusive in Kotlin, and a node carrying both
     * is an invariant breach the printer refuses rather than one it silently drops. [visibility]
     * is the modifier written in front of the keyword, null for the explicit `public` every
     * generated declaration carries but one: a constructor property the navigator keeps to
     * itself. [setter] is the body `set(value) { … }` a stored property answers with, null for
     * the delegate and getter shapes, which have no setter written out.
     */
    public data class Property(
        public val name: String,
        public val annotations: List<KotlinSymbol>,
        public val type: KtExpr?,
        public val mutable: Boolean,
        public val initializer: KtExpr?,
        public val delegate: KtExpr?,
        public val getter: KtExpr?,
        public val visibility: String? = null,
        public val setter: List<KtStmt>? = null,
    ) : KtDeclaration

    /**
     * A class and its members; a nested class arrives as a member that is itself a class.
     *
     * [constructorProperties] is written in the head rather than the body, which is the only
     * position a `private val x: T = default` is legal in. They are properties and not
     * parameters because the class body reads them as members.
     */
    public data class Class(
        public val name: String,
        public val annotations: List<KotlinSymbol>,
        public val members: List<KtDeclaration>,
        public val constructorProperties: List<Property> = emptyList(),
        /**
         * The one supertype the class extends, constructed with no arguments, or null for a
         * plain class. A single symbol rather than a list because it is the whole reason the
         * field exists: a state holder that a platform type owns declares which type owns it,
         * and nothing this module emits implements more than one.
         */
        public val supertype: KotlinSymbol? = null,
    ) : KtDeclaration

    /**
     * `sealed interface Route`.
     *
     * [sealed] rather than a modifier list because it is the only modifier an interface this
     * module emits can carry, and a list of strings would let a caller write one the printer
     * has to trust instead of spelling itself.
     */
    public data class Interface(
        public val name: String,
        public val annotations: List<KotlinSymbol>,
        public val sealed: Boolean,
        public val members: List<KtDeclaration>,
        /**
         * The interfaces this one extends, written after its name.
         *
         * A list rather than one supertype, unlike [Class]'s: an interface is what a caller
         * reads a capability off, and Navigation 3's route hierarchy is a `sealed interface`
         * that has to be a `NavKey` as well as the document's route set.
         */
        public val supertypes: List<KtExpr> = emptyList(),
    ) : KtDeclaration

    /**
     * `data object Home : Route`: a route entry, which is a name and nothing else.
     *
     * No members, because the only object generated is a marker a back stack holds and reads
     * back; [data] is what makes that comparison a value comparison.
     */
    public data class Object(
        public val name: String,
        public val annotations: List<KotlinSymbol>,
        public val data: Boolean,
        public val supertypes: List<KtExpr>,
        /**
         * What the object declares in its body, empty for a marker.
         *
         * A marker object needs no braces, which is what every object this module emitted until a
         * navigation strategy wanted named constants: a route a `NavHost` takes is a `String`, and
         * a `const val` per page is the one shape that keeps the call sites reading like names.
         */
        public val members: List<KtDeclaration> = emptyList(),
    ) : KtDeclaration
}

/**
 * One parameter, in a function or in a primary constructor.
 *
 * [type] is a [KtExpr] rather than a symbol because a declared type is a type expression and
 * `MutableList<Route>` is not a symbol any more than `List<String>` is.
 */
public data class KtParam(
    public val name: String,
    public val type: KtExpr,
    public val default: KtExpr?,
)

/** One file: its package plus declarations. Imports are computed, never listed. */
public data class KtFile(
    public val pkg: String,
    public val header: String?,
    public val declarations: List<KtDeclaration>,
)
