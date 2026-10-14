package dev.rotalex.lutter.editing.patch

import dev.rotalex.lutter.editing.assertSameDocument
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandPlanner
import dev.rotalex.lutter.editing.command.CommandPlannerRoundTripTest
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.diff.DocumentDiff
import dev.rotalex.lutter.editing.template.IdRemapper
import dev.rotalex.lutter.editing.template.NodeTemplate
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.diagnostic.DiagnosticCode
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.overlay.SchemaOverlay
import dev.rotalex.lutter.schema.registry.Registry
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.testsupport.generator.DocumentGenerator
import dev.rotalex.lutter.testsupport.generator.generatorSchema
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PLAN §36.2's bullet, over a thousand generated documents instead of Phase 9's fifteen rows:
 * *"`apply(patch.inverse(), apply(patch, d)) == d` for all commands over at least 1,000
 * generated documents; `apply(diff(a, b), a) == b`"*.
 *
 * ### What "all commands" is taken to mean here, stated before the results
 *
 * §25.3 declares thirteen commands; [Command]'s own KDoc says the thirteenth,
 * `ExtractComponent`, is deferred to the wave-2 work unit and why. What is left is twelve, and
 * **"all commands" here means: every one of the twelve is planned against every generated
 * document, and every planning that the planner accepts is checked.** It does *not* mean every
 * *instance* of every command — a document with forty nodes has forty `RemoveNode`s and about
 * eleven thousand `MoveNode`s, and §36 asks for a thousand documents, not for the cross product.
 * So each document contributes one instance per command family, drawn off its own seed, plus a
 * second `MoveNode` whose target is guaranteed to be refused.
 *
 * ### "The schema admits" is a question with two answers, and conflating them is the defect
 *
 * A generated document is **not** a legal target for every command. Removing a page root, moving
 * a node into its own subtree, inserting a `m3.Text` into a slot whose `ChildFilter` excludes
 * it, renaming a node that a previous command in the same session removed: each is
 * `CommandResult.Rejected`, and each carries a code from [dev.rotalex.lutter.editing.command.EditCodes].
 * Those are not failures of §26.2's property — no patch was produced, so there is nothing to
 * invert — and treating them as failures would make the test green for the wrong reason.
 *
 * So the two answers are kept apart by construction, and the difference is visible in the
 * failure message rather than inferred from it:
 *
 *  * **`CommandResult.Rejected`** increments a census keyed by `DiagnosticCode` and contributes
 *    nothing else. No inverse is attempted, because none exists.
 *  * **`CommandResult.Ok`** is the only arm that reaches `PatchApplier`, and the only one whose
 *    round trip is asserted.
 *
 * The census is not decoration, it is the second half of the proof. **A loop whose every
 * instance is refused is a loop that asserts nothing while passing**, so the test refuses to go
 * green until all twelve command families have produced at least one accepted patch — and
 * compares that set against `CommandPlannerRoundTripTest.PLANNED_COMMANDS`, which
 * `CommandLegalityTest` already proves equal to §25.3's closed set. In the other direction, the
 * test also refuses to go green when *nothing* was refused, because a corpus that never meets a
 * refusal is a corpus that is not exercising the planner at all.
 *
 * ### Which commands can and cannot be exercised against an arbitrary generated document
 *
 * Honestly, because the corpus is generated rather than written and the differences matter:
 *
 *  * **All twelve are reachable, and all twelve are reached** — the coverage assertion above is
 *    what says so rather than this sentence.
 *  * **`InsertNode` carries a real subtree**, captured out of the document itself and remapped
 *    through [IdRemapper], so the `InsertNodes`/`RemoveSubtree` pair is exercised on more than
 *    one row.
 *  * **The four field setters are exercised with a key the component does not declare** on about
 *    half their instances. That is deliberate and it is `FieldPlanners.kt`'s stated policy: §25.3
 *    names slot `accepts` and slot `cardinality` and nothing else, so an undeclared property key
 *    is an *accepted* command. The resulting document is invalid and the inverse must still
 *    restore it — which is a stronger claim than only ever writing legal keys.
 *  * **`ExtractComponent` is not exercised at all**, because it does not exist. Its absence is
 *    recorded in [Command], not here.
 *  * **`Command.ApplyPatch` plans to itself.** The instance this loop builds hands it a patch
 *    another command in the same document produced, which is the real use — "a diff, a
 *    migration, a collaborator's queue" — and it re-proves the same identity for that patch. It
 *    is in the census because the variant exists and must plan, not because it finds anything a
 *    planner would not.
 *  * **What this cannot reach:** a command against a document whose structure is *already*
 *    malformed (two parents for one node, a dangling child reference), because every document
 *    here is valid — `DocumentGeneratorValidityTest` holds that and this file relies on it.
 *    The refusal arms that need a malformed document are covered by `CommandLegalityTest`'s
 *    hand-written table instead.
 *
 * ### The second property, and why its second document is a session
 *
 * `apply(diff(a, b), a) == b` needs two documents, and the interesting `b` is not a second
 * generated one: `DocumentDiff.compute` refuses any pair that disagrees on `UiDocument.app` or
 * on `meta`'s identity, and two generated documents disagree on both. So `b` is `a` after a
 * scripted editing session, which is also the only `b` that makes the diff do work.
 *
 * The session **plans and applies one command at a time against the running document**, and
 * that is not a convenience. A patch carries absolute positions, so two patches both planned
 * against the same `a` name positions the first one has already moved and `PatchApplier`
 * refuses the second — correctly. An editing session is a sequence of edits, not a batch planned
 * at once.
 *
 * ### The thousand, and what it costs
 *
 * Contiguous seeds `0 .. 999`, for the two reasons a property test needs at once: seed 417 is
 * the same document on every machine and on every run, and a thousand consecutive integers are a
 * thousand *different* documents rather than a thousand draws that might collide. Nothing is
 * read from `Random.Default` anywhere in this file — every draw is off a [Random] built from the
 * seed — because W0 established what happens when that is not true.
 *
 * On the number: the *categories* here saturate early, exactly as the generator's own coverage
 * test says for its corpus. What a thousand buys is thirteen thousand independent attempts at
 * the same categories, and the failures worth catching in a patch layer are position-dependent
 * — a `MoveNode` within one slot, a removal that empties a slot, a wrap's two ops interacting —
 * rather than categorical. Each is decided per command instance, so a thinner corpus has a
 * real chance of not containing the awkward one.
 */
class PatchInversePropertyTest {

    /**
     * §26.2's property, over the corpus: a planned patch, applied and then undone, returns the
     * document it was planned against.
     *
     * The coverage assertions come **before** the round trip is reported, and the reason is
     * this file's whole subject. If every `UnwrapNode` in a thousand documents were refused, the
     * loop below would record zero failures and this test would pass having proved nothing
     * about unwrapping. So the corpus is asked to account for all twelve families first, and
     * only then is a failure rendered.
     */
    @Test
    fun `every command a generated document admits round trips through its inverse`() {
        val accepted: MutableSet<KClass<out Command>> = mutableSetOf()
        val refused: MutableMap<DiagnosticCode, Int> = mutableMapOf()
        val rounds: MutableList<RoundTripFailure> = mutableListOf()
        var documents = 0
        var planned = 0
        var replays = 0

        for (seed in seeds(PROPERTY_SEED_COUNT)) {
            val document = DocumentGenerator(propertySchema, seed).generate().document
            val index = DocumentIndex(document)
            val registry = componentsOf(document)
            val ids = TestIds(seed)
            val random = Random(seed.toInt())
            documents += 1

            var replayed: Patch? = null
            for (candidate in plannedCommands(document, index, registry, ids, random, seed)) {
                planned += 1
                when (val result = CommandPlanner.plan(candidate.command, document, propertySchema, ids)) {
                    is CommandResult.Rejected -> recordRefusal(refused, result)

                    is CommandResult.Ok -> {
                        accepted += candidate.command::class
                        if (replayed == null) replayed = result.patch
                        val undo = undoOf(result.patch, document)
                        if (undo != document && rounds.size < REPORT_LIMIT) {
                            rounds += RoundTripFailure(seed, candidate.kind, result.patch.ops, document, undo)
                        }
                    }
                }
            }

            // The twelfth family. `Command.ApplyPatch` is the one command that plans to itself,
            // and the only interesting thing to hand it is a patch the engine produced for this
            // very document — a diff, a migration or a collaborator's queue all arrive that way.
            val incoming = replayed
            if (incoming != null) {
                planned += 1
                when (val result = CommandPlanner.plan(Command.ApplyPatch(incoming), document, propertySchema, ids)) {
                    is CommandResult.Rejected -> recordRefusal(refused, result)

                    is CommandResult.Ok -> {
                        accepted += Command.ApplyPatch::class
                        replays += 1
                        val undo = undoOf(result.patch, document)
                        if (undo != document && rounds.size < REPORT_LIMIT) {
                            rounds += RoundTripFailure(seed, "applyPatch", result.patch.ops, document, undo)
                        }
                    }
                }
            }
        }

        val census = censusOf(documents, planned, accepted, refused)
        val unexercised = CommandPlannerRoundTripTest.PLANNED_COMMANDS - accepted

        assertTrue(
            unexercised.isEmpty(),
            "the corpus never produced an accepted patch for $unexercised.\n" +
                "A command every planner refused contributes nothing to §26.2's property, and " +
                "the loop above would still be green.\n$census",
        )
        assertTrue(
            replays > 0,
            "no document produced a patch to replay through Command.ApplyPatch\n$census",
        )
        assertTrue(
            refused.isNotEmpty(),
            "nothing was ever refused, so the two arms of CommandResult were never told apart " +
                "and the corpus never met a §25.3 policy\n$census",
        )

        if (rounds.isNotEmpty()) {
            val first = rounds.first()
            assertSameDocument(
                first.before,
                first.after,
                "seed ${first.seed}: ${first.kind} did not round trip through its inverse.\n" +
                    "The ops the planner emitted:\n  ${first.ops.joinToString("\n  ")}\n" +
                    "Replay with DocumentGenerator(generatorSchema(), ${first.seed}).generate().\n" +
                    census,
            )
        }
    }

    /**
     * `apply(diff(a, b), a) == b`, where `b` is `a` after a scripted session of planned
     * commands.
     *
     * A different failure from the test above and in a different place. `PatchRoundTripTest`
     * already checks that `PatchApplier` is right about every op it is handed, and what is not
     * checked anywhere is that the ops `DocumentDiff` **chooses** compose into a patch that
     * produces the second document. An applier can be right about all eleven ops and a diff can
     * still emit the wrong set — a row removed from a parent that is also removed, a rewrite
     * issued before the removal it depends on, a declaration written into the wrong container.
     *
     * One pair per document rather than several, because a session is what produces the rich
     * side of the diff: removals, additions, structural rewrites, field edits and records all
     * in one patch, in the order `DocumentDiff.compute` documents. A single `RenameNode` would
     * exercise one group and read as coverage.
     */
    @Test
    fun `a document and the result of one session differ by a patch that reproduces the second`() {
        var documents = 0
        var identical = 0
        var failed = 0
        var failure: DiffFailure? = null

        for (seed in seeds(PROPERTY_SEED_COUNT)) {
            val first = DocumentGenerator(propertySchema, seed).generate().document
            val index = DocumentIndex(first)
            val registry = componentsOf(first)
            val ids = TestIds(seed)
            val random = Random(seed.toInt())
            documents += 1

            var current = first
            for (candidate in plannedCommands(first, index, registry, ids, random, seed)) {
                val result = CommandPlanner.plan(candidate.command, current, propertySchema, ids)
                if (result is CommandResult.Ok) current = PatchApplier.apply(result.patch, current)
            }

            // A session that changed nothing makes the identity vacuous, and counting it would
            // inflate the denominator with a patch of zero ops.
            if (current == first) {
                identical += 1
                continue
            }

            val replayed = PatchApplier.apply(DocumentDiff.compute(first, current), first)
            if (replayed != current) {
                failed += 1
                if (failure == null) failure = DiffFailure(seed, current, replayed)
            }
        }

        // `assertSameDocument` is the assertion and the report: it fails exactly when the two
        // documents differ, which is the condition being measured, and it renders both. A
        // separate `assertTrue` first would throw before the rendering ran, which is the one
        // thing this file exists to avoid. Named `witness` rather than `first` so it cannot be
        // confused with the `a` the loop above calls `first`.
        val witness = failure
        if (witness != null) {
            assertSameDocument(
                witness.expected,
                witness.actual,
                "seed ${witness.seed}: apply(DocumentDiff.compute(a, b), a) is not b.\n" +
                    "$failed of ${documents - identical} sessions failed; ${identical} of " +
                    "$documents sessions changed nothing and were skipped as vacuous.\n" +
                    "A pair differing only in `meta`'s identity or in `app` would have been " +
                    "refused by compute() outright, and no command this script plans edits " +
                    "either.\n" +
                    "Replay with DocumentGenerator(generatorSchema(), ${witness.seed}).generate() " +
                    "and the same scripted session.",
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// One failure, kept whole so both documents can be rendered
// -------------------------------------------------------------------------------------------

/**
 * The first command that applied and then failed to undo, with both documents kept whole.
 *
 * `NodeTable.toString()` is `NodeTable(size=13)`, so an `assertEquals` over two documents
 * reports a difference inside the table as two renderings that are byte-identical — which is
 * why the pair is handed to [assertSameDocument] rather than compared here. That helper already
 * exists in this module's test sources and delegates every row's fields to the record's own
 * `toString`, which is the discipline `firstDifference` in `:engine:test-support` and
 * `RoundTripPropertyTest.kt` in `:engine:serialization` follow on the other side of the graph.
 *
 * **Both documents, not the differing one.** The reader's first question is "what did the patch
 * do", and that is a question about the original; the second is "what is missing", and that is
 * a question about the result. A message carrying only one of them answers half of it.
 */
private class RoundTripFailure(
    val seed: Long,
    val kind: String,
    val ops: List<PatchOp>,
    val before: UiDocument,
    val after: UiDocument,
)

/** One pair that did not replay, kept whole for the same reason [RoundTripFailure] is. */
private class DiffFailure(
    val seed: Long,
    val expected: UiDocument,
    val actual: UiDocument,
)

// -------------------------------------------------------------------------------------------
// The census, which is what makes the loop's silence mean something
// -------------------------------------------------------------------------------------------

/**
 * Counts one refusal and contributes nothing else.
 *
 * **This function is the whole of "the planner refused" as distinct from "the inverse did not
 * restore".** A refused command produced no patch, so there is no inverse to apply and no
 * document to compare; running the round trip on it would be asserting that a refusal restores
 * a document, which is not a claim anything makes. Every refusal lands here and nowhere else,
 * and the message at the end of the loop prints the census so a reader can see that the refusal
 * arm was populated rather than assuming it.
 *
 * **Every reason, not just the first.** `slotRejections` accumulates: a slot that is both full
 * and closed is two true things and the planner reports both, so counting one would report a
 * frequency rather than a set.
 */
private fun recordRefusal(
    refused: MutableMap<DiagnosticCode, Int>,
    result: CommandResult.Rejected,
) {
    for (reason in result.reasons) {
        val code = reason.code
        refused[code] = (refused[code] ?: 0) + 1
    }
}

/**
 * What the run planned, what it accepted, and what it refused, as one line.
 *
 * A `DiagnosticCode` is a value class over `String` with a `toString` of its own, so the
 * entries join as the codes a reader would search for. Sorted by the code rather than by
 * frequency: the question this answers is "which planner rules were exercised", and the answer
 * is a set, not a ranking.
 */
private fun censusOf(
    documents: Int,
    planned: Int,
    accepted: Set<KClass<out Command>>,
    refused: Map<DiagnosticCode, Int>,
): String = "census: $documents documents, $planned command instances planned, " +
    "${accepted.size} of 12 command families accepted at least one patch; refusals: " +
    refused.entries
        .sortedBy { entry -> entry.key.value }
        .map { entry -> entry.key.toString() + " " + entry.value.toString() }
        .joinToString(", ")
        .ifEmpty { "none" }

/** [patch] applied to [document] and then undone, which is §26.2's identity as one expression. */
private fun undoOf(patch: Patch, document: UiDocument): UiDocument =
    PatchApplier.apply(patch.inverse(), PatchApplier.apply(patch, document))

// -------------------------------------------------------------------------------------------
// The schema, the seeds and the ids a planned command needs
// -------------------------------------------------------------------------------------------

/**
 * The schema the corpus is generated against and the commands are planned with.
 *
 * One instance, for the reason `generatorSchema()` is a function rather than a value: the
 * registry is immutable and rebuilding it a thousand times is a thousand allocations doing
 * nothing, while a shared `static` schema would be shared mutable state by another name.
 */
private val propertySchema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> =
    generatorSchema()

/** PLAN §36.2's "at least 1,000". See the class KDoc for what that number buys. */
private const val PROPERTY_SEED_COUNT: Int = 1_000

/** Enough failures to show every distinct cause, few enough to stay readable. */
private const val REPORT_LIMIT: Int = 3

private fun seeds(count: Int): List<Long> = (0L until count.toLong()).toList()

/**
 * Fresh node ids for a planned command.
 *
 * ### Why a counter and not a draw
 *
 * The property does not care what an id is; it cares that the same command planned twice
 * produces the same patch, which `CommandPlannerRoundTripTest` already asserts. A counter
 * seeded from the document's seed is that, it is readable in a failure message, and it cannot
 * collide with anything the generator minted.
 *
 * **The underscore is load-bearing.** `IdMint`'s alphabet is
 * `abcdefghijkmnpqrstuvwxyz23456789`, which contains no underscore, so an id of this shape —
 * `n_w1_417_0` — cannot be an id the generated document already holds. That matters because
 * §6.2 assigns an id once and `NodeTable.with` answers a collision by *replacing* a live row,
 * so a generator that repeated one would quietly delete a node rather than fail, and
 * `IdRemapper.fresh` would retry eight times before giving up and handing back a duplicate
 * anyway.
 */
private class TestIds(private val label: String) : IdGenerator {

    private var minted: Int = 0

    override fun nextNodeId(): NodeId = NodeId("n_w1_${label}_${minted++}")
}

/**
 * The component registry a document's commands are planned against.
 *
 * §8.4's overlay when the document declares components of its own, the schema's own registry
 * when it does not — which is exactly the branch `CommandPlanner.componentRegistry` makes. It is
 * `internal` to this module's `commonMain`, and it is reproduced here rather than reached for
 * because a test source set in the same module is the one place `internal` *is* visible:
 * `import dev.rotalex.lutter.editing.command.CommandPlanner.componentRegistry` would compile,
 * and would be the better answer if the visibility were right. What the two must not do is
 * disagree, because a candidate this file builds from the static registry against a `doc.*` node
 * would be planned against a parent with no slots, and the resulting refusal would be attributed
 * to the wrong rule.
 */
private fun componentsOf(document: UiDocument): Registry<ComponentType, ComponentSpec> =
    if (document.components.isEmpty()) {
        propertySchema.components
    } else {
        SchemaOverlay(propertySchema, document).components
    }

/** The slots the document's own registry says [id]'s component type declares, in spec order. */
private fun slotsOf(
    document: UiDocument,
    registry: Registry<ComponentType, ComponentSpec>,
    id: NodeId,
): List<SlotSpec> {
    val type = document.nodes[id]?.type ?: return emptyList()
    return registry[type]?.slots.orEmpty()
}

/** §7.1's three filters, one question. `PlannerSupport.admits` is `internal`; this is its twin. */
private fun admits(filter: ChildFilter, type: ComponentType): Boolean = when (filter) {
    ChildFilter.Any -> true
    is ChildFilter.Only -> type in filter.types
    is ChildFilter.Except -> type !in filter.types
}

/**
 * Whether the subtree at [root] closes: every child it names is a row the table holds.
 *
 * `NodeTemplate.capture` answers the same question by throwing, and this file needs the answer
 * as a value because it draws a source subtree per document and a throw would carry no seed.
 * `DocumentGeneratorValidityTest` holds every generated document to having no dangling
 * reference, so this is a guard on the harness rather than a check that can fail on the corpus —
 * which is the right way round: a generator defect shows up here as a skipped subtree rather
 * than as an exception with no reproduction.
 */
private fun closesOverItself(document: UiDocument, root: NodeId): Boolean {
    val seen = mutableSetOf<NodeId>()
    val pending = mutableListOf(root)
    while (pending.isNotEmpty()) {
        val id = pending.removeAt(pending.lastIndex)
        if (!seen.add(id)) continue
        val node = document.nodes[id] ?: return false
        for (children in node.slots.values) pending += children
    }
    return true
}

/**
 * A row of [document] the filter admits, leaves preferred, whose subtree closes — or `null`.
 *
 * Leaves are preferred and non-leaves are not excluded, for two reasons. A leaf's capture is
 * one row, which keeps `InsertNode`'s subtree argument honest about being a subtree while
 * staying small; and preferring leaves means the *accepted* arm of the insert is not starved by
 * `Cardinality`, because the interesting refusals (`slot_cardinality`, `slot_child_not_allowed`)
 * are exactly what the coverage census wants to see and are produced by the other builders.
 */
private fun sourceFor(
    document: UiDocument,
    filter: ChildFilter,
    random: Random,
): NodeId? {
    val admissible = document.nodes.ids()
        .filter { id -> document.nodes[id]?.type?.let { admits(filter, it) } == true }
        .filter { id -> closesOverItself(document, id) }
        .toList()
    if (admissible.isEmpty()) return null

    val leaves = admissible.filter { document.nodes[it]?.slots?.isEmpty() == true }
    val pool = if (leaves.isEmpty()) admissible else leaves
    return pool[random.nextInt(pool.size)]
}

/** The type a page root and an inserted wrapper row are given: a registered container. */
private val CONTAINER_TYPE: ComponentType = ComponentType("core.Column")

/** Registered components that declare at least one slot, and so can receive a wrapped node. */
private val WRAPPABLE: List<ComponentSpec> = propertySchema.components.all().filter { it.slots.isNotEmpty() }

/** Registered modifier types, for the chain `SetModifiers` writes. */
private val MODIFIER_TYPES = propertySchema.modifiers.all().map { it.type }

/** A property key no component in this schema declares, for the half of `SetProperty` that must not be checked. */
private val UNDECLARED_PROPERTY: PropertyKey = PropertyKey("w1Undeclared")

/** An event key no component in this schema declares, for the same reason. */
private val UNDECLARED_EVENT: EventKey = EventKey("w1Undeclared")

// -------------------------------------------------------------------------------------------
// The script: one instance per command family, drawn off the seed
// -------------------------------------------------------------------------------------------

/**
 * One command instance and the family it belongs to.
 *
 * The label rather than `command::class` in the failure message, because a message that reads
 * `class dev.rotalex.lutter.editing.command.Command$UnwrapNode` costs the reader a step that
 * `unwrap` does not. The set comparison further down uses the class, because that is what ties
 * the run to §25.3's closed set.
 */
private class Candidate(val kind: String, val command: Command)

/**
 * The script for one document: one instance per command family, plus a guaranteed refusal.
 *
 * ### Why one instance per family and not every instance
 *
 * A generated document holds tens of nodes, and the instances available for `MoveNode` alone are
 * the product of "which nodes" and "which targets" and "which slots" and "which indices" —
 * thousands. §36 asks for a thousand *documents*, and a corpus of a thousand documents crossed
 * with every instance of twelve commands would be a million patches and a test nobody runs.
 * One instance per family per document is the reading that keeps both halves of the sentence,
 * and the number of families that produce an accepted patch is asserted rather than assumed.
 *
 * ### The script is drawn, never written out by hand
 *
 * Every node, slot, index, wrapper and key below is chosen off `random`, which is a [Random]
 * built from the seed and is the **only** random source in this file. W0's determinism test
 * exists because a single `pool.randomOrNull()` reading `Random.Default` survived every other
 * test in the module; the rule this file follows is the simpler one, which has no exception to
 * remember: construct the generator, or do not draw.
 *
 * The draws are made in a fixed order inside fixed functions, so a seed names one script. That
 * is a weaker guarantee than an independent stream per command — a change to one builder's draw
 * count would move every later builder's choice — and it is the same trade `DocumentGenerator`
 * makes, taken for the same reason: one reproducible script per seed is worth more than twelve
 * independent ones that a reader cannot line up with a seed.
 */
private fun plannedCommands(
    document: UiDocument,
    index: DocumentIndex,
    registry: Registry<ComponentType, ComponentSpec>,
    ids: TestIds,
    random: Random,
    seed: Long,
): List<Candidate> {
    val commands = mutableListOf<Candidate>()

    insertCommand(document, registry, ids, random)?.let { commands += Candidate("insert", it) }
    removeCommand(document, random)?.let { commands += Candidate("remove", it) }
    moveCommands(document, index, registry, random).forEach { commands += Candidate("move", it) }
    duplicateCommand(document, index, random)?.let { commands += Candidate("duplicate", it) }
    wrapCommand(document, index, random)?.let { commands += Candidate("wrap", it) }
    unwrapCommand(document, random)?.let { commands += Candidate("unwrap", it) }
    setPropertyCommand(document, registry, random)?.let { commands += Candidate("setProperty", it) }
    setModifiersCommand(document, random)?.let { commands += Candidate("setModifiers", it) }
    setEventCommand(document, registry, random)?.let { commands += Candidate("setHandler", it) }
    renameCommand(document, random)?.let { commands += Candidate("rename", it) }
    commands += Candidate("addPage", addPageCommand(ids, seed))

    return commands
}

/**
 * `InsertNode` into a declared slot, carrying a subtree taken out of this document.
 *
 * The subtree is captured and remapped rather than written out, which is the clipboard path
 * §25.4 names — `NodeTemplate.capture(doc, ids)` / `InsertNode(...)` with `IdRemapper` — and
 * it means `InsertNodes` and its `RemoveSubtree` inverse are exercised on real rows with real
 * ids. A hand-written one-row leaf would have been simpler and would have tested less.
 *
 * The index is drawn over `0 .. size` inclusive because `size` is a legal insertion and the
 * boundary is one of the things worth reaching; the planner refuses anything past it as
 * `edit.index_out_of_range`, which is a refusal this file counts rather than asserts against.
 *
 * **The subtree has a fallback, and returning `null` instead would be the wrong answer.** When
 * the drawn slot is closed — `gen.SinglePanel`'s `body` is `Only({m3.Text, core.Spacer})` — the
 * document has to hold a row of one of those two types for the insert to have a chance of being
 * accepted, and a document that holds none would otherwise produce *no* `InsertNode` at all.
 * One seed out of a thousand is harmless and every seed out of a thousand is a coverage
 * failure, so the fallback is a fresh one-row template of a registered container: accepted by a
 * `Many` slot with no filter, refused as `edit.slot_child_not_allowed` by a closed one, and
 * either way an `InsertNode` this loop has actually planned.
 */
private fun insertCommand(
    document: UiDocument,
    registry: Registry<ComponentType, ComponentSpec>,
    ids: TestIds,
    random: Random,
): Command? {
    val hosts = document.nodes.ids().filter { slotsOf(document, registry, it).isNotEmpty() }.toList()
    if (hosts.isEmpty()) return null

    val parent = hosts[random.nextInt(hosts.size)]
    val declared = slotsOf(document, registry, parent)
    val slot = declared[random.nextInt(declared.size)]
    val occupied = document.nodes[parent]?.slots?.get(slot.name).orEmpty().size
    val at = random.nextInt(occupied + 1)

    val template = sourceFor(document, slot.accepts, random)
        ?.let { source -> IdRemapper(document, ids).remap(NodeTemplate.capture(document, listOf(source)).nodes) }
        ?: NodeTemplate(listOf(Node(id = ids.nextNodeId(), type = CONTAINER_TYPE)))
    return Command.InsertNode(parent, slot.name, at, template)
}

/**
 * `RemoveNode` over every row, roots included.
 *
 * **Roots are not excluded on purpose.** They are refused as `edit.no_parent`, and a family
 * whose refusals were filtered out beforehand would leave no way to tell "the planner refused"
 * from "the harness never asked". The accepted arm is fed by the non-roots in the same draw.
 */
private fun removeCommand(document: UiDocument, random: Random): Command? {
    val ids = document.nodes.ids().toList()
    if (ids.isEmpty()) return null
    return Command.RemoveNode(ids[random.nextInt(ids.size)])
}

/**
 * `MoveNode`, twice: one move the document may accept and one it must refuse as
 * `edit.cycle`.
 *
 * ### The cycle instance is not drawn, it is built
 *
 * `MoveNode(id, id, ...)` — a node moved into itself — is always available for any node that
 * has a parent, and `planMoveNode` answers it with `edit.cycle` whatever the target's slots
 * look like, because the cycle check runs before the slot checks. Leaving it to a draw would
 * make the refusal census depend on a coincidence of shape, and a census that can come out
 * empty is a census that teaches nothing. The `slot` it names is the one the node itself
 * occupies, because that name is guaranteed to exist somewhere in the document and the planner
 * never reaches the slot question anyway.
 *
 * The second instance is a normal move into a random node that declares at least one slot,
 * with a drawn index over `0 .. size`. It reaches the refusals a real drag reaches:
 * `slot_cardinality` when the target slot is `ExactlyOne` or `ZeroOrOne` and already full,
 * `slot_child_not_allowed` when the filter excludes the node's type, and `slot_undeclared`
 * never — because the target is chosen among nodes that declare slots.
 */
private fun moveCommands(
    document: UiDocument,
    index: DocumentIndex,
    registry: Registry<ComponentType, ComponentSpec>,
    random: Random,
): List<Command> {
    val movers = document.nodes.ids().filter { index.parentOf(it) != null }.toList()
    if (movers.isEmpty()) return emptyList()

    val id = movers[random.nextInt(movers.size)]
    val from = index.parentOf(id) ?: return emptyList()

    val commands = mutableListOf<Command>()
    commands += Command.MoveNode(id, id, from.slot, 0)

    val targets = document.nodes.ids().filter { slotsOf(document, registry, it).isNotEmpty() }.toList()
    if (targets.isEmpty()) return commands

    val newParent = targets[random.nextInt(targets.size)]
    val declared = slotsOf(document, registry, newParent)
    val slot = declared[random.nextInt(declared.size)]
    val occupied = document.nodes[newParent]?.slots?.get(slot.name).orEmpty().size
    commands += Command.MoveNode(id, newParent, slot.name, random.nextInt(occupied + 1))
    return commands
}

/**
 * `DuplicateNode`, over every node that has a position.
 *
 * Nodes with no parent are excluded rather than refused, and this is the one family where that
 * is safe to do: a root is refused for `edit.no_parent` on the first check, and `planRemoveNode`
 * and `planMoveNode` above are already producing that code. Duplicating a subtree under fresh
 * ids is the only command here that mints a whole subtree's worth, so it is also the one the
 * `IdRemapper` contract is most worth exercising against.
 */
private fun duplicateCommand(document: UiDocument, index: DocumentIndex, random: Random): Command? {
    val movers = document.nodes.ids().filter { index.parentOf(it) != null }.toList()
    if (movers.isEmpty()) return null
    return Command.DuplicateNode(movers[random.nextInt(movers.size)])
}

/**
 * `WrapNode` in a drawn registered component, through one of the slots that component declares.
 *
 * The pool is the registered components that declare a slot, drawn uniformly: a wrapper with no
 * slots has no slot to name, so naming one would be building a command rather than a refusal.
 * Every slot of a chosen wrapper is equally likely, which is what puts `slot_child_not_allowed`
 * and `slot_undeclared` in the census — `gen.SinglePanel`'s `body` is `Only({m3.Text,
 * core.Spacer})` and `gen.Banner`'s `banner` is `Except({m3.Text})`, so both filters are
 * reachable from a uniform draw.
 */
private fun wrapCommand(document: UiDocument, index: DocumentIndex, random: Random): Command? {
    if (WRAPPABLE.isEmpty()) return null
    val movers = document.nodes.ids().filter { index.parentOf(it) != null }.toList()
    if (movers.isEmpty()) return null

    val wrapper = WRAPPABLE[random.nextInt(WRAPPABLE.size)]
    val slot = wrapper.slots[random.nextInt(wrapper.slots.size)]
    return Command.WrapNode(movers[random.nextInt(movers.size)], wrapper.type, slot.name)
}

/**
 * `UnwrapNode` over every row, roots and childless nodes included.
 *
 * The same reasoning as `removeCommand`, and it is load-bearing rather than decorative here:
 * a node with children is a minority of a generated tree, because the generator's depth rule
 * puts leaves at the bottom. Filtering to "has children" up front would have meant this family
 * reported one refusal code instead of three, and `edit.nothing_to_unwrap` is a refusal no
 * other builder in this file can produce.
 */
private fun unwrapCommand(document: UiDocument, random: Random): Command? {
    val ids = document.nodes.ids().toList()
    if (ids.isEmpty()) return null
    return Command.UnwrapNode(ids[random.nextInt(ids.size)])
}

/**
 * `SetProperty`, with a declared key half the time and an undeclared one the other half.
 *
 * **The undeclared key is the point, not a shortcut.** `FieldPlanners` states the policy: §25.3
 * names `SlotSpec.accepts` and `SlotSpec.cardinality`, both about slots, so the four setters
 * check nothing but the node's existence. A property key no component declares is therefore an
 * *accepted* command that produces a document `prop.unknown` would report — and whose inverse
 * still has to restore the document. Restricting this to declared keys would have asserted the
 * property over the easy half and left §17's boundary untested.
 *
 * The value is a constant or `null`, and both arms matter: `Node.props` is a map, so an absent
 * key and a key holding nothing are different facts, and the `null` arm is the one that turns a
 * `SetProp` into a removal.
 */
private fun setPropertyCommand(
    document: UiDocument,
    registry: Registry<ComponentType, ComponentSpec>,
    random: Random,
): Command? {
    val ids = document.nodes.ids().toList()
    if (ids.isEmpty()) return null

    val id = ids[random.nextInt(ids.size)]
    val spec = document.nodes[id]?.type?.let { type -> registry[type] }
    val declared = spec?.properties.orEmpty()

    val key = if (declared.isNotEmpty() && random.nextBoolean()) {
        declared[random.nextInt(declared.size)].key
    } else {
        UNDECLARED_PROPERTY
    }

    val value: PropertyValue? = if (random.nextBoolean()) {
        PropertyValue.Const(Value.Str("w1"))
    } else {
        null
    }
    return Command.SetProperty(id, key, value)
}

/**
 * `SetModifiers` with a chain or with nothing, so both halves of the op are planned.
 *
 * The chain it writes is the node's own plus one registered modifier type, which is what a
 * designer does and which makes the `old` side of the op non-empty. `random.nextInt` over an
 * empty registry would throw, so the empty case is the fallback and the node's own chain is
 * never silently discarded.
 */
private fun setModifiersCommand(document: UiDocument, random: Random): Command? {
    val ids = document.nodes.ids().toList()
    if (ids.isEmpty()) return null

    val id = ids[random.nextInt(ids.size)]
    val chain: List<ModifierEntry> = if (MODIFIER_TYPES.isEmpty()) {
        emptyList()
    } else {
        document.nodes[id]?.modifiers.orEmpty() +
            ModifierEntry(MODIFIER_TYPES[random.nextInt(MODIFIER_TYPES.size)])
    }
    return Command.SetModifiers(id, chain)
}

/**
 * `SetEventHandler` with a declared event half the time and an undeclared one the other half.
 *
 * Same reasoning as [setPropertyCommand] and for the same stated policy: `event.unknown` is
 * §17 pass 4's, not a planner's. The sequence written is one `nav.back` step rather than a
 * generated handler, because §26.2's property is about whether the inverse restores what the
 * old side carried, not about what a handler means; the document's own handlers are already in
 * the corpus and `SetEventHandler` over them is what `planSetEventHandler` captures.
 */
private fun setEventCommand(
    document: UiDocument,
    registry: Registry<ComponentType, ComponentSpec>,
    random: Random,
): Command? {
    val ids = document.nodes.ids().toList()
    if (ids.isEmpty()) return null

    val id = ids[random.nextInt(ids.size)]
    val spec = document.nodes[id]?.type?.let { type -> registry[type] }
    val declared = spec?.events.orEmpty()

    val event = if (declared.isNotEmpty() && random.nextBoolean()) {
        declared[random.nextInt(declared.size)].key
    } else {
        UNDECLARED_EVENT
    }

    val actions: ActionSequence? = if (random.nextBoolean()) {
        ActionSequence(listOf(ActionStep(ActionId("nav.back"))))
    } else {
        null
    }
    return Command.SetEventHandler(id, event, actions)
}

/**
 * `RenameNode` with a label or with nothing.
 *
 * `Node.name` is validated by nothing and resolved by nothing, so this is the one command in
 * the set the planner can never refuse except for the node's existence — which is also why the
 * `null` arm matters: it is the only way the four field ops and this one ever produce a `null`
 * on the `new` side of their op.
 */
private fun renameCommand(document: UiDocument, random: Random): Command? {
    val ids = document.nodes.ids().toList()
    if (ids.isEmpty()) return null

    val id = ids[random.nextInt(ids.size)]
    return Command.RenameNode(id, if (random.nextBoolean()) "w1-${id.value}" else null)
}

/**
 * `AddPage`: a screen under a fresh id, and the one row its root names.
 *
 * The page id is derived from the seed rather than from a counter, so a failure message naming
 * one seed does not need a second number to reproduce — and it carries an underscore, which
 * `IdMint`'s alphabet does not, so it cannot collide with a page id the generated document
 * holds. `p_w1_417` would also be safe and is a character shorter; the underscore is kept so
 * that every id this file mints is provably outside the generator's alphabet.
 *
 * The subtree is one row, because D30's `PutNodes` exists to put a page root into a table with
 * no parent and nothing more is needed to exercise the pair `planAddPage` emits.
 */
private fun addPageCommand(ids: TestIds, seed: Long): Command {
    val page = PageId("p_w1_$seed")
    val root = ids.nextNodeId()
    return Command.AddPage(
        page = Page(
            id = page,
            name = "W1Screen",
            route = "/w1/$seed",
            root = root,
        ),
        subtree = NodeTemplate(listOf(Node(id = root, type = CONTAINER_TYPE))),
    )
}