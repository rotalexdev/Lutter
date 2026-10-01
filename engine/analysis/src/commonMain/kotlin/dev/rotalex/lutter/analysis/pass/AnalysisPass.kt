package dev.rotalex.lutter.analysis.pass

import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.analysis.diagnostic.Diagnostic

/**
 * One analysis phase. Internal: callers see [dev.rotalex.lutter.analysis.Analyzer] only.
 *
 * A blocking pass with errors stops later passes; the rest accumulate unconditionally.
 */
internal interface AnalysisPass {
    public val name: String
    public val blocking: Boolean
    public fun run(document: UiDocument): List<Diagnostic>
}
