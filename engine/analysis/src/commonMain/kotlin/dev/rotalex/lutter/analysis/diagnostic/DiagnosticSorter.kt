package dev.rotalex.lutter.analysis.diagnostic

/**
 * PLAN §17.2's order: severity first, then page, node, code, property. Total and stable.
 */
internal object DiagnosticSorter {
    public fun sort(diagnostics: List<Diagnostic>): List<Diagnostic> =
        diagnostics.sortedWith(
            compareBy(
                { it.severity.ordinal },
                { it.location.pageId?.value },
                { it.location.componentDeclId?.value },
                { it.location.nodeId?.value },
                { it.code.value },
                { it.location.property?.value },
                { it.location.modifierIndex },
                { it.location.path.joinToString(separator = "/") },
                { it.message },
            ),
        )
}
