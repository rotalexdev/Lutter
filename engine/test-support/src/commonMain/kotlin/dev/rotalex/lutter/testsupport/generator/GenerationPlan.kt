package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.ChildFilter

// What one generated document is, and what the generator meant by it.
//
// ### Why the plan exists at all
//
// Phase 9's recurring defect was a hand-written expectation describing a *different* document
// than the code produced — seven instances, every one of them a red build and every one of them
// a test that was wrong rather than the code. The cure is not more care with the expectation;
// it is removing the second description. A test that asserts "some document has a theme
// selection" has to write down, by hand, which seed produced one, and that hand-written seed is
// the defect waiting to happen.
//
// So the generator returns what it *decided* next to what it *built*, and the tests read the
// decision. Every assertion in `DocumentGeneratorCoverageTest` compares the plan against the
// document it came with — `plan.componentUsage` against `document.nodes`, `plan.filledSlots`
// against the slots those nodes carry — rather than against a list somebody wrote down. When the
// generator changes what it intends, the test changes with it or fails, which is the property
// the Phase 9 tables lacked.
//
// ### What is deliberately not here
//
// No expected property value, no expected node count, no expected tree shape. Those are the
// hand-written document descriptions this file exists to remove, and a document the generator
// builds from a seed does not have one to write down: seed 7 is not "the one with three pages
// and a banner", it is whatever seed 7 produces.

/**
 * One generated document, and the plan that produced it.
 *
 * A plain class rather than a data class, for the reason `DocumentIndex` is: the interesting
 * identity is the run that made it, and a generated equality over a whole `UiDocument` would
 * cost more than the one question a test asks ("is this the same document?"), which
 * `UiDocument.equals` answers better than a wrapper would.
 */
public class GeneratedDocument(

    /** The seed this document came from, and the first word of every failure about it. */
    public val seed: Long,

    /** The document. */
    public val document: UiDocument,

    /** What the generator decided while building [document]. */
    public val plan: GenerationPlan,
)

/** Everything [DocumentGenerator] decided on the way to one document. */
public class GenerationPlan(

    /** Node counts per component type, including the `doc.*` instances. */
    public val componentUsage: Map<ComponentType, Int>,

    /** Every declared slot shape the generator placed a node of the owner under. */
    public val slotShapes: List<SlotShape>,

    /** Every slot the generator wrote, with the number of children it wrote into it. */
    public val filledSlots: List<FilledSlot>,

    /** The modifier types the generator applied, in the order it applied them. */
    public val modifiers: List<ModifierType>,

    /** The document-defined components the document declares. */
    public val declaredComponents: List<ComponentDeclId>,

    /** The document-defined components the document placed at least one instance of. */
    public val instantiatedComponents: List<ComponentDeclId>,

    /** How the document's theme vocabulary was decided. See [ThemeSelection]. */
    public val theme: ThemeSelection,

    /** How many `StateDecl`s the document holds across `appState`, pages and components. */
    public val stateDeclarations: Int,

    /** The derived declarations, whose `initial` is null and whose `derived` is not. */
    public val derivedStates: List<StateId>,

    /** How many node properties the generator wrote as `PropertyValue.Computed`. */
    public val computedProperties: Int,

    /** How many event handlers the generator wrote, counted over every node. */
    public val handlers: Int,

    /** The action ids every handler used, top level and inside branch arms alike. */
    public val actions: List<ActionId>,

    /** The subset of [actions] that appeared on a step carrying at least one branch. */
    public val branchedActions: List<ActionId>,

    /** The `HostFunctionDecl.name`s a `host.call` step named. */
    public val hostCalls: List<String>,

    /** The pages a `nav.navigate` step named, always ones the document declares. */
    public val navigations: List<PageId>,
)

/**
 * The plan as text, because a plan nobody can read is a plan nobody can use.
 *
 * Written out field by field rather than left to the default `toString`, which would print
 * `GenerationPlan@1f3a5c`. A coverage failure reports which shape was never reached, and the
 * shape has to be nameable in the message; a `UiDocument` cannot say which of *its* fields the
 * generator meant to write, which is the whole reason this type exists.
 */
public fun GenerationPlan.describe(): String =
    "components=${componentUsage.keys.joinToString(",")} " +
        "slotShapes=${slotShapes.size} filledSlots=${filledSlots.size} " +
        "modifiers=${modifiers.distinct().joinToString(",")} " +
        "declared=${declaredComponents.size} instantiated=${instantiatedComponents.size} " +
        "theme=$theme state=$stateDeclarations derived=${derivedStates.size} " +
        "computed=$computedProperties handlers=$handlers " +
        "actions=${actions.distinct().joinToString(",")} branched=${branchedActions.size} " +
        "hostCalls=${hostCalls.size} navigations=${navigations.size}"

/**
 * One slot as its spec declares it, deduplicated across the nodes it was encountered on.
 *
 * [cardinality] and [filter] are read off the `SlotSpec` rather than restated, which is what
 * makes "every `Cardinality` and every `ChildFilter` the schema declares is exercised" a
 * statement about the schema instead of a statement about a list in a test.
 */
public data class SlotShape(
    public val owner: ComponentType,
    public val slot: SlotName,
    public val cardinality: Cardinality,
    public val filter: ChildFilter,
)

/**
 * One slot on one node, with the number of children the generator put in it.
 *
 * [children] is the assertion the awkward shapes need. An `ExactlyOne` slot that reads as
 * present and holds nothing is valid in the model and refused by `SchemaPass`, so "the
 * generator produced an `ExactlyOne` slot" is not a coverage claim until the count says one.
 */
public data class FilledSlot(
    public val node: NodeId,
    public val owner: ComponentType,
    public val slot: SlotName,
    public val children: Int,
)

/**
 * The three ways §14.2 lets a document resolve its tokens, which is also the three ways
 * [DocumentGenerator] decides what a token name may be.
 *
 * [None] and [Declared] are the same field (`UiDocument.theme == null`) and are deliberately not
 * the same case here, because §14.2 makes them behave differently: [Declared] resolves against
 * the document's single theme, [None] resolves against Material. `ReferencePass` reports
 * `token.unknown` for a name that is valid under one and not the other, so a generator that
 * produced a token name without saying which rule applied would be producing documents that are
 * only valid half the time.
 */
public enum class ThemeSelection {

    /** No `ThemeDecl` at all, and nothing selected: `md.*` is the whole vocabulary. */
    None,

    /** One or more themes, and none selected: §14.2 resolves the single one. */
    Declared,

    /** Themes declared and one of them named by `UiDocument.theme`. */
    Selected,
}
