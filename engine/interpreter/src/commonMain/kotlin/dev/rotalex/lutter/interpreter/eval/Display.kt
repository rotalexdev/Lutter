package dev.rotalex.lutter.interpreter.eval

import dev.rotalex.lutter.model.value.Value

/**
 * The text a template part becomes (§10.5, §10.6).
 *
 * §10.4 admits four part types and §10.6's first hazard row is the reason a fifth is absent:
 * `Double.toString()` differs between JS and JVM, so a float's text is `num.format`'s to
 * write, on both sides, with integer arithmetic. There is no float arm here and that is the
 * rule landing rather than a gap — a part that is one is a document pass 5 already refused,
 * and printing it would put back exactly the divergence the row exists to prevent.
 *
 * Integers go through `Long.toString()`, which is exact base ten on every target. It is the
 * same argument `CanonicalNumbers` in `:engine:model` makes for a document's own numbers.
 */
public fun Value.toDisplayString(): String = when (this) {
    is Value.Str -> v
    is Value.Int32 -> v.toString()
    is Value.Int64 -> v.toString()
    is Value.Bool -> v.toString()
    else -> notAPart()
}

/**
 * The one refusal. A part that is none of the four is a document analysis rejected, and the
 * message says so rather than inventing a rendering for it.
 */
private fun Value.notAPart(): Nothing = throw IllegalStateException(
    "'${this::class.simpleName}' is not a template part: §10.4 admits str, i32, i64 and bool, " +
        "and a floating value has to go through num.format before it is written as text",
)
