package dev.rotalex.lutter.serialization

import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.testsupport.generator.DocumentGenerator
import dev.rotalex.lutter.testsupport.generator.GeneratedDocument
import dev.rotalex.lutter.testsupport.generator.ThemeSelection
import dev.rotalex.lutter.testsupport.generator.describe
import dev.rotalex.lutter.testsupport.generator.generatorSchema
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PLAN §36.1's bullet, over a thousand documents rather than a fixture: *"the document
 * serializes to canonical JSON and deserializes back to an equal document;
 * `encode(decode(encode(d))) == encode(d)` for at least 1,000 generated documents"*.
 *
 * ### Both halves, and why they are two tests
 *
 * The bullet is two claims about two different layers. `decode(encode(d)) == d` asks whether
 * the **codec** preserves the model, and it is the one a person debugging an editor actually
 * needs. `encode(decode(encode(d))) == encode(d)` asks whether a decoded document re-encodes to
 * the *same bytes*, which is §18's canonicality claim and is strictly stronger than the first:
 * two documents can be equal and still have been produced by a writer whose field order
 * depends on a hash map's iteration order, because the second encode is the one that would show
 * it. A test that only asserted the first would pass over a codec that is correct on model
 * equality and non-canonical on the wire, which is the whole of §18.
 *
 * ### Why a thousand, and what makes the corpus worth the runtime
 *
 * A thousand is the plan's number, and the honest answer to "is it padded" is **the shapes are
 * saturated well before the end and the tail buys independence, not variety**. The generator's
 * own `DocumentGeneratorCoverageTest` already proves that 250 seeds place every registered
 * component type, reach every declared slot shape and therefore every `Cardinality` and every
 * `ChildFilter`, and reach all three `ThemeSelection`s. There is no twelfth seed that finds a
 * thirteenth category.
 *
 * What seeds 250 and onward buy is a thousand *independent draws of the same categories*, and
 * that is not padding for a property like this one. The interesting failures in a codec are not
 * "a field is dropped" — a dropped field is found by the first handful of documents, because
 * the generator writes every declared field on every node. They are position- and
 * depth-dependent: a value that only misbehaves at the third decimal of a float, a token only
 * misread when the theme vocabulary came from a `Declared` theme rather than from Material, an
 * `Obj` whose map key order differs when the model has two fields rather than one. Those are
 * decided per document, so a corpus of 250 has a one-in-four chance of not containing the
 * unlucky one and a corpus of 1,000 does not have that chance.
 *
 * The third test in this file is the check on that claim rather than a restatement of it: it
 * asserts the coverage the argument rests on, against the registries rather than against a
 * list written out here, and prints a census so a reader can see what the corpus actually is.
 *
 * ### Failure messages, and the one that is not optional here
 *
 * Every message leads with the seed, because `DocumentGenerator(generatorSchema(), 417)` is the
 * whole reproduction. Beyond that, `NodeTable.toString()` is `NodeTable(size=13)`, so an
 * `assertEquals` over two documents reports a difference inside the table as two renderings
 * that are byte-identical — [firstDifference] below names the field instead, and is copied from
 * `GeneratorFixtures.kt` in `:engine:test-support` for the reason `treeOf` is copied in
 * `:engine:editing`: it is `internal` to another module's *test* source set, and no Gradle
 * configuration publishes one module's tests to another module's tests.
 *
 * The second half of the discipline is [firstByteDifference], and it is this module's own
 * lesson rather than a borrowed one. A codec failure here is usually *two byte arrays*, and
 * `contentEquals` says only "different"; two `ByteArray`s render as `[12, 45, 33, …]` and name
 * nothing. Naming the first offset that differs costs ten lines and is the difference between a
 * message that locates a defect and one that only says there is one.
 *
 * ### What this file does not claim
 *
 * It does not claim the codec is *validating*: §17's passes live in `:engine:analysis`, and
 * `:engine:serialization` may not see them. Whether a decoded document is a document the engine
 * can render is `DocumentGeneratorValidityTest`'s question, and this file assumes it.
 */
class RoundTripPropertyTest {

    /**
     * `decode(encode(d)) == d`, for every generated document in the corpus.
     *
     * The comparison is [firstDifference] rather than `assertEquals` over the two documents,
     * and it is not a stylistic choice: `firstDifference` returns `null` exactly when the two
     * documents are equal, so the assertion below *is* the property, and what it adds over an
     * `assertEquals` is a message that names the field. `UiDocument.equals` is still what
     * decides the answer — the walk is a renderer, not an oracle.
     */
    @Test
    fun `a generated document decodes back to the same document`() {
        val failures = mutableListOf<String>()

        for (seed in seeds(PROPERTY_SEED_COUNT)) {
            val generated = DocumentGenerator(propertySchema, seed).generate()
            val encoded = JsonDocumentCodec.encode(generated.document)

            // A decode failure is recorded rather than thrown, so the seed that produced it is
            // the first thing the message says. `SerializationException` is the type
            // `DocumentCodec.decode` documents; anything else is a defect somewhere else and
            // arrives with its own stack trace, which is more useful than a line of prose.
            val decoded: UiDocument? = try {
                JsonDocumentCodec.decode(encoded).document
            } catch (failure: SerializationException) {
                failures += "seed $seed: decoding the codec's own encoding failed: ${failure.message}\n" +
                    renderGenerated(generated)
                null
            }

            if (decoded != null) {
                val difference = firstDifference(generated.document, decoded)
                if (difference != null) {
                    failures += "seed $seed: decode(encode(d)) is not d:\n$difference\n" +
                        renderGenerated(generated)
                }
            }

            if (failures.size >= REPORT_LIMIT) break
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} of $PROPERTY_SEED_COUNT generated documents did not survive a decode.\n" +
                "Replay any one with DocumentGenerator(generatorSchema(), <seed>).generate().\n" +
                failures.joinToString("\n\n"),
        )
    }

    /**
     * `encode(decode(encode(d))) == encode(d)`: three encodes and two decodes, compared as
     * bytes.
     *
     * Bytes rather than documents, and that is the whole difference between this and the test
     * above. §6.2 makes document equality order-independent, so a writer that emitted the same
     * fields in a different order would satisfy `decode(encode(d)) == d` and violate §18's
     * canonical form. Comparing the byte strings is what §18's claim actually is.
     */
    @Test
    fun `encoding a document through the codec twice gives the same bytes`() {
        val failures = mutableListOf<String>()

        for (seed in seeds(PROPERTY_SEED_COUNT)) {
            val generated = DocumentGenerator(propertySchema, seed).generate()
            val first = JsonDocumentCodec.encode(generated.document)

            val reEncoded: ByteArray? = try {
                val once = JsonDocumentCodec.decode(first).document
                val second = JsonDocumentCodec.encode(once)
                JsonDocumentCodec.encode(JsonDocumentCodec.decode(second).document)
            } catch (failure: SerializationException) {
                failures += "seed $seed: a second pass through the codec failed: ${failure.message}\n" +
                    renderGenerated(generated)
                null
            }

            if (reEncoded != null && !first.contentEquals(reEncoded)) {
                failures += "seed $seed: encode(decode(encode(d))) is not encode(d): " +
                    "${first.size} bytes against ${reEncoded.size}.\n" +
                    "${firstByteDifference(first, reEncoded)}\n" +
                    renderGenerated(generated)
            }

            if (failures.size >= REPORT_LIMIT) break
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} of $PROPERTY_SEED_COUNT generated documents re-encoded to different bytes.\n" +
                "A difference here with equal documents is §18's canonical form, not §17's validity.\n" +
                failures.joinToString("\n\n"),
        )
    }

    /**
     * The corpus is the one the argument above says it is, checked against the registries.
     *
     * Every expectation is read off `propertySchema` or off `plan`, never off a list written
     * out here: a component registered tomorrow is covered the day it is registered, and a test
     * that enumerated the twelve types by hand would keep passing over a thirteenth that no
     * generated document ever placed.
     *
     * **The component-type row is a subset check, and it has to be.** `plan.componentUsage`
     * counts the `doc.*` instances along with the registered types — its own KDoc says so, and
     * most documents in this corpus declare a component and place one — so the set it produces
     * is a strict superset of the registry's on any corpus that does. Equality here would have
     * been asserting a second claim, "the corpus places nothing the registry does not declare",
     * which is false for a reason no amount of widening `PROPERTY_SEED_COUNT` would fix.
     *
     * The node counts are asserted as a *spread* rather than as a number. A corpus whose
     * documents were all the same size would satisfy every coverage claim above and would be
     * testing one document a thousand times; a spread is what makes the count mean anything,
     * and it is the cheapest honest proxy for "these really are a thousand documents".
     */
    @Test
    fun `the corpus reaches every shape the schema declares`() {
        val placed = mutableSetOf<ComponentType>()
        val cardinalities = mutableSetOf<Cardinality>()
        val filters = mutableSetOf<ChildFilter>()
        val themes = mutableSetOf<ThemeSelection>()
        var withInstances = 0
        var smallest = Int.MAX_VALUE
        var largest = 0
        var total = 0

        for (seed in seeds(PROPERTY_SEED_COUNT)) {
            val generated = DocumentGenerator(propertySchema, seed).generate()
            placed += generated.plan.componentUsage.keys
            cardinalities += generated.plan.slotShapes.map { it.cardinality }
            filters += generated.plan.slotShapes.map { it.filter }
            themes += generated.plan.theme
            if (generated.plan.instantiatedComponents.isNotEmpty()) withInstances += 1
            val size = generated.document.nodes.size
            smallest = minOf(smallest, size)
            largest = maxOf(largest, size)
            total += size
        }

        val census = "seeds 0 until $PROPERTY_SEED_COUNT; " +
            "nodes $smallest to $largest, ${total / PROPERTY_SEED_COUNT} on average; " +
            "${withInstances} documents carrying a document-component instance"

        // A subset check and not an equality, and the difference is the whole of the assertion:
        // `GenerationPlan.componentUsage` counts the `doc.*` instances as well as the registered
        // types, which is what its own KDoc says it does, so a document that declares a
        // component and places one adds a type no static registry holds. The claim this file
        // actually makes is that every registered type is reached, and that is what is checked —
        // the same check `DocumentGeneratorCoverageTest` makes from the other side, off the same
        // registry rather than off a list written here.
        val registered: Set<ComponentType> = propertySchema.components.all().map { it.type }.toSet()
        val neverPlaced: List<ComponentType> = registered.filterNot { it in placed }
        assertTrue(
            neverPlaced.isEmpty(),
            "${neverPlaced.size} registered component type(s) the corpus never placed: " +
                "${neverPlaced.joinToString(", ")}.\n" +
                "Widen PROPERTY_SEED_COUNT, or ask why DocumentGenerator cannot reach it.\n" +
                census,
        )
        assertEquals(
            propertySchema.components.all().flatMap { it.slots }.map { it.cardinality }.toSet(),
            cardinalities,
            "the corpus did not exercise every Cardinality the schema declares\n$census",
        )
        assertEquals(
            propertySchema.components.all().flatMap { it.slots }.map { it.accepts }.toSet(),
            filters,
            "the corpus did not exercise every ChildFilter the schema declares\n$census",
        )
        assertEquals(
            ThemeSelection.entries.toSet(),
            themes,
            "a document with no theme and a document with an unselected theme are different " +
                "documents to §14.2, and the corpus has to hold both\n$census",
        )
        assertTrue(
            withInstances > 0,
            "no generated document placed a doc.* instance, so §8.4's overlay was never " +
                "round-tripped\n$census",
        )
        assertTrue(
            largest > smallest,
            "every generated document had ${smallest} nodes: a corpus of one document " +
                "repeated a thousand times\n$census",
        )
    }
}

// -------------------------------------------------------------------------------------------
// The schema, and the corpus
// -------------------------------------------------------------------------------------------

/**
 * The schema the corpus is generated against and read back against.
 *
 * One instance rather than a call per seed, for the reason `GeneratorFixtures.kt` gives: the
 * registry is immutable and rebuilding twelve component specs a thousand times is a thousand
 * allocations doing nothing. `generatorSchema()` deliberately does *not* cache, because a shared
 * static schema is shared mutable state by another name — so the caching is here, in the test
 * that wants it.
 */
private val propertySchema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> =
    generatorSchema()

/**
 * How many seeds the corpus has: `0L` up to but not including this many.
 *
 * Contiguous and zero-based rather than drawn, for the two reasons a property test needs at
 * once. Reproducibility, because seed 417 is the same document on every machine and every run;
 * and *distinctness*, because a hundred consecutive integers are a hundred different documents
 * rather than a hundred draws that might collide.
 *
 * PLAN §36 asks for at least 1,000 and this is exactly 1,000. The class KDoc argues what the
 * number buys and what it does not.
 */
private const val PROPERTY_SEED_COUNT: Int = 1_000

/** Enough failures to show every distinct cause, few enough to stay readable. */
private const val REPORT_LIMIT: Int = 5

private fun seeds(count: Int): List<Long> = (0L until count.toLong()).toList()

// -------------------------------------------------------------------------------------------
// Naming a difference, because two node tables print identically
// -------------------------------------------------------------------------------------------

/**
 * The first place two documents differ, as an address, or `null` when they are equal.
 *
 * ### Why this is copied and not shared
 *
 * `GeneratorFixtures.kt` in `:engine:test-support` carries the same function, written for
 * `:engine:test-support`'s own tests. It is `internal`, and it lives in a **test** source set:
 * `commonTestImplementation` puts another module's `commonMain` on this test compile classpath
 * and none of its tests, so there is no Gradle configuration that would hand the original over.
 * Copying is the move this repository has already made for the same reason — `treeOf` in
 * `PatchRoundTripTest` and in `CommandPlannerRoundTripTest` is the precedent, and its KDoc says
 * the same thing about widening a merged work unit's test visibility. The body is the same walk
 * so that a difference is described the same way wherever it is found.
 *
 * ### What it walks, and the one field it does not
 *
 * Field by field, in the order a reader would think of, and each branch names its own address:
 * `meta`, `app.startPage`, `pages['p_abc']`, `nodes['n_x']`. For a node the two whole rows are
 * printed, because `Node` is a data class and its generated `toString` covers all seven fields
 * — a hand-listed renderer would go stale the day a field is added, and would go stale
 * silently, which is worse than no renderer at all.
 *
 * `app.navigation` is never compared. `NavigationSpec` equals by class and keeps the identity
 * `toString`, so reading it would manufacture a difference that is not there.
 */
private fun firstDifference(left: UiDocument, right: UiDocument): String? {
    if (left.meta != right.meta) return "meta:\n  left  = ${left.meta}\n  right = ${right.meta}"
    if (left.app.packageName != right.app.packageName) {
        return "app.packageName:\n  left  = '${left.app.packageName}'" +
            "\n  right = '${right.app.packageName}'"
    }
    if (left.app.startPage != right.app.startPage) {
        return "app.startPage:\n  left  = '${left.app.startPage}'\n  right = '${right.app.startPage}'"
    }
    if (left.appState != right.appState) {
        return "appState:\n  left  = ${left.appState}\n  right = ${right.appState}"
    }
    if (left.dataModels != right.dataModels) {
        return "dataModels:\n  left  = ${left.dataModels}\n  right = ${right.dataModels}"
    }
    if (left.enums != right.enums) return "enums:\n  left  = ${left.enums}\n  right = ${right.enums}"
    if (left.hostFunctions != right.hostFunctions) {
        return "hostFunctions:\n  left  = ${left.hostFunctions}\n  right = ${right.hostFunctions}"
    }
    if (left.themes != right.themes) return "themes:\n  left  = ${left.themes}\n  right = ${right.themes}"
    if (left.theme != right.theme) return "theme:\n  left  = ${left.theme}\n  right = ${right.theme}"
    if (left.resources != right.resources) {
        return "resources:\n  left  = ${left.resources}\n  right = ${right.resources}"
    }

    for (key in left.pages.keys + right.pages.keys) {
        val found = left.pages[key]
        val other = right.pages[key]
        if (found == other) continue
        return "pages['$key']:\n  left  = $found\n  right = $other"
    }
    for (key in left.components.keys + right.components.keys) {
        val found = left.components[key]
        val other = right.components[key]
        if (found == other) continue
        return "components['$key']:\n  left  = $found\n  right = $other"
    }

    // Ids before rows: a missing or extra node is a difference in its own right, and reporting
    // it as "node 'n_x' is absent from one side" says more than reporting the first row whose
    // content happens not to line up.
    val leftIds = left.nodes.ids().toList()
    val rightIds = right.nodes.ids().toList()
    if (leftIds != rightIds) {
        val onlyLeft = leftIds.filterNot { it in rightIds }
        val onlyRight = rightIds.filterNot { it in leftIds }
        return "node ids differ:\n  only in left  = $onlyLeft\n  only in right = $onlyRight" +
            "\n  left order  = $leftIds\n  right order = $rightIds"
    }
    for (id in leftIds) {
        val found = left.nodes[id]
        val other = right.nodes[id]
        if (found == other) continue
        return "nodes['$id']:\n  left  = $found\n  right = $other"
    }
    return null
}

/**
 * Where two byte arrays first differ, as an offset and the two runs around it.
 *
 * ### Why this is here and not left to the reader
 *
 * `contentEquals` answers "different" and nothing else, and the two things being compared are
 * `ByteArray`s — which render as `[123, 45, 33, …]` and locate nothing. The failures this
 * module has actually had were all of that shape: a writer that got one field's ordering wrong
 * produces two arrays that differ at one offset and agree everywhere else, and the offset is
 * the entire finding.
 *
 * Sixteen bytes of context either side is enough to read the two documents' text by eye without
 * pasting a kilobyte into a failure message. When the arrays are the same length the offset is
 * the answer; when they are not, the lengths are printed above it anyway by the caller, because
 * a length disagreement is usually the real story and an offset would hide it.
 */
private fun firstByteDifference(left: ByteArray, right: ByteArray): String {
    val shared = minOf(left.size, right.size)
    var at = 0
    while (at < shared && left[at] == right[at]) at += 1
    if (at == shared && left.size == right.size) {
        return "the two byte strings are equal, which this message should never be reached from"
    }

    val from = maxOf(0, at - CONTEXT_BYTES)
    val leftRun = left.copyOfRange(from, minOf(left.size, at + CONTEXT_BYTES)).decodeToString()
    val rightRun = right.copyOfRange(from, minOf(right.size, at + CONTEXT_BYTES)).decodeToString()
    return "first difference at offset $at, from $from:\n" +
        "  left  = $leftRun\n" +
        "  right = $rightRun"
}

/** Bytes of context either side of the first difference. Enough to read; not enough to bury. */
private const val CONTEXT_BYTES: Int = 16

// -------------------------------------------------------------------------------------------
// The whole document, for a failure message
// -------------------------------------------------------------------------------------------

/**
 * The whole document and the plan that produced it, as text.
 *
 * Rows in ascending id order so two renderings line up for reading, and every field of every
 * row delegated to the record's own `toString` rather than to a hand-listed subset. The plan
 * line is [GeneratedDocument]'s whole reason for existing: it says what the generator *meant*
 * by the document below it, so a failure that turns out to be a generator bug rather than a
 * codec bug is recognisable from the message instead of from a second debugging session.
 */
private fun renderGenerated(generated: GeneratedDocument): String = buildString {
    val document = generated.document
    append("seed          ").append(generated.seed).append('\n')
    append("plan          ").append(generated.plan.describe()).append('\n')
    append("meta          ").append(document.meta).append('\n')
    append("app           ").append(document.app.packageName)
    append(" startPage=").append(document.app.startPage).append('\n')
    append("pages         ")
    append(document.pages.values.sortedBy { it.id.value }.joinToString(" | ")).append('\n')
    append("components    ")
    append(document.components.values.sortedBy { it.id.value }.joinToString(" | ")).append('\n')
    append("appState      ").append(document.appState).append('\n')
    append("dataModels    ").append(document.dataModels).append('\n')
    append("enums         ").append(document.enums).append('\n')
    append("hostFunctions ").append(document.hostFunctions).append('\n')
    append("themes        ").append(document.themes).append('\n')
    append("theme         ").append(document.theme).append('\n')
    append("nodes\n")
    for (id in document.nodes.ids()) {
        append("  ").append(document.nodes.require(id)).append('\n')
    }
}