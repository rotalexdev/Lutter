package dev.rotalex.lutter.model.expr

import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.model.value.canonicalText

/**
 * §10.8's text view: the canonical spelling of an expression.
 *
 * The AST is the source of truth and the text is a view, so the printer has exactly one job
 * with exactly one answer: whatever it emits, [ExprParser] must read back to a tree equal to
 * the one it was handed. Every rule below exists in service of that property, and each is
 * stated where it is applied rather than in a preamble a reader has to hold all at once.
 *
 * The form, in one place:
 *
 * - Literals spell Kotlin-like: `"text"` with `\\`, `\"`, `\$`, `\n`, `\t`, `\r` escapes, `42`,
 *   `42L`, `1.5`, `1.5f`, `true`, `false`, `null`. A float always carries a decimal point
 *   (`16.0`, `16.0f`) because `16` is an `Int32` and the two must not collide, and a float
 *   the canonical form cannot spell bit-exactly is refused rather than silently rounded.
 * - A reference spells its binding kind: `state.s_count`, `param.title`, `event.value`,
 *   `item.product`. A bare name is not a reference — which variant a name resolves to is a
 *   question about scope, and a text view that had to guess would not round-trip.
 * - A member access spells `.` and `?.`: `state.s_user.name`, `state.s_user?.name`.
 * - A call spells its [FunctionId] in full: `list.isNotEmpty(state.s_items)`,
 *   `core.coalesce(a, b)`. There is no method-call sugar — `items.isNotEmpty()` would need
 *   the function registry to decide which namespace owns a short name, and the parser lives
 *   in a module that does not see the registry. Sugar is a parse-time convenience a caller
 *   with a registry can add later; the canonical form does not need it.
 * - `if` spells with keywords and parenthesized compound branches:
 *   `if (state.s_c) then (state.s_a) else (if (…) then … else …)` — the parens are what
 *   keeps a nested `if`'s `else` from being read by the wrong parent.
 * - An operand that is itself a compound — [Expr.Binary], [Expr.Unary] or [Expr.If] — is
 *   wrapped in parens wherever an operand is expected, so the parser needs no precedence
 *   table: the structure is in the text. Leaves are never wrapped.
 * - A [Expr.Template] spells every part inside `${…}`, literals included:
 *   `"${"Hello "}${state.s_user.name}"`. The uniform rule is what makes an all-literal
 *   template readable back as a template rather than collapsing into one `Const`.
 *
 * What the printer refuses, it refuses loudly: a [Value] variant the grammar does not spell
 * (`Color`, `Dp`, a resource ref…), a member or reference name that is not an identifier,
 * and a float whose canonical digits do not read back bit-exactly. The AST in the document
 * is the stored form and loses nothing; only the view narrows.
 */
public object ExprPrinter {

    /** The canonical text of [expr]. @throws IllegalArgumentException when it cannot spell. */
    public fun print(expr: Expr): String {
        val out = StringBuilder()
        write(expr, out)
        return out.toString()
    }

    private fun write(expr: Expr, out: StringBuilder) {
        when (expr) {
            is Expr.Const -> writeValue(expr.value, out)
            is Expr.Ref -> writeRef(expr.target, out)
            is Expr.Member -> writeMember(expr, out)
            is Expr.Call -> writeCall(expr, out)
            is Expr.Unary -> writeUnary(expr, out)
            is Expr.Binary -> {
                writeOperand(expr.left, out)
                out.append(when (expr.op) {
                    BinaryOp.Add -> " + "
                    BinaryOp.Sub -> " - "
                    BinaryOp.Mul -> " * "
                    BinaryOp.Eq -> " == "
                    BinaryOp.Neq -> " != "
                    BinaryOp.Lt -> " < "
                    BinaryOp.Le -> " <= "
                    BinaryOp.Gt -> " > "
                    BinaryOp.Ge -> " >= "
                    BinaryOp.And -> " && "
                    BinaryOp.Or -> " || "
                })
                writeOperand(expr.right, out)
            }
            is Expr.If -> {
                out.append("if (")
                write(expr.cond, out)
                out.append(") then ")
                writeOperand(expr.then, out)
                out.append(" else ")
                writeOperand(expr.otherwise, out)
            }
            is Expr.ListLiteral -> {
                out.append('[')
                expr.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(", ")
                    writeOperand(item, out)
                }
                out.append(']')
            }
            is Expr.Template -> {
                out.append('"')
                for (part in expr.parts) {
                    out.append("\${")
                    write(part, out)
                    out.append('}')
                }
                out.append('"')
            }
        }
    }

    /**
     * An operand in a position that expects one: compounds wrap, leaves do not. The rule is
     * what lets the parser read the text back without a precedence table — see the class
     * KDoc.
     */
    private fun writeOperand(expr: Expr, out: StringBuilder) {
        if (expr is Expr.Binary || expr is Expr.Unary || expr is Expr.If) {
            out.append('(')
            write(expr, out)
            out.append(')')
        } else {
            write(expr, out)
        }
    }

    private fun writeValue(value: Value, out: StringBuilder) {
        when (value) {
            is Value.Null -> out.append("null")
            is Value.Bool -> out.append(if (value.v) "true" else "false")
            is Value.Int32 -> out.append(value.v.toString())
            is Value.Int64 -> out.append(value.v.toString()).append('L')
            is Value.Float32 -> {
                out.append(spellFloat(value.v.toDouble(), value.v.toDouble()))
                out.append('f')
            }
            is Value.Float64 -> out.append(spellFloat(value.v, value.v))
            is Value.Str -> writeString(value.v, out)
            is Value.Dp -> writeUnit(value.v, ".dp", out)
            is Value.Sp -> writeUnit(value.v, ".sp", out)
            else -> throw IllegalArgumentException(
                "The printer cannot spell a ${value::class.simpleName} literal: " +
                    "the text view covers the primitive literals and the unit-suffixed numbers " +
                    "(`16.0.dp`, `12.0.sp`), and the AST is the stored form",
            )
        }
    }

    /**
     * The canonical digits of a float, guaranteed to read back bit-exactly.
     *
     * `canonicalText` is the document's own number text, and a float always carries a decimal
     * point in this view because an integral spelling would parse as an `Int32`. The read-back
     * guard is the honest edge of the format: the canonical form keeps four fractional
     * digits, so a value below half of that scale rounds away — `1.0e-5` spells as `0`, which
     * would not be the value it claims to be, and the printer refuses rather than guess. A
     * float whose nearest representable neighbour is what the digits name (`839.0001` is not
     * a `Float`) refuses for the same reason.
     */
    private fun spellFloat(canonical: Double, original: Double): String {
        val text = canonicalText(canonical)
        val withPoint = if ('.' in text) text else "$text.0"
        val readBack = withPoint.toDouble()
        require(readBack == original) {
            "The printer cannot spell $original: the canonical form is '$withPoint', " +
                "which is a different value"
        }
        return withPoint
    }

    /**
     * A `Dp` or `Sp` number and its unit: `16.0.dp`, `12.5.sp`.
     *
     * The read-back is a `Float`, not a `Double`: the document stores these as a `Float`, so
     * `0.1.dp` is the exact value `0.1f` even though `0.1` as a `Double` is a different number.
     * The decimal point is not required by the grammar here — the unit already says what the
     * number is — but it is kept so the text matches what generated Kotlin spells.
     */
    private fun writeUnit(value: Float, unit: String, out: StringBuilder) {
        val text = canonicalText(value.toDouble())
        val withPoint = if ('.' in text) text else "$text.0"
        require(withPoint.toFloat() == value) {
            "The printer cannot spell $value$unit: the canonical form is '$withPoint', " +
                "which is a different value"
        }
        out.append(withPoint).append(unit)
    }

    private fun writeString(text: String, out: StringBuilder) {
        out.append('"')
        for (ch in text) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '$' -> out.append("\\$")
                '\n' -> out.append("\\n")
                '\t' -> out.append("\\t")
                '\r' -> out.append("\\r")
                else -> out.append(ch)
            }
        }
        out.append('"')
    }

    private fun writeRef(target: RefTarget, out: StringBuilder) {
        when (target) {
            is RefTarget.State -> out.append("state.").append(target.id.value)
            is RefTarget.Param -> out.append("param.").append(target.name.value)
            is RefTarget.EventArg -> writeName("event", target.name, out)
            is RefTarget.Item -> writeName("item", target.name, out)
        }
    }

    /**
     * A binding name must be an identifier, or the text would not read back. `EventArg` and
     * `Item` names are unbounded `String`s in the model — the scope lookup downstream is what
     * validates them — but a name the grammar cannot spell is a name this view refuses, with
     * the AST untouched.
     */
    private fun writeName(kind: String, name: String, out: StringBuilder) {
        require(name.isIdentifier()) {
            "The printer cannot spell $kind binding '$name': a binding name must be an identifier"
        }
        out.append(kind).append('.').append(name)
    }

    private fun writeMember(expr: Expr.Member, out: StringBuilder) {
        // A numeric literal receiver carries parens: `5.name` would hand its dot to the
        // number scan, and the parens are what keep the member readable at all.
        if (expr.receiver is Expr.Const && expr.receiver.value.isNumeric) {
            out.append('(')
            write(expr.receiver, out)
            out.append(')')
        } else {
            writeOperand(expr.receiver, out)
        }
        out.append(if (expr.safe) "?." else ".")
        require(expr.name.isIdentifier()) {
            "The printer cannot spell member '${expr.name}': a member name must be an identifier"
        }
        out.append(expr.name)
    }

    private fun writeCall(expr: Expr.Call, out: StringBuilder) {
        out.append(expr.function.value).append('(')
        expr.args.forEachIndexed { index, arg ->
            if (index > 0) out.append(", ")
            writeOperand(arg, out)
        }
        out.append(')')
    }

    private fun writeUnary(expr: Expr.Unary, out: StringBuilder) {
        when (expr.op) {
            UnaryOp.Not -> {
                out.append('!')
                writeOperand(expr.operand, out)
            }
            UnaryOp.Neg -> {
                out.append('-')
                if (expr.operand is Expr.Const && expr.operand.value.isNumeric) {
                    // `-5` is the spelling of a negative literal, so negation of a numeric
                    // literal always carries its own parens: the two readings must not share
                    // a text.
                    out.append('(')
                    write(expr.operand, out)
                    out.append(')')
                } else {
                    writeOperand(expr.operand, out)
                }
            }
        }
    }

    private val Value.isNumeric: Boolean
        get() = this is Value.Int32 || this is Value.Int64 || this is Value.Float32 ||
            this is Value.Float64 || this is Value.Dp || this is Value.Sp

    private fun String.isIdentifier(): Boolean =
        isNotEmpty() &&
            (first().isLetter() || first() == '_') &&
            drop(1).all { it.isLetterOrDigit() || it == '_' }
}
