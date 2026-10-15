package dev.rotalex.lutter.testsupport.benchmark

import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandPlanner
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.patch.PatchApplier
import dev.rotalex.lutter.editing.template.IdRemapper
import dev.rotalex.lutter.editing.template.NodeTemplate
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.SequentialIdGenerator
import dev.rotalex.lutter.testsupport.generator.DocumentGenerator
import dev.rotalex.lutter.testsupport.generator.codegenCorpusView

// PLAN §29's table, and the document every row of it is about.
//
// ### The finding this file exists to record
//
// §29.3 specifies the workload as `DocumentGenerator.balanced(nodes = 10_000, depth = 12)`.
// **There is no such function, and there never has been.** `DocumentGenerator` is a pure
// function of a seed and has exactly one public entry point, `generate()`, which reads
// `MAX_DEPTH = 3` and two or three pages out of the seed's own stream and then fills every
// slot with `random.nextInt(3)` children. There is no node budget, no depth parameter and no
// page-count parameter, so there is no seed that reliably names ten thousand nodes: the size
// is a *consequence* of the seed, and finding a seed that happens to produce 10,000 of them
// would need a search this repository cannot run at authoring time. The smallest generator
// produces a few dozen nodes and a lucky one produces a few hundred, so the gap between what
// §29 asks for and what the generator offers is two orders of magnitude.
//
// That is a finding about the engine rather than about a benchmark, and it is recorded here
// rather than in a report because the next reader will otherwise re-derive it: **the engine
// can analyse and generate for any size, but nothing in it can author a document of a chosen
// size.** §5.6's normalized table is what makes the gap closable — nodes are rows, reachability
// is the only structure over them — and closing it means composing, not generating.
//
// ### What composing costs, and why it is the cheap way round
//
// The obvious composition is to merge several generated documents into one. It is not cheap
// and this file does not do it, because two generated documents disagree about five different
// identifier spaces and every one of them is referenced from a place that would have to be
// rewritten with it:
//
//   * `Page.name` and `Page.route` are `GenScreen$index` and `/generated/$index` in **every**
//     document, and §13.1 makes the route unique across the document.
//   * `appState` names are `appText`, `appCount`, `appTotal` in every document, and §12.1
//     resolves a state by name within its owner's store.
//   * `hostFunctions` are named `genHost0`/`genHost1` in every document, and `host.call`
//     names its callee as a string argument.
//   * `ComponentDecl.name` is `GenWidget$index` in every document, and §16.4 turns a page or
//     component name into a generated declaration.
//   * And then the references themselves: a `nav.navigate` step carries its target as
//     `Value.Ref(RefKind.Page, …)` inside `ActionStep.args`, and a duplicated app-state
//     declaration has to be renamed in `Expr.Ref(RefTarget.State(…))` at an arbitrary depth
//     inside every computed property of every node that read it.
//
// Only a document *merge* pays all of that. What this file does instead is **grow one
// document by cloning**, and the reason cloning is free is that every identifier a cloned row
// carries still names something that exists: the copy is of a subtree of the base document and
// is re-owned by a new page, but its references are untouched and remain resolvable. Only the
// rows' own [NodeId]s have to change, because §6.2 assigns an id once and `NodeTable.with`
// answers a collision by replacing the row that is there — the one failure no existence check
// in the engine reports. [IdRemapper] exists for exactly this and is what supplies them.
//
// ### The shape this produces, stated plainly
//
// One generated page's tree, copied until the table holds [BENCHMARK_TARGET_NODES] rows, each
// copy under a page of its own. **The copies are siblings, not a deeper tree**: depth is the
// generator's to decide and this file does not reach into it. What that means is that the
// document has a great many screens, each of ordinary generated shape, rather than one screen
// with a very wide root — and that is the shape a real application of ten thousand nodes has,
// because an application has screens. §29 asks for "a 10,000-node document" and gets one.
//
// The alternative — cloning into one page's slots — produces the same node count with a
// hundred columns in a row, which is a tree no §29 row is about and which §29.2's
// `perf.large_column` rule (declared there, not implemented anywhere in this repository)
// exists precisely to complain about.

/** §29's and §29.3's node count. The one number every scenario below is stated against. */
internal const val BENCHMARK_TARGET_NODES: Int = 10_000

/**
 * The seed the base document is generated from.
 *
 * Any seed works, because [composeLargeDocument] grows whatever it is given; this one is
 * fixed so that the workload is a **function of a constant** and a benchmark run on two
 * machines is measuring the same document. The base document's node count is whatever seed 1
 * draws, which is a few dozen — which is precisely why the composition loop, not the seed, is
 * what reaches ten thousand.
 */
internal const val BENCHMARK_BASE_SEED: Long = 1L

/** Prefix for the node ids the clones are given. Cannot collide with the generator's own. */
internal const val BENCHMARK_NODE_PREFIX: String = "bench_n_"

/** Prefix for the ids of the pages the clones are added under. */
internal const val BENCHMARK_PAGE_PREFIX: String = "bench_p"

/** The package §16.5's file layout is generated into. Fixed, like the seed, for the same reason. */
internal const val BENCHMARK_BASE_PACKAGE: String = "com.example.benchmark"

/**
 * A composed document and how it was put together.
 *
 * The two counts are the workload's identity. A benchmark that does not record what it
 * measured cannot be compared with anything, and "ten thousand nodes" is not an identity: two
 * runs of this function agree on [nodes] and [pages] to the last, which is what lets a
 * regression report name a difference in the engine rather than a difference in the fixture.
 */
internal class ComposedDocument(

    /** The document. */
    val document: UiDocument,

    /** How many pages were added to reach the target. Zero when the base document was big enough. */
    val addedPages: Int,

    /** The page every copy was taken from, named so a failure can say what it copied. */
    val copiedFrom: PageId,
) {

    /** The node count §29's targets are stated for. */
    val nodes: Int get() = document.nodes.size

    /** How many screens the document has, copied and original. */
    val pages: Int get() = document.pages.size

    /** One line for a failure message: what was built, without the ten thousand nodes in it. */
    fun describe(): String =
        "seed $BENCHMARK_BASE_SEED, ${nodes} node(s) over ${pages} page(s), " +
            "$addedPages copied from '$copiedFrom'"
}

/**
 * A generated document grown to at least [target] nodes by cloning one of its own pages.
 *
 * Pure and deterministic: same arguments, same document, byte for byte. The determinism comes
 * from three choices and each of them is load-bearing.
 *
 *   * **One [IdRemapper] for the whole run.** `IdRemapper`'s own KDoc calls this the supported
 *     pattern — "*a bulk paste remaps twice with the same instance and is guaranteed two
 *     disjoint sets of ids*" — and it is also what makes the loop affordable. A remapper per
 *     copy reserves every id in the document on construction (`NodeTable.ids()` is an `O(n log
 *     n)` sort), so a remapper per copy is quadratic over the run and a thousand copies is a
 *     hundred million string comparisons before the first measurement.
 *   * **[SequentialIdGenerator], not a random one.** Ids are counted, not drawn, so the
 *     thousandth copy carries the id it carried yesterday. §6.2's "never derived from
 *     position" is about ids the *engine* assigns to a user's nodes; `SequentialIdGenerator`'s
 *     own KDoc names test and fixture documents as what it is for, and a benchmark fixture is
 *     one. `BENCHMARK_NODE_PREFIX` is what keeps the counted ids disjoint from the generator's
 *     own, which are `n` plus six characters from an alphabet with no underscore in it.
 *   * **The base document's page, copied, and nothing else.** See the file KDoc for why this
 *     is not a merge of several generated documents.
 *
 * ### What the composition deliberately does not do
 *
 * It does not renumber, re-own, or rewrite anything the cloned rows reference. Each new page
 * therefore carries the source page's [Page.params] and [Page.state] **as the same records**,
 * state ids included, and that is legal rather than a shortcut: `AnalysisPass.stateOf` builds
 * its lookup as `document.appState.plus(owned)`, so a `StateDecl` is resolved inside its
 * owner's store and two pages holding the same one is two screens that both read the same
 * scope. Codegen is equally comfortable with it — a page's state class is named from the
 * *page* (`StateEmitter.classNameOf` is `page.name + PageStateSuffix`), and every page this
 * function adds is named uniquely, so each screen emits its own holder with no collision, and
 * component state is emitted as composable locals where no name is shared at all.
 *
 * @param target the node count to reach. Every call adds at least one row, so this terminates
 *   for any value at or above the base document's own size.
 * @param seed the base document's seed. See [BENCHMARK_BASE_SEED].
 * @throws IllegalStateException if the generated document has no page to copy, or if
 *   [CommandPlanner] refuses an [Command.AddPage]. Both are refusals this composition cannot
 *   work around, and both are better as a failure than as a document nobody checked.
 */
internal fun composeLargeDocument(
    target: Int = BENCHMARK_TARGET_NODES,
    seed: Long = BENCHMARK_BASE_SEED,
): ComposedDocument {
    // The codegen corpus's filtered view, not `generatorSchema()`. The three components
    // `generatorSchema()` adds declare an intrinsic binding with a slot or an event and no
    // emitter behind it, which `KotlinGenerator`'s own coverage check refuses at construction,
    // and §29's table has a codegen row. `CodegenCorpus` states the whole of that argument.
    val view = codegenCorpusView

    var document: UiDocument = DocumentGenerator(view, seed).generate().document

    val source: Page = document.pages.values.firstOrNull()
        ?: error("seed $seed produced a document with no page, so there is nothing to copy")
    val sourceRoot: NodeId = source.root

    val ids = SequentialIdGenerator(BENCHMARK_NODE_PREFIX)
    val remapper = IdRemapper(document, ids)

    var added: Int = 0
    while (document.nodes.size < target) {
        val template: NodeTemplate =
            remapper.remap(NodeTemplate.capture(document, listOf(sourceRoot)).nodes)
        val root: Node = template.root
            ?: error("copying '$sourceRoot' out of a document that holds it produced no rows")

        // `name` and `route` are the two fields a copy cannot inherit: §16.4 turns a page name
        // into a generated declaration, and §13.1 makes a route unique across the document,
        // and the source page's are `GenScreen0` and `/generated/0`.
        val page = Page(
            id = PageId(BENCHMARK_PAGE_PREFIX + added),
            name = "BenchScreen$added",
            route = "/benchmark/$added",
            params = source.params,
            state = source.state,
            root = root.id,
        )

        val command = Command.AddPage(page, template)
        val patch = when (val planned = CommandPlanner.plan(command, document, view, ids)) {
            is CommandResult.Ok -> planned.patch
            is CommandResult.Rejected -> error(
                "adding page '${page.id}' was refused: " +
                    planned.reasons.joinToString("; ") { it.code.value + " " + it.message },
            )
        }
        document = PatchApplier.apply(patch, document)
        added += 1
    }

    return ComposedDocument(document, added, source.id)
}