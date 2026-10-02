package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticSorter
import dev.rotalex.lutter.analysis.diagnostic.Severity
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

/**
 * The document gate: validates a [UiDocument] and lowers it when clean.
 *
 * Runs passes 1, 3, 4, 5 and 7 in order; passes 2, 6 and 8 are later phases and are
 * absent here rather than stubbed. Never throws on invalid documents — findings
 * come back as diagnostics with [AnalysisResult.resolved] null.
 */
public class Analyzer<A : Any, F : Any, T : Any>(
    private val schema: SchemaView<ComponentSpec, ModifierSpec, A, F, T>,
) {
    public fun analyze(document: UiDocument): AnalysisResult {
        val found = mutableListOf<Diagnostic>()
        val passes = listOf(StructuralPass(), SchemaPass(schema), ReferencePass(schema))
        for (pass in passes) {
            found += pass.run(document)
            if (pass.blocking && found.any { it.severity == Severity.Error }) break
        }
        // Pass 5 sits outside the list because `AnalysisPass.run` returns findings alone and
        // pass 7 attaches what it produces; §17.1 also has it skipped once a pass has errored.
        val checked = if (found.none { it.severity == Severity.Error }) {
            ExpressionPass(schema).check(document)
        } else {
            ExpressionPass.Result.NONE
        }
        found += checked.diagnostics
        val sorted = DiagnosticSorter.sort(found)
        if (sorted.any { it.severity == Severity.Error }) return AnalysisResult(sorted, null)
        return AnalysisResult(sorted, ResolutionPass(schema, checked).run(document))
    }
}
