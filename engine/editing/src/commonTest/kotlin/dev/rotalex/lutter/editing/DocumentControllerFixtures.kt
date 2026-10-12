package dev.rotalex.lutter.editing

import dev.rotalex.lutter.editing.command.freshIds
import dev.rotalex.lutter.editing.command.plannerDocument
import dev.rotalex.lutter.editing.command.plannerSchema
import dev.rotalex.lutter.editing.history.History
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import kotlin.test.fail

/**
 * One function, composing three fixtures that already exist.
 *
 * `PlannerFixtures` in the sibling `command` package is this module's document, schema and id
 * vocabulary, and it is what a controller test starts from — reusing it is the point of it
 * having been written down once. What is added here is only the wiring: a schema and a
 * generator become a controller.
 *
 * **No new vocabulary is introduced here**, and that is what makes the file worth existing. A
 * second set of nodes, component types and properties would be a third fixture style for a
 * module that already has two, and the two existing tests would then be able to disagree about
 * what a `Column` is without either of them noticing.
 *
 * The type arguments are spelled rather than left to inference because this line is read dozens
 * of times and the inference is not obvious: `plannerSchema` binds its three registries the
 * planners never read to [String], so that is what the controller's own three come out as.
 */
internal fun controller(
    initial: UiDocument = plannerDocument(),
    history: History = History(),
): DocumentController<String, String, String> = DocumentController(initial, plannerSchema(), freshIds(), history)

/**
 * The label of [id] in [document].
 *
 * A helper rather than a chain of `require` calls at each site, because the interesting
 * assertion in a controller test is usually about one field of one row and the node table is
 * the only way to reach it.
 */
internal fun labelOf(document: UiDocument, id: NodeId): String? = document.nodes.require(id).name

/**
 * Asserts two documents are equal, and says which row and which field differs when they are
 * not.
 *
 * ### Why this exists rather than `assertEquals` on the documents
 *
 * `NodeTable.toString()` is `NodeTable(size=13)`, so an `assertEquals` over two `UiDocument`s
 * reports two renderings that are byte-identical. That has now cost this module a whole
 * investigation for a difference that was one field of one row, and a diagnostic that cannot name
 * the difference is a diagnostic that has to be re-derived by hand.
 *
 * **The obvious fix would have made this failure worse, not better.** The `treeOf` helper that
 * `PatchRoundTripTest` and `CommandPlannerRoundTripTest` both use prints the id, the type and the
 * slots, and nothing else. A rename changes `Node.name`, which `treeOf` does not print, so the
 * two renderings would have been *identical strings* and the assertion would have failed with an
 * empty diff. Four of a node's seven fields — `name`, `props`, `modifiers` and `events` — are
 * exactly the ones a controller test spends its time changing.
 *
 * So this renders the **whole row** instead of an enumerated subset, and it does that by
 * delegating to each record's own `toString()` rather than by hand-listing fields. A hand-listed
 * renderer goes stale the day a field is added, and would go stale silently: a field it forgets
 * is a field whose differences stop being visible, which is the whole defect this exists to
 * remove. `Node` is a data class, so its generated `toString()` covers all seven fields and
 * keeps covering them for free.
 *
 * ### The equality is still the type's, not this function's
 *
 * [renderDocument] is for the *message* only. The comparison is `UiDocument.equals`, so nothing
 * is decided by the renderer and a renderer that were somehow incomplete could not make a
 * difference pass.
 *
 * ### The one field deliberately left out, and why
 *
 * `document.app`. `AppSpec` is a data class but its `navigation` field is a `NavigationSpec`,
 * which is **not** — it has `equals(other) = other is NavigationSpec` and a constant `hashCode`,
 * and therefore the default identity `toString`. Two equal documents built by two separate calls
 * to `plannerDocument()` hold two different `NavigationSpec` instances, so printing one would
 * manufacture a difference that does not exist and send whoever reads the message looking at the
 * one field that is never the wrong one. A field that cannot be rendered without lying is left
 * out, and the omission is this sentence rather than a surprise.
 */
internal fun assertSameDocument(expected: UiDocument, actual: UiDocument, context: String) {
    if (expected == actual) return

    fail(
        "$context\n" +
            "--- expected ---\n${renderDocument(expected)}\n" +
            "--- actual ---\n${renderDocument(actual)}",
    )
}

/**
 * A whole document as text, for a failure message.
 *
 * Rows in ascending id order so that two renderings line up for reading, and every field of every
 * row included so that nothing can differ without being visible. See [assertSameDocument] for why
 * this is not the `treeOf` shape the other tests in this module use, and for the one field it
 * leaves out.
 */
internal fun renderDocument(document: UiDocument): String = buildString {
    append("meta          ").append(document.meta).append('\n')
    append("pages         ").append(document.pages.values.sortedBy { it.id.value }.joinToString(" | ")).append('\n')
    append("components    ").append(document.components).append('\n')
    append("appState      ").append(document.appState).append('\n')
    append("dataModels    ").append(document.dataModels).append('\n')
    append("enums         ").append(document.enums).append('\n')
    append("hostFunctions ").append(document.hostFunctions).append('\n')
    append("themes        ").append(document.themes).append('\n')
    append("theme         ").append(document.theme).append('\n')
    append("resources     ").append(document.resources).append('\n')
    append("nodes\n")
    for (id in document.nodes.ids()) {
        append("  ").append(document.nodes.require(id)).append('\n')
    }
}