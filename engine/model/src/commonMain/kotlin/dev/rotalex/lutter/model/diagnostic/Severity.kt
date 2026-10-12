package dev.rotalex.lutter.model.diagnostic

/**
 * How loudly a diagnostic fails the document: errors block resolution, the rest do not.
 *
 * Declaration order is the sort order — `DiagnosticSorter` reads [ordinal], so Error first.
 *
 * ### Why this moved to `:engine:model` (D29)
 *
 * Because [Diagnostic] did, and because a severity is not an analysis decision: it is the one
 * field of a finding that says whether the thing found is fatal, and every producer of a
 * finding has to answer it. A refused command is always [Error] — a command either describes
 * an edit that may be made or one that may not, and there is no severity in between — so the
 * editing layer reads this enum and never writes a warning about a refusal.
 */
public enum class Severity {
    Error,
    Warning,
    Info,
}