package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument

/**
 * What analysis found, and the lowered document when nothing failed.
 *
 * A plain class: identity is the run, not the two fields, so no structural copy.
 */
public class AnalysisResult(
    public val diagnostics: List<Diagnostic>,
    public val resolved: ResolvedDocument?,
) {
    /** Whether any diagnostic is an error; warnings alone still resolve. */
    public val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.Error }
}
