package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticSorter
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.pass.ActionPass
import dev.rotalex.lutter.analysis.pass.ExpressionPass
import dev.rotalex.lutter.analysis.pass.ReferencePass
import dev.rotalex.lutter.analysis.pass.ResolutionPass
import dev.rotalex.lutter.analysis.pass.SchemaPass
import dev.rotalex.lutter.analysis.pass.StructuralPass
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.model.doc.UiDocument
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
    public fun analyze(document: UiDocument): AnalysisResult {
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
        return AnalysisResult(sorted, ResolutionPass(view, checked).run(document))
    }
}
