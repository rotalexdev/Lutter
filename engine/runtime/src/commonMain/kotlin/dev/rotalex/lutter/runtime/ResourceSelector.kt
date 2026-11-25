package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.model.doc.Qualifier
import dev.rotalex.lutter.model.doc.ResourceDecl
import dev.rotalex.lutter.model.doc.ResourceVariant

/**
 * Variant selection, defined once so every host answers identically (§20:1595).
 *
 * The rule is §20's: a variant answers when its qualifier set is a **subset of the
 * environment's**, and the winner is the answering variant with the **most** qualifiers.
 * A variant with no qualifiers always answers — it is the fallback — and loses to any
 * more specific competitor.
 *
 * The tiebreak exists because two variants can match equally: `locale=es + density=480`
 * and `locale=es + theme=dark` answer the same environment with the same count when the
 * environment carries all three. §18.2:1414 gives sets no order, so the tiebreak cannot
 * be positional; the winner is the one whose qualifiers encode canonically smallest —
 * the same encoding the canonical writer uses for a set (§18.2's own tiebreak for sets).
 * The rule is total and target-independent, which is what the determinism obligation
 * needs: a JVM host and a Wasm host cannot disagree.
 *
 * Pure and public because the plan's `ResourceProvider` is the host's to implement
 * (§20:1598) — a provider that reaches for the document itself would need this rule,
 * and re-deriving it per host is exactly the divergence the engine exists to prevent.
 */
public object ResourceSelector {

    /** Picks [decl]'s variant for [environment], or null when nothing answers. */
    public fun select(decl: ResourceDecl, environment: Set<Qualifier>): ResourceVariant? =
        decl.variants
            .filter { variant -> variant.qualifiers.all(environment::contains) }
            .minWithOrNull(compareBy({ -it.qualifiers.size }, { canonical(it) }))

    /**
     * The canonical encoding of a variant's qualifiers: each qualifier as `type:payload`,
     * sorted by its own canonical form and joined.
     *
     * The payload types are the model's three, none of which has a platform-dependent
     * spelling, so the comparison is stable across targets.
     */
    private fun canonical(variant: ResourceVariant): String =
        variant.qualifiers.map(::canonical).sorted().joinToString(",")

    private fun canonical(qualifier: Qualifier): String = when (qualifier) {
        is Qualifier.Locale -> "locale:${qualifier.tag}"
        is Qualifier.Density -> "density:${qualifier.dpi}"
        is Qualifier.Theme -> "theme:${qualifier.variant}"
    }
}
