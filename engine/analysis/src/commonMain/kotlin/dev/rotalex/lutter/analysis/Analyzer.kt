package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticSorter
import dev.rotalex.lutter.analysis.pass.ActionPass
import dev.rotalex.lutter.analysis.pass.ExpressionPass
import dev.rotalex.lutter.analysis.pass.ReferencePass
import dev.rotalex.lutter.analysis.pass.ResolutionPass
import dev.rotalex.lutter.analysis.pass.SchemaPass
import dev.rotalex.lutter.analysis.pass.StructuralPass
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.index.DirtyNodes
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.overlay.SchemaOverlay

/**
 * The document gate: validates a [UiDocument] and lowers it when clean.
 *
 * Runs passes 1, 3, 4, 5, 6 and 7 in order; passes 2 and 8 are later phases and are
 * absent here rather than stubbed. Never throws on invalid documents — findings
 * come back as diagnostics with [AnalysisResult.resolved] null.
 *
 * A document that declares components is analyzed against §8.4's overlay, so a `doc.*`
 * instance is a node whose spec the document itself supplied rather than an unknown type.
 */
public class Analyzer<A : Any, F : Any, T : Any>(
    private val schema: SchemaView<ComponentSpec, ModifierSpec, A, F, T>,
) {
    /**
     * The gate over one document, with §6.2:537's partial-update seam beside the full run.
     *
     * [dirty] `null` — the default — is the full analysis this class has always run: every pass
     * sees the whole document and every node is lowered from scratch. With [dirty] non-null the
     * reporting passes — 1, 3, 4, 5 and 6 — still run over the whole document, because a
     * diagnostic is a claim about the document as a whole and no pass here can make a partial
     * one. What [dirty] narrows is pass 7 alone: [previous]'s resolved node for every id outside
     * the dirty set is carried across at reference identity — §29.1:2048's skip-ability
     * contract, the observable property that lets a runtime skip an untouched subtree — and the
     * nodes inside are lowered again against the current document, their inherited scope
     * recomputed from it.
     *
     * [previous] is the run the reuse draws from: the `AnalysisResult.resolved` of the document
     * as it was before the edits. `null` — which is also what a previous run that reported an
     * error carries, having resolved nothing — means there is nothing to reuse, and the call is
     * then a full analysis. That is the fallback rule rather than a shortcut, and it is what
     * keeps the full-versus-incremental equality property true for every document this class
     * cannot incrementally reuse.
     */
    public fun analyze(
        document: UiDocument,
        dirty: DirtyNodes? = null,
        previous: ResolvedDocument? = null,
    ): AnalysisResult {
        val found = mutableListOf<Diagnostic>()
        // §8.4's view, built per call and before pass 3: a `doc.*` instance must be validated
        // against the spec its own declaration synthesizes, not refused as an unknown type. It is
        // never cached on this analyzer, so one document's components cannot reach another's
        // analysis, and a document that declares none pays nothing for the merged registry.
        val view: SchemaView<ComponentSpec, ModifierSpec, A, F, T> =
            if (document.components.isEmpty()) schema else SchemaOverlay(schema, document)
        val passes = listOf(StructuralPass(), SchemaPass(view), ReferencePass(view))
        for (pass in passes) {
            found += pass.run(document)
            if (pass.blocking && found.any { it.severity == Severity.Error }) break
        }
        // Pass 5 sits outside the list because `AnalysisPass.run` returns findings alone and
        // pass 7 attaches what it produces; §17.1 also has it skipped once a pass has errored.
        val checked = if (found.none { it.severity == Severity.Error }) {
            ExpressionPass(view).check(document)
        } else {
            ExpressionPass.Result.NONE
        }
        found += checked.diagnostics
        // Pass 6 is unconditional: a document can be wrong in two independent ways, and the
        // author needs both reported rather than only whichever one an earlier pass found.
        found += ActionPass(view).run(document)
        val sorted = DiagnosticSorter.sort(found)
        if (sorted.any { it.severity == Severity.Error }) return AnalysisResult(sorted, null)
        // Pass 7 takes both or neither: a dirty set without a previous run to reuse from is a
        // full analysis — there is nothing to carry — and so is `dirty` null, whatever
        // [previous] holds. The default parameters keep the null path the exact call today is.
        val carried = previous.takeIf { dirty != null }
        return AnalysisResult(sorted, ResolutionPass(view, checked).run(document, dirty, carried))
    }
}
