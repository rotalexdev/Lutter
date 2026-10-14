package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.serialization.JsonDocumentCodec

/**
 * What the four generator tests share, and why it is this little.
 *
 * ### One schema, built once
 *
 * `generatorSchema()` rebuilds every registry on every call, which is right for a library and
 * wasteful for a test that runs it two hundred times inside a loop. One `val` here is the whole
 * saving, and it is a file-level `val` rather than an `object` member for the reason
 * `NoMutableObjectStateTest` exists: a shared schema is immutable, and a `var` beside it would
 * not be.
 *
 * ### The seeds
 *
 * A fixed, contiguous, zero-based range rather than a draw. A property test's seed has two jobs —
 * reproducibility and coverage — and a contiguous range gives both without a generator of its
 * own: seed 17 is the same document on every machine and on every run, and a hundred consecutive
 * integers are a hundred *different* documents rather than a hundred draws that might collide.
 *
 * The range is a parameter of each test rather than one shared constant because these four tests
 * are also the coverage measurement: determinism needs a handful of seeds, validity needs enough
 * to be worth believing, and coverage needs enough to have seen every registered component.
 * Keeping them apart is what lets a later work unit widen one without widening the others.
 *
 * ### [renderGenerated]
 *
 * Phase 9 learned this the hard way: `NodeTable.toString()` is `NodeTable(size=13)`, so an
 * `assertEquals` over two `UiDocument`s that differ reports two renderings which are byte
 * identical. Every failure in this package therefore quotes the **seed** first — that is the
 * replay handle — and then the document in full, with every field of every row delegated to the
 * record's own `toString()` rather than to a hand-listed subset that goes stale silently when a
 * field is added.
 */
internal val generatorTestSchema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> =
    generatorSchema()

/** One document for [seed], built through a fresh generator exactly as a caller would. */
internal fun generatedDocument(seed: Long): GeneratedDocument =
    DocumentGenerator(generatorTestSchema, seed).generate()

/** [count] consecutive seeds from zero: `0L` up to but not including [count]. */
internal fun seeds(count: Int): List<Long> = (0L until count.toLong()).toList()

/** The canonical bytes a document round-trips through, which is what determinism is about. */
internal fun encodedBytes(document: UiDocument): ByteArray = JsonDocumentCodec.encode(document)

/**
 * The first place two documents differ, as an address, or null when they are equal.
 *
 * ### Why this exists rather than an `assertEquals` over the documents
 *
 * Because `assertEquals` over two `UiDocument`s answers "different" and nothing else. §6.2 makes
 * `NodeTable.equals` order-independent, `NodeTable.toString()` is `NodeTable(size=13)`, and the
 * node table is where almost everything a generator varies lives — so a failure prints two
 * renderings that are byte-identical and the reader has to re-derive the difference by hand.
 *
 * That is not hypothetical: this module's first determinism failure was exactly that, and the
 * report said only "two byte strings differ" over a hundred-line dump of one of them.
 *
 * So the walk below is field by field, in the order the reader would think of, and each branch
 * names its own address: `meta`, `app.startPage`, `pages['p_abc']`, `nodes['n_x'].slots`, and for
 * a node the two whole rows, because `Node` is a data class and its generated `toString` already
 * covers all seven fields — a hand-listed renderer would go stale the day a field is added, and
 * would go stale silently, which is worse than no renderer at all.
 *
 * `app.navigation` is the one field never compared. `NavigationSpec` equals by class and keeps
 * the identity `toString`, so reading it would manufacture a difference that is not there — the
 * same omission [renderGenerated] documents and that `renderDocument` in `:engine:editing`
 * documents at greater length.
 */
internal fun firstDifference(left: UiDocument, right: UiDocument): String? {
    if (left.meta != right.meta) return "meta:\n  left  = ${left.meta}\n  right = ${right.meta}"
    if (left.app.packageName != right.app.packageName) {
        return "app.packageName:\n  left  = '${left.app.packageName}'" +
            "\n  right = '${right.app.packageName}'"
    }
    if (left.app.startPage != right.app.startPage) {
        return "app.startPage:\n  left  = '${left.app.startPage}'\n  right = '${right.app.startPage}'"
    }
    if (left.appState != right.appState) return "appState:\n  left  = ${left.appState}\n  right = ${right.appState}"
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

    // Ids before nodes: a missing or extra node is a difference in its own right, and reporting
    // it as "node 'n_x' is absent from one side" says more than reporting the first node whose
    // row happens not to line up.
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
 * The whole document and its plan as text, for a failure message.
 *
 * Rows in ascending id order so two renderings line up for reading, and every field of every row
 * included so nothing can differ without being visible. `document.app.navigation` is the one
 * field left out: `NavigationSpec` compares equal by class and keeps the identity `toString`, so
 * printing it would manufacture a difference that is not there — the same omission
 * `renderDocument` in `:engine:editing` documents at greater length.
 */
internal fun renderGenerated(generated: GeneratedDocument): String = buildString {
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
