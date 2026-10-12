package dev.rotalex.lutter.editing

import dev.rotalex.lutter.editing.command.freshIds
import dev.rotalex.lutter.editing.command.plannerDocument
import dev.rotalex.lutter.editing.command.plannerSchema
import dev.rotalex.lutter.editing.history.History
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId

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