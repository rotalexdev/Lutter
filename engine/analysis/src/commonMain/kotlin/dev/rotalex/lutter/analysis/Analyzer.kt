package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticSorter
import dev.rotalex.lutter.analysis.pass.ActionPass
import dev.rotalex.lutter.analysis.pass.AnalysisPass
import dev.rotalex.lutter.analysis.pass.ExprAddress
import dev.rotalex.lutter.analysis.pass.ExpressionPass
import dev.rotalex.lutter.analysis.pass.ReferencePass
import dev.rotalex.lutter.analysis.pass.ResolutionPass
import dev.rotalex.lutter.analysis.pass.SchemaPass
import dev.rotalex.lutter.analysis.pass.StructuralPass
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.SchemaRevision
import dev.rotalex.lutter.analysis.resolved.SchemaRevisionMinter
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.doc.ComponentDecl
import dev.rotalex.lutter.analysis.scope.ScopeAnalysis
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.index.DirtyNodes
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.ScopeId
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
     * §29.1:2048's revision of the base view, minted once: the base schema never changes
     * within one analyzer instance, so one id covers every run without document components.
     */
    private val baseRevision: SchemaRevision = SchemaRevisionMinter.mint()

    /**
     * The overlay the last component surface built, beside the revision it got.
     *
     * The overlay reads exactly two things off the document — the declarations and their
     * recorded versions — so an equal surface builds an equal overlay by construction, and
     * reusing the instance for it is what lets §29.1:2048's revision name a schema context
     * rather than a call. One entry: the pipeline re-analyzes one document forward, and the
     * previous entry is unreachable once the surface moved.
     */
    private var overlayCache: OverlayCache<A, F, T>? = null

    private class OverlayCache<A : Any, F : Any, T : Any>(
        val components: Map<ComponentDeclId, ComponentDecl>,
        val componentVersions: Map<ComponentType, Int>,
        val view: SchemaOverlay<ModifierSpec, A, F, T>,
        val revision: SchemaRevision,
    )
    /**
     * The gate over one document, with §6.2:537's partial-update seam beside the full run.
     *
     * [dirty] `null` — the default — is the full analysis this class has always run: every pass
     * sees the whole document and every node is lowered from scratch. With [dirty] non-null and
     * [previousFindings] present the reporting passes — 1, 3, 4, 5 and 6 — re-validate only the
     * nodes inside the dirty closure and carry the previous run's findings for every other node,
     * so the walk is the closure rather than the whole table. Pass 5 is the one that also feeds
     * the lowering, so its walk narrows only when [previous] is a resolution from this run's
     * schema context, whose typings can stand in for the clean nodes; without one it stays full,
     * whatever [previousFindings] holds. With [dirty] non-null but [previousFindings] absent
     * there is nothing to carry, and every reporting pass runs in full exactly as before.
     *
     * [previous] is the run the reuse draws from: the `AnalysisResult.resolved` of the document
     * as it was before the edits. `null` — which is also what a previous run that reported an
     * error carries, having resolved nothing — means there is nothing to reuse, and the call is
     * then a full analysis. That is the fallback rule rather than a shortcut, and it is what
     * keeps the full-versus-incremental equality property true for every document this class
     * cannot incrementally reuse. The carry is additionally keyed on the schema revision,
     * §29.1:2048's third cache component: [previous]'s document must have been lowered
     * against this run's schema context, and a revision that differs — a declaration edit, a
     * version note, a different analyzer — or an unknown one refuses the carry wholesale,
     * recomputing every node against the current view rather than trusting what another
     * context produced.
     *
     * [previousFindings] is [previous]'s reporting half: the diagnostics the run that produced
     * [previous] reported. When both it and [dirty] are present, the per-node passes validate
     * only the dirty closure and the node-attributed findings outside it are carried. The
     * caller contract is the whole of the soundness argument, and it is narrow: this call is
     * only valid for a patch that changed properties or modifiers of nodes inside the closure —
     * never one that inserted, removed, reordered or rewrote a record. Any of those can move a
     * finding for a node the dirty set does not name, and the analyzer cannot see the patch to
     * check. Two consequences follow from the same guarantee: the structural pass does not run in
     * this mode — the graph it checks is the one the caller promised not to move — so its
     * findings, node-attributed or not, are carried verbatim; and the placement guard in pass 7
     * is skipped, because a scope set can only change when a slot path does. Every other finding
     * with no node (declaration- and document-level) is recomputed on every run. A carried finding
     * whose node the new document no longer holds is dropped rather than surfaced stale.
     */
    public fun analyze(
        document: UiDocument,
        dirty: DirtyNodes? = null,
        previous: ResolvedDocument? = null,
        previousFindings: List<Diagnostic>? = null,
    ): AnalysisResult {
        val dirtySet = dirty
        val findings = previousFindings
        // The carry seam's precondition, named once: a closure plus the previous run's reporting
        // half. Everything below that trusts the caller reads this one flag.
        val carrying = dirtySet != null && findings != null
        // §6.2:537's closure, expressed as the passes' node predicate. `null` is the full path,
        // and the default parameters keep that path the exact call this class made before.
        val visit: ((NodeId) -> Boolean)? = if (dirtySet != null && findings != null) {
            val closure: DirtyNodes = dirtySet
            ({ id: NodeId -> id in closure })
        } else {
            null
        }
        // The previous run's findings for what this run does not re-check. Two rules: a finding
        // with no node is recomputed, because a declaration-level claim can move without any
        // node changing — the one exception is the structural family below — and a node finding
        // survives only when its node is outside the closure and the table still holds it.
        //
        // `struct.*` is the exception because the structural pass does not run at all in this
        // mode: a property or modifier edit cannot add, remove, reorder or re-parent a node, so
        // every graph claim the previous run made — including the ones with no node, like a
        // missing root — is still true of this document.
        val carriedFindings: List<Diagnostic> = if (dirtySet != null && findings != null) {
            val closure: DirtyNodes = dirtySet
            findings.filter { finding ->
                if (finding.code.value.startsWith(STRUCTURAL_CODE_PREFIX)) return@filter true
                val nodeId = finding.location.nodeId ?: return@filter false
                nodeId !in closure && document.nodes[nodeId] != null
            }
        } else {
            emptyList()
        }
        val found = mutableListOf<Diagnostic>()
        // §8.4's view, resolved before pass 3: a `doc.*` instance must be validated against the
        // spec its own declaration synthesizes, not refused as an unknown type. The view arrives
        // with §29.1:2048's schema revision: a document that declares none resolves against the
        // base view, whose revision is minted once per analyzer; one that declares components
        // resolves against the overlay its surface synthesizes, cached beside the revision and
        // rebuilt only when the surface the overlay reads — the declarations and their recorded
        // versions — differs from the last one this analyzer saw. Two documents with equal
        // surfaces share one view and one revision, which is sound because an equal surface
        // builds an equal overlay by construction; a surface that changed gets a fresh pair, and
        // every carry from the old one is refused in pass 7 wholesale rather than guarded per
        // node, because what changed is exactly the input no per-node guard can see.
        val view: SchemaView<ComponentSpec, ModifierSpec, A, F, T>
        val revision: SchemaRevision
        val cache = overlayCache
        if (document.components.isEmpty()) {
            view = schema
            revision = baseRevision
        } else if (
            cache != null &&
            cache.components == document.components &&
            cache.componentVersions == document.meta.componentVersions
        ) {
            view = cache.view
            revision = cache.revision
        } else {
            val overlay = SchemaOverlay(schema, document)
            view = overlay
            revision = SchemaRevisionMinter.mint()
            overlayCache =
                OverlayCache(document.components, document.meta.componentVersions, overlay, revision)
        }
        // One walk of the document serves every pass that needs an index or a scope set: the
        // index is immutable and the scope map is a pure function of it, so sharing changes no
        // answer and removes two of the three index builds a full analysis used to pay.
        val index = DocumentIndex(document)
        // In the carry path the scope map is a lookup instead of a table: the passes only ask
        // about the closure (and about whatever the lowerer has to recompute), so the ten
        // thousand ancestor walks a full map pays for are work no caller reads. The full path
        // keeps the map it always built, so its answer cannot change.
        val scopes: (NodeId) -> Set<ScopeId> = if (carrying) {
            ScopeAnalysis.lazyScopes(document, { view.components[it] }, index)
        } else {
            val full = ScopeAnalysis.computeScopes(document, { view.components[it] }, index)
            val lookup: (NodeId) -> Set<ScopeId> = { id -> full[id] ?: emptySet() }
            lookup
        }
        val passes = mutableListOf<AnalysisPass>()
        // The structural pass is the one walk whose answer a property or modifier edit cannot
        // change, so the carry path does not pay for it; its findings come across above.
        if (!carrying) passes += StructuralPass()
        passes += SchemaPass(view, scopes, visit)
        passes += ReferencePass(view, visit)
        for (pass in passes) {
            found += pass.run(document)
            if (pass.blocking && found.any { it.severity == Severity.Error }) break
        }
        // Pass 5 sits outside the list because `AnalysisPass.run` returns findings alone and
        // pass 7 attaches what it produces; §17.1 also has it skipped once a pass has errored.
        // The error test reads the carried findings beside the fresh ones: a carried error is as
        // real as one this run rediscovered, and the full analysis would have skipped pass 5 for
        // it too.
        val checked = if ((carriedFindings + found).none { it.severity == Severity.Error }) {
            // Pass 5's typing is also pass 7's input. Filtering its node walk to the closure
            // would leave no typing for a node pass 7 carries across, and `ResolutionPass`
            // compares a carried node's typings against this map before trusting it. So when a
            // previous resolution from this run's schema context is present, its entries answer
            // for the nodes this run does not re-check: the node did not change, and neither did
            // its expressions' scope (every ancestor of a clean node is clean too), so the
            // previous typing is this run's typing. `CarriedTypings` reads one address at a time
            // from that tree rather than materialising a map over the whole document. A previous
            // resolution from another context is no evidence at all — pass 7 refuses that carry
            // wholesale — and then pass 5 stays full so the lowering has every typing. Without a
            // previous resolution there is nothing to read from, and pass 5 stays full for the
            // same reason.
            val reusable: ResolvedDocument? = previous?.takeIf { it.schemaRevision == revision }
            val expressionVisit: ((NodeId) -> Boolean)? =
                if (visit != null && reusable != null) visit else null
            val raw = ExpressionPass(view, index, expressionVisit).check(document)
            if (expressionVisit != null && reusable != null) {
                ExpressionPass.Result(
                    raw.diagnostics,
                    CarriedTypings(raw.typed, reusable),
                    raw.derived,
                )
            } else {
                raw
            }
        } else {
            ExpressionPass.Result.NONE
        }
        found += checked.diagnostics
        // Pass 6 is unconditional: a document can be wrong in two independent ways, and the
        // author needs both reported rather than only whichever one an earlier pass found.
        found += ActionPass(view, index, visit).run(document)
        // A node this run re-validated can emit a finding attributed to a different, clean node
        // (a structural check reading the graph is the one that does), so a carried finding and
        // a fresh one can name the same fact twice. Deduplicating after the sort keeps the
        // result the sorted set a full analysis produces.
        val sorted = DiagnosticSorter.sort(carriedFindings + found).distinct()
        if (sorted.any { it.severity == Severity.Error }) return AnalysisResult(sorted, null)
        // Pass 7 takes both or neither: a dirty set without a previous run to reuse from is a
        // full analysis — there is nothing to carry — and so is `dirty` null, whatever
        // [previous] holds. The default parameters keep the null path the exact call today is.
        val carried = previous.takeIf { dirtySet != null }
        return AnalysisResult(
            sorted,
            ResolutionPass(view, checked, revision, scopes, trustPlacement = carrying)
                .run(document, dirtySet, carried),
        )
    }
}

/**
 * Pass 5's product with the previous resolution behind it.
 *
 * [fresh] holds what this run typed, which is the closure. Everything outside it is a node the
 * document did not change, so its typing is the one [previous] already holds — read one address
 * at a time rather than materialised as a map over the whole document, which is a walk this seam
 * exists to avoid. The caller gates this on the same schema revision as the resolution carry, so
 * an entry read here is from this run's schema context.
 */
private class CarriedTypings(
    private val fresh: Map<ExprAddress, TypedExpr>,
    private val previous: ResolvedDocument,
) : Map<ExprAddress, TypedExpr> by fresh {

    override fun get(key: ExprAddress): TypedExpr? {
        fresh[key]?.let { return it }
        val node = previous.nodes[key.node] ?: return null
        val index = key.modifierIndex
        return if (index == null) {
            node.props[key.property]?.typed
        } else {
            node.modifiers.getOrNull(index)?.args?.get(key.property)?.typed
        }
    }

    override fun containsKey(key: ExprAddress): Boolean = get(key) != null
}

/** The diagnostic-code prefix the structural pass owns — `pass/StructuralPass.kt`'s family. */
private const val STRUCTURAL_CODE_PREFIX: String = "struct."
