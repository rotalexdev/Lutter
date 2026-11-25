package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprParser
import dev.rotalex.lutter.model.expr.ExprPrinter
import dev.rotalex.lutter.model.expr.PropertyValue

/**
 * The outcome of committing a text field's contents to a property.
 *
 * Three cases because a commit has exactly three fates: it becomes a value, the engine refuses
 * the command the value would produce, or the text is not an expression at all. The third is
 * the one an editor must show in the field itself — a parse error is not a document diagnostic,
 * because nothing was edited.
 */
public sealed interface PropertyEdit {

    /** The value the text named, ready for `Command.SetProperty`. */
    public data class Value(public val value: PropertyValue) : PropertyEdit

    /** The text is not an expression. Nothing was written. */
    public data class Unparseable(public val message: String) : PropertyEdit
}

/**
 * A `PropertyValue` as the text one field shows, and back (§9.2's expression editor).
 *
 * ### One box, two arms of the union
 *
 * `PropertyValue` has two arms, and this object deliberately collapses them into one string:
 * `Expr.Const` prints as a literal (`"Alpha"`, `8`, `true`), every other expression prints as
 * its canonical source, and parsing the result decides which arm comes back. A field that
 * needed a mode switch — "literal or expression?" — would ask an author a question the text
 * already answers, and the answer it hedges is exactly the one `Expr.Const` makes exact.
 *
 * The rule is not a guess: `ExprParser` guarantees `parse(print(expr)) == expr` and refuses
 * anything it does not fully consume, so a text that parses to `Expr.Const` *is* a literal and
 * one that parses to anything else *is* an expression. Nothing here re-implements either half.
 *
 * ### What is not here
 *
 * No caret handling, no partial-parse state, and no history merging. §25.1 keeps undo-menu
 * copy in the editor and §26.3 keeps merge windows in the caller's hands — a field that
 * committed on every keystroke would need both, and a field that commits on focus loss needs
 * neither.
 */
public object PropertyText {

    /** The canonical text of [value]: what the field starts with, and what an undo restores. */
    public fun of(value: PropertyValue): String = ExprPrinter.print(asExpr(value))

    /**
     * The value [text] spells.
     *
     * An empty or blank text is [PropertyEdit.Unparseable] rather than a default: "clear this
     * property" is `Command.SetProperty(value = null)`, a different edit with a different
     * meaning, and a field that turned blank into the spec's default would delete a value
     * nobody asked to delete.
     */
    public fun apply(text: String): PropertyEdit {
        if (text.isBlank()) {
            return PropertyEdit.Unparseable(
                "a property needs a value; to remove it, clear the property rather than its text",
            )
        }
        val expr: Expr = try {
            ExprParser.parse(text)
        } catch (failure: IllegalArgumentException) {
            return PropertyEdit.Unparseable(failure.message ?: "not an expression")
        }
        return PropertyEdit.Value(
            when (expr) {
                is Expr.Const -> PropertyValue.Const(expr.value)
                else -> PropertyValue.Computed(expr)
            },
        )
    }

    private fun asExpr(value: PropertyValue): Expr = when (value) {
        is PropertyValue.Const -> Expr.Const(value.value)
        is PropertyValue.Computed -> value.expr
    }
}
