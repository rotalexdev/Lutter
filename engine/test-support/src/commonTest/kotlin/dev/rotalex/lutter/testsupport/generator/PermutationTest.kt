package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.value.Value
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PLAN §36.2: *"Permutation tests (different construction orders) yield identical
 * bytes/diagnostics/generated files."*
 *
 * ### What "different construction order" means here, and what it must not mean
 *
 * It means **map insertion order, and nothing else**. Every map the document model holds is
 * re-ordered: the page map, the component map, the theme map, the data-model and enum maps, the
 * resource map, `meta.componentVersions`, `nodes` itself, and on each node its `props`, `slots`
 * and `events`, each modifier's `args`, each action step's `args` and `branches`, and the field
 * maps of `Value.Obj` and `Value.MapOf`.
 *
 * It does **not** mean re-ordering a list, and the distinction is load-bearing rather than
 * convenient. `Node.slots` values, `Node.modifiers`, `appState`, `hostFunctions`,
 * `ComponentDecl.params`, `Value.ListOf.items` and `DataModelDecl.fields` are lists, and for each
 * of them order is the meaning: `children[0]` is the first item drawn, `padding` before
 * `background` pads inside it. A document with two of those swapped is a **different document**,
 * not a differently-built one, and permuting it would be a test that passes for the wrong reason —
 * it would be asserting that a layout change does not change the output.
 *
 * ### Why the equality check comes first and is not the assertion
 *
 * W1 established the rule this file is built around: `UiDocument.equals` is order-independent by
 * §6.2, so comparing documents proves nothing about bytes. Which is exactly why
 * `every construction order produced the same document` is here at all — as a **control**, not as
 * the claim. If a permutation accidentally changed a value rather than an order, the byte
 * comparison below would fail for a reason that has nothing to do with order-independence, and a
 * reader would go looking for a defect in the encoder. The control turns that into "the permuter
 * is wrong", which is a far better failure.
 *
 * ### What the shuffle cannot see
 *
 * A seeded `shuffled` is a permutation of the *same* entries, so it can only ever reorder what
 * the identity permutation already contains. It cannot surface a layer that reads an unordered
 * source — a hash map's own iteration order, a set, a registry — because by the time a map
 * reaches the document model it has already lost its hash order. That is a real limit: the only
 * way to see it is a seed-by-seed comparison against a second process, which is what a committed
 * golden is for and what §36.1's `CodegenGoldenTest` still owes this repository.
 */
class PermutationTest {

    /**
     * The control: a permutation changes insertion order and nothing else.
     *
     * `firstDifference` rather than `assertEquals` over the two documents, because
     * `NodeTable.toString()` is `NodeTable(size=13)` and a plain assertion would print two
     * renderings that are byte-identical. The walk returns null exactly when the two are equal,
     * so the assertion *is* the equality.
     */
    @Test
    fun `every construction order produced the same document`() {
        val failures = mutableListOf<String>()

        for (seed in seeds(PERMUTATION_SEED_COUNT)) {
            val original = DocumentGenerator(codegenCorpusView, seed).generate().document
            for (order in ConstructionOrder.entries) {
                val permuted = Permutation(order, Random(seed)).apply(original)
                val difference = firstDifference(original, permuted.document)
                if (difference != null) {
                    failures += "seed $seed, order $order changed more than an insertion order:\n$difference"
                }
            }
            if (failures.size >= REPORT_LIMIT) break
        }

        assertTrue(
            failures.isEmpty(),
            "a permutation altered a document rather than the order it was built in.\n" +
                "Everything after this control is meaningless while this fails.\n" +
                failures.joinToString("\n\n"),
        )
    }

    /**
     * The claim: three construction orders, one canonical byte string.
     *
     * §18's canonical writer sorts every object key and writes the node table by id, so an
     * insertion order cannot survive to the wire. This is the assertion that says so, and it is a
     * byte comparison for W1's reason: two documents can be equal and still have been encoded from
     * different orders.
     */
    @Test
    fun `every construction order produced the same bytes`() {
        val failures = mutableListOf<String>()

        for (seed in seeds(PERMUTATION_SEED_COUNT)) {
            val original = outcomeOf(seed)
            for (order in ConstructionOrder.entries) {
                val permuted = outcomeOf(permutedDocument(seed, order))
                if (!original.documentBytes.contentEquals(permuted.documentBytes)) {
                    failures += "seed $seed, order $order: the canonical bytes differ " +
                        "(${original.documentBytes.size} against ${permuted.documentBytes.size})\n" +
                        firstByteDifference(original.documentBytes, permuted.documentBytes)
                }
            }
            if (failures.size >= REPORT_LIMIT) break
        }

        assertTrue(
            failures.isEmpty(),
            "a construction order reached the wire.\n" +
                "§18's canonical writer sorts every object key and writes nodes by id, so an " +
                "insertion order has nowhere to survive.\n" +
                failures.joinToString("\n\n"),
        )
    }

    /**
     * The claim, for the other half of §36.2's sentence: diagnostics.
     *
     * `Analyzer.analyze` sorts what it returns through §17.2's total order, so a list comparison
     * is a real comparison rather than a comparison of two lists that happened to arrive in the
     * order they were built in. Compared rendered, not as objects, for the reason
     * `renderDiagnostic` gives: two `Diagnostic(...)` renderings of a difference inside the
     * location are byte-identical, and the difference is invisible.
     *
     * Both halves are compared — analysis's and codegen's — because they are produced by
     * different code and sorted by different rules, and a document that refuses produces
     * diagnostics and **no files**, which a files-only comparison would not see at all.
     */
    @Test
    fun `every construction order produced the same diagnostics`() {
        val failures = mutableListOf<String>()

        for (seed in seeds(PERMUTATION_SEED_COUNT)) {
            val original = outcomeOf(seed)
            for (order in ConstructionOrder.entries) {
                val permuted = outcomeOf(permutedDocument(seed, order))
                if (original.analysisDiagnostics != permuted.analysisDiagnostics) {
                    failures += "seed $seed, order $order: the analysis diagnostics differ\n" +
                        "  original = ${original.analysisDiagnostics}\n" +
                        "  order    = ${permuted.analysisDiagnostics}"
                }
                if (original.codegenDiagnostics != permuted.codegenDiagnostics) {
                    failures += "seed $seed, order $order: the codegen diagnostics differ\n" +
                        "  original = ${original.codegenDiagnostics}\n" +
                        "  order    = ${permuted.codegenDiagnostics}"
                }
            }
            if (failures.size >= REPORT_LIMIT) break
        }

        assertTrue(
            failures.isEmpty(),
            "a construction order changed what the engine said about a document.\n" +
                "§17.2 sorts diagnostics by a total order precisely so that this cannot happen.\n" +
                failures.joinToString("\n\n"),
        )
    }

    /**
     * The claim, for the generated files: same paths, same order, same bytes.
     *
     * Compared as a list of path-and-content pairs rather than as `GeneratedFiles`, because
     * `GeneratedFiles.equals` is structural over its own sorted list and would answer "different"
     * without naming which file differed — the `NodeTable(size=N)` trap in another costume. The
     * per-file comparison then locates the first file whose text moved.
     */
    @Test
    fun `every construction order produced the same generated files`() {
        val failures = mutableListOf<String>()
        var checked = 0

        for (seed in seeds(PERMUTATION_SEED_COUNT)) {
            val original = outcomeOf(seed)
            for (order in ConstructionOrder.entries) {
                val permuted = outcomeOf(permutedDocument(seed, order))
                if (original.files.isEmpty() && permuted.files.isEmpty()) continue
                checked += 1
                if (original.files.map { it.path } != permuted.files.map { it.path }) {
                    failures += "seed $seed, order $order: the emitted paths differ\n" +
                        "  original = ${original.files.map { it.path }}\n" +
                        "  order    = ${permuted.files.map { it.path }}"
                    continue
                }
                for ((first, second) in original.files.zip(permuted.files)) {
                    if (first.content != second.content) {
                        failures += "seed $seed, order $order: '${first.path}' differs\n" +
                            firstByteDifference(
                                first.content.encodeToByteArray(),
                                second.content.encodeToByteArray(),
                            )
                    }
                }
            }
            if (failures.size >= REPORT_LIMIT) break
        }

        assertTrue(
            failures.isEmpty(),
            "a construction order changed the generated code.\n" +
                "§16.5's `GeneratedFiles` is a sorted list and §16.3's printer writes from the " +
                "plan, so neither has an insertion order to expose.\n" +
                failures.joinToString("\n\n"),
        )
        assertTrue(checked > 0, "no seed and no order produced a generated file to compare")
    }

    /**
     * The vacuity guard: the permutation is not the identity.
     *
     * Every other case in this file compares outcomes of three orders against one baseline. A
     * permuter that quietly did nothing would make all four agree and every case above pass while
     * proving nothing — the same failure W0 found in "one generator reused is the same as two
     * fresh ones", which could not see a global draw because a reused generator consumes it in
     * step with itself.
     *
     * So the count is checked per seed. `Identity` must move nothing, which is exact. `Reversed`
     * must move something, and it is **certain** to: every generated document declares at least
     * two pages, so its node table holds at least two nodes, and reversing two or more keys always
     * changes the order. `Shuffled` is asserted over the corpus rather than per seed, because a
     * shuffle of *n* entries returns the identity order once in *n*! draws and that is not a
     * probability worth a flaky build over.
     */
    @Test
    fun `the permutation actually reordered something`() {
        val failures = mutableListOf<String>()
        var shuffled = 0

        for (seed in seeds(PERMUTATION_SEED_COUNT)) {
            val original = DocumentGenerator(codegenCorpusView, seed).generate().document
            val identity = Permutation(ConstructionOrder.Identity, Random(seed)).apply(original)
            val reversed = Permutation(ConstructionOrder.Reversed, Random(seed)).apply(original)
            val shuffle = Permutation(ConstructionOrder.Shuffled, Random(seed)).apply(original)
            if (shuffle.reorderings > 0) shuffled += 1

            if (identity.reorderings != 0) {
                failures += "seed $seed: the identity order reordered ${identity.reorderings} map(s)"
            }
            if (reversed.reorderings == 0) {
                failures += "seed $seed: reversing the insertion order changed nothing, so this " +
                    "seed's comparison would have been against the document itself"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "the permutation is not doing what it claims.\n" + failures.joinToString("\n"),
        )
        assertTrue(
            shuffled > 0,
            "the seeded shuffle reordered nothing in $PERMUTATION_SEED_COUNT documents; " +
                "the shuffled comparison proved nothing",
        )
    }

    /**
     * [document] rebuilt in [order], and how many of its maps came out in a different order.
     *
     * The count is what makes the vacuity guard above possible: a permutation function that
     * returns only a document cannot tell "I reordered everything" from "I reordered nothing",
     * because both return the same equal document.
     *
     * `shuffled(random)` takes its source explicitly. The **no-argument** `shuffled()`, and the
     * same for `random()` and `randomOrNull()`, read `kotlin.random.Random.Default` — the
     * platform's global — and a seeded corpus that reached one would be neither reproducible nor
     * isolated from the tests around it. That is the exact defect `DocumentGenerator.step()` had
     * and `DocumentGeneratorDeterminismTest` was written to catch.
     */
    private fun permutedDocument(seed: Long, order: ConstructionOrder): UiDocument {
        val original = DocumentGenerator(codegenCorpusView, seed).generate().document
        return Permutation(order, Random(seed)).apply(original).document
    }

    private companion object {

        /**
         * How many seeds this corpus runs.
         *
         * Half of `DeterminismTest`'s, because this file pays four pipelines per seed rather
         * than two and buys a third of the coverage: a permutation is a comparison of orders
         * within one document, so the axes it can fail along are fewer than the axes a
         * rebuild-and-rebuild comparison has, and seeds are what buy independence on either.
         */
        const val PERMUTATION_SEED_COUNT: Int = 60

        /** Enough failures to show every distinct cause, few enough to stay readable. */
        const val REPORT_LIMIT: Int = 5
    }
}

/** The three ways a document's maps are ordered on the way in. */
private enum class ConstructionOrder {

    /** Insertion order untouched: the baseline the other two are compared against. */
    Identity,

    /** Every map's entries inserted back to front. */
    Reversed,

    /** Every map's entries in a seeded random order. */
    Shuffled,
}

/** A document and how many of its maps the permutation actually re-inserted. */
private class Permuted(val document: UiDocument, val reorderings: Int)

/**
 * Rebuilds a document with every map inserted in a different order.
 *
 * A class rather than a function because the count of maps it moved has to travel out of it, and
 * threading an accumulator through sixteen call sites to collect it would be worse than one
 * mutable field on an object that is constructed per permutation and read once afterwards.
 *
 * Every method here is total and side-effect-free apart from the counter: the same document and
 * the same order always produce the same result, which is what makes the control case above mean
 * something.
 */
private class Permutation(
    private val order: ConstructionOrder,
    private val random: Random,
) {

    private var moved: Int = 0

    /** How many maps, and the node table, came out of this permutation in a new order. */
    val reorderings: Int get() = moved

    /**
     * [document] with every map it holds re-inserted.
     *
     * The node table is rebuilt entry by entry rather than re-wrapped: `NodeTable`'s constructor
     * is `internal` to `:engine:model` and `with` is the only way in, so a different insertion
     * order means a different sequence of `with` calls. That is also the honest thing to permute —
     * §5.5's `NodeTable` documents its backing map's iteration order as *unspecified*, and this
     * is the one construction a test can actually vary.
     */
    fun apply(document: UiDocument): Permuted {
        val ids = document.nodes.ids().toList()
        val insertion = inOrder(ids)
        if (insertion != ids) moved += 1

        var table = NodeTable.EMPTY
        for (id in insertion) table = table.with(node(document.nodes.require(id)))

        val permuted = document.copy(
            meta = document.meta.copy(componentVersions = remap(document.meta.componentVersions)),
            pages = remap(document.pages),
            components = remap(document.components),
            nodes = table,
            dataModels = remap(document.dataModels),
            enums = remap(document.enums),
            themes = remap(document.themes.mapValues { (_, declared) -> theme(declared) }),
            resources = remap(document.resources),
        )
        return Permuted(permuted, moved)
    }

    /**
     * One node, with its three maps and every modifier's and handler's arguments re-inserted.
     *
     * `modifiers` and the lists inside `slots` keep their order, for the reason the class KDoc of
     * `PermutationTest` gives: those are lists, and a list's order is what it means.
     */
    private fun node(source: Node): Node = source.copy(
        props = remap(source.props.mapValues { (_, value) -> property(value) }),
        modifiers = source.modifiers.map { entry -> entry.copy(args = remap(entry.args)) },
        slots = remap(source.slots),
        events = remap(source.events.mapValues { (_, sequence) -> sequence(sequence) }),
    )

    /** A handler: its steps in order, each step's arguments and branch arms re-inserted. */
    private fun sequence(source: ActionSequence): ActionSequence =
        ActionSequence(source.steps.map { step(it) })

    private fun step(source: ActionStep): ActionStep = source.copy(
        args = remap(source.args),
        branches = remap(source.branches.mapValues { (_, arm) -> sequence(arm) }),
    )

    /** Only the two container variants hold maps. `ListOf` is a list, and its order is meaning. */
    private fun property(source: PropertyValue): PropertyValue = when (source) {
        is PropertyValue.Const -> source.copy(value = value(source.value))
        is PropertyValue.Computed -> source
    }

    private fun value(source: Value): Value = when (source) {
        is Value.Obj -> source.copy(fields = remap(source.fields.mapValues { (_, v) -> value(v) }))
        is Value.MapOf -> source.copy(entries = remap(source.entries.mapValues { (_, v) -> value(v) }))
        else -> source
    }

    /**
     * A theme, with all five token maps and the nested defaults re-inserted.
     *
     * `TextStyleSpec` and `ShapeSpec` are typealiases for `Map<PropertyKey, Value>`, so their
     * entries are re-inserted and their values walked. `LiteralPrinter.textStyle` sorts them
     * before it writes them, which is a deliberate determinism measure on the other side of this
     * comparison — and this case is what would notice if it stopped.
     */
    private fun theme(source: ThemeDecl): ThemeDecl = source.copy(
        colors = remap(source.colors),
        typography = remap(source.typography.mapValues { (_, style) -> remap(style) }),
        shapes = remap(source.shapes.mapValues { (_, shape) -> remap(shape) }),
        // The one map whose values are not walked: `dimensions` is `Map<TokenName, Value.Dp>`, so
        // a walk would widen every entry to `Value` and stop being the map the field declares.
        // A `Value.Dp` holds a number and has no map inside it, so there is nothing to reorder
        // below this level anyway.
        dimensions = remap(source.dimensions),
        custom = remap(source.custom.mapValues { (_, v) -> value(v) }),
        componentDefaults = remap(source.componentDefaults.mapValues { (_, defaults) -> remap(defaults) }),
    )

    /**
     * [source] with its entries inserted in this permutation's order.
     *
     * A `LinkedHashMap` rather than `toMap()`, because `toMap()` on a map of more than one entry
     * returns a `LinkedHashMap` and on exactly one entry returns the entry itself as a
     * singleton — which preserves no order at all and would make this a no-op for every
     * single-entry map in the document.
     */
    private fun <K, V> remap(source: Map<K, V>): Map<K, V> {
        val ordered: List<Map.Entry<K, V>> = inOrder(source.entries.toList())
        val built = LinkedHashMap<K, V>(ordered.size)
        for (entry in ordered) built[entry.key] = entry.value
        if (built.keys.toList() != source.keys.toList()) moved += 1
        return built
    }

    /** [source] in this permutation's order. */
    private fun <T> inOrder(source: List<T>): List<T> = when (order) {
        ConstructionOrder.Identity -> source
        ConstructionOrder.Reversed -> source.reversed()
        // The one-argument `shuffled`, which takes its source and does not read `Random.Default`.
        ConstructionOrder.Shuffled -> source.shuffled(random)
    }
}