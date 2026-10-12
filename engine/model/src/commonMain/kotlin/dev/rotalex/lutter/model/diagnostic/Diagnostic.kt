package dev.rotalex.lutter.model.diagnostic

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
 *
 * ### Why this lives in `:engine:model`, which is D29 and not a preference
 *
 * The type used to sit in `:engine:analysis`, and it never used the layer it sat in: its
 * five imports were all from `:engine:model` and none from analysis. Moving it down relocates
 * a record that was always data — a code, a severity, a location, a message and the arguments
 * behind them — and nothing else.
 *
 * The move is forced by a contradiction the plan had rather than chosen. §25.2 has
 * `DocumentController.dispatch` return `Rejected(reasons: List<Diagnostic>)` and §33.6 files
 * that controller in `:engine:editing`, whose allow-list is `:engine:model` and
 * `:engine:schema`. The module that owns the commands therefore could not name the thing its
 * own commands refuse with. Widening the allow-list to admit `:engine:analysis` would have
 * inverted §23.3's *"the editor composes the layers, the layers do not compose the editor"*;
 * moving a data type down violates no boundary at all.
 *
 * ### What did *not* move, and why
 *
 * `DiagnosticCodes` — the catalog of specific codes — stayed in `:engine:analysis`, because
 * that is where the passes that emit them live. A code without a pass that raises it is a
 * string; this one is `component.unknown` next to `SchemaPass`, which raises it. The type here
 * carries a `DiagnosticCode` and does not know any of them, which is what lets
 * `:engine:editing` carry its own `edit.` catalog alongside.
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