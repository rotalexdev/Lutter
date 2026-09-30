package dev.rotalex.lutter.model.expr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The two operators that take one operand, and the only two.
 *
 * PLAN §10.1 declares `public enum class UnaryOp { Not, Neg }` and §10.2's included list
 * names exactly what they are: `&& || !` and unary minus. Two entries, both persisted, both
 * spelled.
 *
 * ### Why an enum and not a symbol
 *
 * A document could have said `{"op":"!"}`. The wire form is `"not"` instead, for the same
 * reason [Value] and [TypeRef] use words rather than a spelling of themselves: a keyword is
 * unambiguous in any reader, survives a document being read by a person, and does not change
 * if §10.8's textual syntax later decides to print `!` as something else. §10.8 says the AST
 * is the source of truth and text is a view; the wire form is closer to the text than the
 * view is, and this is the layer where that difference is cheapest to keep.
 *
 * ### Why the class is `@Serializable` and not only the entries
 *
 * Two reasons, and the first is the one the kotlinx.serialization docs state outright:
 * **`@SerialName` on an enum entry is honoured only when the enum class itself is
 * `@Serializable`.** Enums are serializable out of the box without it, which is exactly what
 * makes the omission quiet — the class still serializes, the entry still writes a string, and
 * the string is the *constant name*. For this enum that would look almost right: `Not` and
 * `Neg` are already the words a person would guess. Every `BinaryOp` below would be wrong in
 * precisely the way nobody looks for, since all eleven tags differ from their constant names
 * by nothing but the case of the first letter.
 *
 * The second reason is on this project's own targets: the same docs note that on Kotlin/JS
 * and Kotlin/Native the class-level annotation is required to use the enum as a *root*
 * object — and the Wasm target is JS, and `ExprTest`'s per-operator assertions encode an
 * operator on its own, which is exactly that.
 *
 * The annotation is load-bearing, and this comment is here so that nobody tidies it away as
 * redundant.
 *
 * ### What is not here, and why there is no unary plus
 *
 * No unary `+`. It is a no-op in Kotlin, so it would be a variant that generates an
 * expression with no effect and an AST node no document has a reason to contain. Two entries
 * is the honest size of this set.
 */
@Serializable
public enum class UnaryOp {

    /** Logical negation, `!`. Short-circuit is not a concern: there is nothing to skip. */
    @SerialName("not")
    Not,

    /** Arithmetic negation, unary minus. */
    @SerialName("neg")
    Neg,
}

/**
 * The eleven binary operators, and the two that are deliberately missing.
 *
 * PLAN §10.1 writes the list with a trailing comment: `// no Div/Mod in MVP (D10)`. That
 * comment is the specification, and it is a decision rather than an omission — so it gets its
 * own section below, because a reader who finds this file without that context is exactly the
 * reader who "fixes" it.
 *
 * ### D10: there is no `Div` and there is no `Mod`, and adding either is a regression
 *
 * This is the one place in the expression system where the model deliberately refuses to be
 * complete, and the reason is a parity bug the omission *prevents*.
 *
 * `/` and `%` on integers do not mean the same thing everywhere, and the two things this
 * engine runs are not the same thing. The interpreter and the generated Kotlin must produce
 * identical results for identical documents — that is the whole point of ADR-008's "closed
 * AST and operator set" and of the expression corpus of §10.7, which evaluates every case
 * twice and compares. An integer division is where that comparison breaks first:
 *
 *  * **Division by zero is an exception in Kotlin** and a choice a document author has to
 *    make. `a / 0` throws in generated code, and an interpreter that has to not throw has to
 *    answer *something* — and whatever it answers is a value the generated code cannot
 *    produce.
 *  * **Overflow behaviour is a second-order version of the same problem.** Kotlin's `/` on
 *    `Int.MIN_VALUE / -1` overflows, and that is consistent on both sides; the trap is that
 *    making the interpreter match it requires the interpreter to *also* overflow, and the
 *    cheapest-looking implementation returns `Null` instead, which is now a divergence that
 *    only shows up in production.
 *  * **Floating-point division is a third.** `Double` division agrees across the JVM and
 *    Wasm, which is what makes it tempting to add `Div` "for doubles only" — and then the
 *    operator set has a rule about operand types that has to be enforced in the analyzer, in
 *    the interpreter and in codegen, instead of a property the type system gives for free.
 *
 * D10 in PLAN's own decision table reads *"Integer division / overflow semantic parity"* and
 * its resolution is *"`/` and `%` are **not** in MVP operators"*, pointing at §10.6's hazard
 * table, whose row is *"`Int` division/modulo by zero — `/`, `%` not offered in MVP (D10)"*.
 * §10.2 lists `/` and `%` under **Excluded (postponed)** alongside lambdas and regexes.
 * Three sections say the same thing, which is the level of repetition a decision this
 * consequential deserves.
 *
 * **So: if you are here to add `Div` because an arithmetic expression obviously needs it,
 * read the above first.** The correct answer is `core.coalesce`, an explicit
 * `If`, or a `FunctionRegistry` entry in `:engine:builtins` whose implementation is *total*
 * — §10.6 requires every function to be total, and a total function is one that has already
 * made the division-by-zero decision somewhere a document cannot see. That is the extension
 * point the design has for this, and the corpus of §10.7 is where a new operator has to prove
 * itself against both backends before it is allowed to exist at all.
 *
 * ### `And` and `Or` short-circuit, and that is a promise about both backends
 *
 * §10.6 lists evaluation order and laziness as a hazard *policy*, not an accident: `&&`,
 * `||` and `if` are short-circuit in the interpreter and in the generated Kotlin. Nothing in
 * this enum enforces that — [Expr.Binary] takes two sub-expressions and does not require
 * anything of the right-hand one — so the two evaluators have to agree by construction, and
 * this file is where the third reader of that promise would look.
 *
 * ### Why the names are words and not `==`, `!=`, `<=`
 *
 * The same argument as [UnaryOp], and it is stronger here: `<` and `<=` are one character
 * apart and a document diff that cannot tell them apart is a document diff nobody reads. The
 * spellings are lower case and unabbreviated where the abbreviation is unambiguous (`add`,
 * `and`), and the two operators that genuinely have no word — `Eq` and `Neq` — are named
 * after the operation rather than after a character. `Le` and `Ge` follow `Lt` and `Gt`,
 * which is PLAN's own ordering in §10.1 and is read most naturally as "less, less-or-equal".
 *
 * ### The class is `@Serializable`, and that is not decoration
 *
 * Both reasons are in [UnaryOp]'s KDoc and both apply here. The first one bites harder: an
 * enum serializes without the annotation and writes its constant names, so the omission is
 * silent — and **all eleven** of these entries differ from their constant names by nothing but
 * the case of the first letter. A lowercase wire form produced by accident (an enum that is not
 * `@Serializable`, an entry whose `@SerialName` is deleted) is byte-for-byte indistinguishable
 * from the correct one on this engine, and the two only diverge when a document written by the
 * other engine is read.
 */
@Serializable
public enum class BinaryOp {

    /**
     * Addition, `+`.
     *
     * One operator, not two: §10.2 lists `+ - *` and names no separate string-concatenation
     * operator, so whatever `+` means over `Str` is a single question rather than two.
     *
     * What it *does* mean over a `Str` is not decided here, and this comment is the honest
     * boundary: the model holds trees, not types, and §10.4's checker is what has to say
     * whether `+` accepts two strings, one string and a number, or neither. The only
     * concatenation §10.5 pins down is [Expr.Template]'s, and it pins it to canonical
     * `toDisplayString` rather than to this operator.
     */
    @SerialName("add")
    Add,

    /** Subtraction, `-`. */
    @SerialName("sub")
    Sub,

    /** Multiplication, `*`. */
    @SerialName("mul")
    Mul,

    /**
     * Equality, `==`.
     *
     * §10.6 permits `==` on floats and rules that NaN cannot be a literal, so `NaN` reaches a
     * document only through arithmetic and then follows Kotlin — which is the honest answer,
     * since both backends are Kotlin.
     */
    @SerialName("eq")
    Eq,

    /** Inequality, `!=`. */
    @SerialName("neq")
    Neq,

    /**
     * Less than, `<`.
     *
     * §10.6 rules that string comparison is limited to `==`/`!=` and the `str.*` functions, so
     * `<` on strings is a diagnostic rather than a locale-dependent answer. The model does not
     * refuse to build the tree: the tree is a shape and the rule is about types.
     */
    @SerialName("lt")
    Lt,

    /** Less than or equal, `<=`. */
    @SerialName("le")
    Le,

    /** Greater than, `>`. */
    @SerialName("gt")
    Gt,

    /** Greater than or equal, `>=`. */
    @SerialName("ge")
    Ge,

    /** Conjunction, `&&`. Short-circuits in both backends; see the interface KDoc. */
    @SerialName("and")
    And,

    /** Disjunction, `||`. Short-circuits in both backends; see the interface KDoc. */
    @SerialName("or")
    Or,
}
