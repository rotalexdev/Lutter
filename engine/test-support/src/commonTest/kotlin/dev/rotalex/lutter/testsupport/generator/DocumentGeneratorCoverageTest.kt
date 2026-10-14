package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.overlay.documentDeclIdOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The corpus reaches every shape §7.1 declares, and the awkward ones really occurred.
 *
 * ### Why no table of expectations appears anywhere in this file
 *
 * Phase 9 shipped seven hand-written expectations that described a different document than the
 * code produced. Every one was the same mistake: somebody wrote down what they expected, it did
 * not match, and the first reaction was to change the expectation rather than to ask why. A
 * seeded generator makes that mistake *impossible* here, because there is nothing to hand-write —
 * seed 17 is not "the document with a banner in it", it is whatever seed 17 builds.
 *
 * Every assertion below therefore has one of two shapes:
 *
 *  * **Plan against document.** The generator declares what it did; this file checks the document
 *    agrees. `plan.componentUsage` against the node table, `plan.filledSlots` against the slots
 *    those nodes carry, `plan.instantiatedComponents` against the `doc.*` nodes in the table.
 *    A generator that lied, or a plan that drifted from the builder, fails here.
 *  * **Registry against corpus.** What the schema declares against what the corpus reached, both
 *    read off the registry rather than off a list in this file. A new `ComponentSpec` is covered
 *    the moment it is registered; deleting one from this file changes nothing.
 *
 * There is no third shape, and in particular no row saying "seed 42 contains a `gen.Banner`",
 * because that is the row that broke seven times.
 *
 * ### Why 250 seeds
 *
 * Coverage here is a coupon-collector problem rather than a pass or fail: which of twelve
 * component types one document reaches is a draw, and the test is whether all of them were
 * reached across the corpus. Two hundred and fifty documents of roughly forty nodes is about ten
 * thousand placements, which makes a registered type never appearing negligible — and if it ever
 * does the failure names what to widen rather than a component to hard-code.
 */
class DocumentGeneratorCoverageTest {

    @Test
    fun `every registered component type is placed, and the plan agrees with the table`() {
        val reached = mutableMapOf<ComponentType, Int>()
        val disagreements = mutableListOf<String>()

        for (seed in seeds(COVERAGE_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            val inTable = generated.document.nodes.ids().map { typeOf(generated, it) }
            for ((placed, planned) in generated.plan.componentUsage) {
                // Written as a read and a write rather than `merge`, which is a `java.util.Map`
                // default method as well as a common one and is on
                // `NoJvmOnlyCollectionMembersTest`'s list for having compiled on desktop and
                // failed on a target nobody here builds any more.
                reached[placed] = (reached[placed] ?: 0) + planned
                val actual = inTable.count { it == placed }
                if (actual == planned) continue
                disagreements += "seed $seed planned $planned node(s) of '$placed' and the table " +
                    "holds $actual.\n" + renderGenerated(generated)
            }
        }

        val missing = registeredComponents().map { it.type }.filterNot { it in reached }
        assertTrue(
            missing.isEmpty(),
            "${missing.size} registered component type(s) never placed across $COVERAGE_SEED_COUNT " +
                "seeds: ${missing.joinToString(", ")}.\n" +
                "Widen COVERAGE_SEED_COUNT, or ask why pickChild cannot reach them.",
        )
        assertTrue(
            disagreements.isEmpty(),
            "${disagreements.size} plan/document disagreement(s).\n" + disagreements.joinToString("\n"),
        )
    }

    @Test
    fun `every declared slot shape is reached, and so is every cardinality and filter`() {
        val reached = mutableSetOf<SlotShape>()
        for (seed in seeds(COVERAGE_SEED_COUNT)) reached += generatedDocument(seed).plan.slotShapes

        val missing = declaredSlotShapes().filterNot { it in reached }
        assertTrue(
            missing.isEmpty(),
            "${missing.size} declared slot shape(s) never reached: ${missing.joinToString(" | ")}",
        )

        // §7.1's whole vocabulary, read off the two registries rather than typed out here: a new
        // `Cardinality` entry or a new filter on a spec is covered the day it is declared.
        assertEquals(
            declaredSlotShapes().map { it.cardinality }.toSet(),
            reached.map { it.cardinality }.toSet(),
            "the corpus did not exercise every Cardinality the schema declares",
        )
        assertEquals(
            declaredSlotShapes().map { it.filter }.toSet(),
            reached.map { it.filter }.toSet(),
            "the corpus did not exercise every ChildFilter the schema declares",
        )
    }

    @Test
    fun `an ExactlyOne slot that was written holds exactly one child`() {
        // The case worth being pedantic about, and the one easiest to get wrong without this: an
        // `ExactlyOne` slot left out of a node's `slots` map reads as present and empty, which the
        // model accepts and `SchemaPass` refuses as `component.slot_cardinality`. "The corpus
        // contains a Button" would not catch that; only counting the children does.
        val offenders = mutableListOf<String>()
        var checked = 0

        for (seed in seeds(COVERAGE_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            val shapes = shapeOf(generated)
            for (filled in generated.plan.filledSlots) {
                if (shapes.getValue(filled.slot to filled.owner).cardinality != Cardinality.ExactlyOne) continue
                checked += 1
                val written = generated.document.nodes[filled.node]?.slots?.get(filled.slot)
                if (written != null && written.size == 1 && filled.children == 1) continue
                offenders += "seed $seed: node ${filled.node} of '${filled.owner}' wrote slot " +
                    "'${filled.slot}' with ${filled.children} child(ren) under ExactlyOne\n" +
                    renderGenerated(generated)
                if (offenders.size >= REPORT_LIMIT) break
            }
            if (offenders.size >= REPORT_LIMIT) break
        }

        assertTrue(checked > 0, "no generated document wrote an ExactlyOne slot at all")
        assertTrue(
            offenders.isEmpty(),
            "${offenders.size} ExactlyOne slot(s) did not hold exactly one child.\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `a child the slot admits is the child that is there`() {
        // The filter half of `SchemaPass.checkSlots`, checked here rather than left to the validity
        // test: a filter the generator quietly ignored would produce documents that are only valid
        // because nothing had yet looked, and the failure it would cause is
        // `component.child_not_allowed` on every node under that slot.
        val offenders = mutableListOf<String>()

        for (seed in seeds(COVERAGE_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            val shapes = shapeOf(generated)
            for (filled in generated.plan.filledSlots) {
                val filter = shapes.getValue(filled.slot to filled.owner).filter
                val node = generated.document.nodes[filled.node] ?: continue
                for (child in node.slots[filled.slot].orEmpty()) {
                    val childType = generated.document.nodes[child]?.type ?: continue
                    if (admits(filter, childType)) continue
                    offenders += "seed $seed: slot '${filled.slot}' of '${filled.owner}' holds " +
                        "'$childType', which '$filter' does not admit"
                    if (offenders.size >= REPORT_LIMIT) break
                }
                if (offenders.size >= REPORT_LIMIT) break
            }
            if (offenders.size >= REPORT_LIMIT) break
        }

        assertTrue(
            offenders.isEmpty(),
            "${offenders.size} slot(s) hold a child their filter does not admit.\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `every theme arrangement §14.2 has occurs, and the document agrees with the plan`() {
        val modes = mutableSetOf<ThemeSelection>()
        val offenders = mutableListOf<String>()

        for (seed in seeds(COVERAGE_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            val document = generated.document
            modes += generated.plan.theme

            val written: Boolean = when (generated.plan.theme) {
                ThemeSelection.None -> document.themes.isEmpty() && document.theme == null
                ThemeSelection.Declared -> document.themes.isNotEmpty() && document.theme == null
                ThemeSelection.Selected ->
                    document.theme != null && document.theme in document.themes
            }
            if (!written) {
                offenders += "seed $seed planned ${generated.plan.theme} and wrote " +
                    "themes=${document.themes.size} selected=${document.theme}"
            }
        }

        assertEquals(
            ThemeSelection.entries.toSet(),
            modes,
            "the corpus did not reach every ThemeSelection; a document with no theme and a " +
                "document with an unselected theme are different documents to §14.2",
        )
        assertTrue(
            offenders.isEmpty(),
            "${offenders.size} document(s) disagree with their plan about the theme.\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `document components are declared, instantiated, and their types read back`() {
        var declared = 0
        var instantiated = 0
        val offenders = mutableListOf<String>()

        for (seed in seeds(COVERAGE_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            declared += generated.plan.declaredComponents.size
            instantiated += generated.plan.instantiatedComponents.size

            for (id in generated.plan.instantiatedComponents) {
                if (id in generated.document.components) continue
                offenders += "seed $seed planned an instance of '$id' and never declared it"
            }
            // `documentDeclIdOf` is the inverse `SchemaOverlay` gives the runtime and codegen, so
            // reading a node's type back through it is what proves the node is an instance of a
            // declaration this document has — which is the whole of §8.4's overlay contract, and
            // the thing Phase 9 could not do from the editing side.
            for (nodeId in generated.document.nodes.ids()) {
                val node = generated.document.nodes.require(nodeId)
                val owner = documentDeclIdOf(node.type.value) ?: continue
                if (owner in generated.document.components) continue
                offenders += "seed $seed: node '$nodeId' has type '${node.type}', which names a " +
                    "declaration '$owner' the document does not have"
            }
            if (offenders.size >= REPORT_LIMIT) break
        }

        assertTrue(declared > 0, "no generated document declared a component")
        assertTrue(instantiated > 0, "no generated document instantiated one")
        assertTrue(
            offenders.isEmpty(),
            "${offenders.size} document-component instance(s) did not resolve.\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `state, expressions and events all occur, including a derived declaration and a branch`() {
        var derived = 0
        var computed = 0
        var handlers = 0
        var branched = 0
        var hostCalls = 0
        var navigations = 0
        val actions = mutableSetOf<ActionId>()
        val dangling = mutableListOf<String>()

        for (seed in seeds(COVERAGE_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            val plan = generated.plan
            derived += plan.derivedStates.size
            computed += plan.computedProperties
            handlers += plan.handlers
            branched += plan.branchedActions.size
            hostCalls += plan.hostCalls.size
            navigations += plan.navigations.size
            actions += plan.actions
            // §13.1 and pass 4 both check that a navigation names a page the document declares;
            // the plan is the cheap place to confirm it, and a mismatch here names its seed.
            for (target in plan.navigations) {
                if (target in generated.document.pages) continue
                dangling += "seed $seed planned a navigation to '$target', which it does not declare"
            }
        }

        assertTrue(derived > 0, "no derived state declaration in the corpus")
        assertTrue(computed > 0, "no computed (PropertyValue.Computed) property in the corpus")
        assertTrue(handlers > 0, "no event handler in the corpus")
        assertTrue(branched > 0, "no action step carrying a branch arm in the corpus")
        assertTrue(hostCalls > 0, "no host.call in the corpus")
        assertTrue(navigations > 0, "no nav.navigate in the corpus")
        assertTrue(dangling.isEmpty(), "${dangling.size} navigation(s) name no declared page:\n" +
            dangling.joinToString("\n"))

        // §11.4's six, read off the registry rather than off a list here: a generator that
        // stopped writing one of them fails this, and a seventh action added to the schema would
        // have to be reached rather than being written into this file.
        assertEquals(
            generatorTestSchema.actions.all().map { it.id }.toSet(),
            actions,
            "the corpus did not use every action the schema registers",
        )
    }

    @Test
    fun `every registered modifier is applied somewhere in the corpus`() {
        val applied = mutableSetOf<ModifierType>()
        for (seed in seeds(COVERAGE_SEED_COUNT)) applied += generatedDocument(seed).plan.modifiers

        assertEquals(
            generatorTestSchema.modifiers.all().map { it.type }.toSet(),
            applied,
            "the corpus did not apply every modifier the schema registers",
        )
    }

    @Test
    fun `ids are unique, and they are not the positions they were built at`() {
        for (seed in seeds(COVERAGE_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            val ids: List<NodeId> = generated.document.nodes.ids().toList()

            assertEquals(
                ids.size,
                ids.distinct().size,
                "seed $seed produced a node table with a repeated id",
            )
            assertEquals(
                ids.size,
                generated.document.nodes.size,
                "seed $seed produced ids the table does not hold",
            )

            // §6.2's rule is that an id is never derived from a position. A counting generator
            // would make `n_1..n_n` carry the construction order, and a round-trip test over them
            // could not tell "the encoder preserved the ids" from "the encoder rebuilt the table in
            // order". Every id here is a prefix plus drawn symbols, so no id in the corpus is a
            // bare counter and the assertion is the whole claim.
            val counters = ids.map { it.value }.filter { SEQUENTIAL.matches(it) }
            assertTrue(
                counters.isEmpty(),
                "seed $seed produced ${counters.size} sequential id(s): ${counters.take(3)}",
            )
        }
    }

    // -------------------------------------------------------------------------------------
    // The two registries this file compares against, read rather than written out
    // -------------------------------------------------------------------------------------

    private fun registeredComponents(): List<ComponentSpec> = generatorTestSchema.components.all()

    private fun typeOf(generated: GeneratedDocument, id: NodeId): ComponentType =
        generated.document.nodes.require(id).type

    /** The cardinality and filter every written slot was filled under, keyed by (slot, owner). */
    private fun shapeOf(generated: GeneratedDocument): Map<Pair<SlotName, ComponentType>, SlotShape> =
        generated.plan.slotShapes.associateBy { it.slot to it.owner }

    /**
     * Every slot shape the static schema declares.
     *
     * Only the static ones, and that is deliberate: a `doc.*` slot's shape comes from
     * `SchemaOverlay.documentComponentSpec`, which synthesizes `Many` with no filter, so a
     * document component cannot widen the vocabulary this test is measuring. A `SlotShape` for
     * one still appears in `plan.slotShapes` — the generator records it — and it simply does not
     * have to match a declaration here.
     */
    private fun declaredSlotShapes(): List<SlotShape> = buildList {
        for (spec in registeredComponents()) {
            for (slot in spec.slots) add(SlotShape(spec.type, slot.name, slot.cardinality, slot.accepts))
        }
    }

    private fun admits(filter: ChildFilter, child: ComponentType): Boolean = when (filter) {
        is ChildFilter.Any -> true
        is ChildFilter.Only -> child in filter.types
        is ChildFilter.Except -> child !in filter.types
    }

    private companion object {
        const val COVERAGE_SEED_COUNT: Int = 250
        const val REPORT_LIMIT: Int = 5

        /** The shape a counting generator would have produced, and this one must never write. */
        val SEQUENTIAL: Regex = Regex("""^n_\d+$""")
    }
}
