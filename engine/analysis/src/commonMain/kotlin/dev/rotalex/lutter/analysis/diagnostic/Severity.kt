package dev.rotalex.lutter.analysis.diagnostic

/**
 * How loudly a diagnostic fails the document: errors block resolution, the rest do not.
 *
 * Declaration order is the sort order — [DiagnosticSorter] reads [ordinal], so Error first.
 */
public enum class Severity {
    Error,
    Warning,
    Info,
}
