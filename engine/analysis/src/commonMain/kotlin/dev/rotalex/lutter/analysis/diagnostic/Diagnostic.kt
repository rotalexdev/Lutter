package dev.rotalex.lutter.analysis.diagnostic

import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey

/**
 * One finding: PLAN §17.2 field for field. Values, not wire — never serialized.
 *
 * [message] is rendered from [code] plus [args] in English; [args] carries the same
 * facts for tooling. [suggestions] stays empty until the editing layer owns fixes.
 */
public data class Diagnostic(
    public val severity: Severity,
    public val code: DiagnosticCode,
    public val location: DiagnosticLocation,
    public val message: String,
    public val args: Map<String, String> = emptyMap(),
    public val suggestions: List<Suggestion> = emptyList(),
)

/**
 * Where a diagnostic points: PLAN §17.2's seven fields. Only the relevant ones are set.
 *
 * [path] disambiguates inside a node — slot plus index, or the app-level address.
 */
public data class DiagnosticLocation(
    public val pageId: PageId? = null,
    public val componentDeclId: ComponentDeclId? = null,
    public val nodeId: NodeId? = null,
    public val property: PropertyKey? = null,
    public val modifierIndex: Int? = null,
    public val event: EventKey? = null,
    public val path: List<String> = emptyList(),
)

/**
 * A proposed fix, human-readable. The Patch arm PLAN sketches arrives with the editor.
 */
public data class Suggestion(public val label: String)
