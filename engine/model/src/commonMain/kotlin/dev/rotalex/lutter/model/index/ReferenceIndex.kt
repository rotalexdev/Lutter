package dev.rotalex.lutter.model.index

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ResourceId
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.value.Value

/**
 * Who mentions a page, a resource, a state or a document component — the index behind a safe
 * delete and a safe rename.
 *
 * PLAN §5.6:442 declares this class with **no members**: the whole of it is a constructor and
 * the comment *"who references state/page/resource/component (safe delete & rename)"*. The
 * four queries below are the narrowest set that answers §6.2:507's question — "who uses X" —
 * for the four kinds §5.6 names, and the one thing worth being explicit about is that they
 * are named after what they answer rather than after a plan line, because there is no plan
 * line to name.
 *
 * ### What the index covers, and what it does not
 *
 * **It covers the node table**, and a node is where a reference is in practice: `Value.Ref`
 * in a property, in a modifier argument or in an action argument, `RefTarget.State` inside an
 * expression, and a node whose `type` is `doc.<id>` for an instance of a document component.
 *
 * **It does not cover a declaration.** `StateDecl.initial` is a `Value?` and a theme's
 * `custom` map is a bag of them, so a document *can* put a `Value.Ref` somewhere with no node
 * behind it, and this index will not see it. That is a real limit and it is stated rather than
 * hidden, because the alternative is a second locator type: a `NodeId` is the only place this
 * module can point at (§17.2's `DiagnosticLocation` lives in `:engine:analysis` and §23.3
 * puts it out of reach), so a reference with no node has nowhere to be reported from. A
 * maintainer's answer to this gap is a plan amendment, not a guess here.
 *
 * ### Ids are compared as strings, never reconstructed
 *
 * Every id below is a `@JvmInline value class` whose `init` refuses a malformed value, and
 * the thing being compared is a bare `String` that came out of a document. Rebuilding a typed
 * id from it to compare would throw `IllegalArgumentException` on a document that is
 * perfectly readable, and an index that goes down on one bad id is worse than one that
 * reports a miss. So the query side is a string lookup and the string is never parsed.
 */
public class ReferenceIndex(document: UiDocument) {

    private val found: Found = scan(document)

    /** The nodes whose properties, modifiers or handlers read [state]. */
    public fun stateReferrers(state: StateId): Set<NodeId> =
        found.states[state.value] ?: emptySet()

    /** The nodes that hold a `Value.Ref` naming [page]. */
    public fun pageReferrers(page: PageId): Set<NodeId> =
        found.pages[page.value] ?: emptySet()

    /** The nodes that hold a `Value.Ref` naming [resource]. */
    public fun resourceReferrers(resource: ResourceId): Set<NodeId> =
        found.resources[resource.value] ?: emptySet()

    /**
     * The nodes that use [component]: a `Value.Ref` naming it, and a node whose `type` is
     * `doc.<id>`.
     *
     * The second is the ordinary one. §5.7 defines an instance as a node whose type is
     * `doc.<ComponentDeclId>`, so an instance references its declaration by its component type
     * rather than by a reference value, and an index that only looked for the value would
     * report every component in a document as unused.
     */
    public fun componentReferrers(component: ComponentDeclId): Set<NodeId> =
        found.components[component.value] ?: emptySet()
}

/** One pass over the table, grouped by the kind of thing referenced. */
private class Found(
    val states: Map<String, Set<NodeId>>,
    val pages: Map<String, Set<NodeId>>,
    val resources: Map<String, Set<NodeId>>,
    val components: Map<String, Set<NodeId>>,
)

/**
 * The scan in progress.
 *
 * Four maps of a raw id string to the nodes that mention it, one per kind. The strings are
 * `NodeId`-free on purpose: see `ReferenceIndex`'s KDoc on why a typed id is not rebuilt
 * from a document.
 */
private class Scan {

    private val states = mutableMapOf<String, MutableSet<NodeId>>()
    private val pages = mutableMapOf<String, MutableSet<NodeId>>()
    private val resources = mutableMapOf<String, MutableSet<NodeId>>()
    private val components = mutableMapOf<String, MutableSet<NodeId>>()

    /** A `Value.Ref`. `RefKind.DataModel` is not one of the four kinds §5.6 asks about. */
    fun reference(node: NodeId, kind: RefKind, id: String): Unit {
        val target = when (kind) {
            RefKind.Page -> pages
            RefKind.Resource -> resources
            RefKind.Component -> components
            RefKind.DataModel -> return
        }
        target.getOrPut(id) { mutableSetOf() } += node
    }

    fun state(node: NodeId, id: String): Unit {
        states.getOrPut(id) { mutableSetOf() } += node
    }

    fun instance(node: NodeId, componentId: String): Unit {
        components.getOrPut(componentId) { mutableSetOf() } += node
    }

    fun result(): Found = Found(states.readOnly(), pages.readOnly(), resources.readOnly(), components.readOnly())
}

private fun Map<String, MutableSet<NodeId>>.readOnly(): Map<String, Set<NodeId>> =
    mapValues { (_, nodes) -> nodes.toSet() }

/**
 * The `doc.` prefix §5.7 gives an instance's component type, and nothing else.
 *
 * `ComponentType` validates as a *namespaced* id — two or more dotted segments — so
 * `doc.<id>` is already well formed, and a type that is not an instance of a document
 * component is one that does not start with the prefix.
 */
private const val DOCUMENT_COMPONENT_PREFIX: String = "doc."

private fun scan(document: UiDocument): Found {
    val scan = Scan()

    for (id in document.nodes.ids()) {
        val node = document.nodes[id] ?: continue

        val type = node.type.value
        if (type.startsWith(DOCUMENT_COMPONENT_PREFIX)) {
            scan.instance(id, type.substring(DOCUMENT_COMPONENT_PREFIX.length))
        }

        for (property in node.props.values) scanProperty(property, id, scan)
        for (modifier in node.modifiers) {
            for (argument in modifier.args.values) scanProperty(argument, id, scan)
        }
        for (sequence in node.events.values) scanSequence(sequence, id, scan)
    }

    return scan.result()
}

private fun scanProperty(property: PropertyValue, node: NodeId, scan: Scan) {
    when (property) {
        is PropertyValue.Const -> scanValue(property.value, node, scan)
        is PropertyValue.Computed -> scanExpr(property.expr, node, scan)
    }
}

private fun scanSequence(sequence: ActionSequence, node: NodeId, scan: Scan) {
    for (step in sequence.steps) scanStep(step, node, scan)
}

private fun scanStep(step: ActionStep, node: NodeId, scan: Scan) {
    for (argument in step.args.values) scanProperty(argument, node, scan)
    for (branch in step.branches.values) scanSequence(branch, node, scan)
}

private fun scanExpr(expr: Expr, node: NodeId, scan: Scan) {
    when (expr) {
        is Expr.Const -> scanValue(expr.value, node, scan)
        is Expr.Ref -> {
            val target = expr.target
            if (target is RefTarget.State) scan.state(node, target.id.value)
        }

        is Expr.Member -> scanExpr(expr.receiver, node, scan)
        is Expr.Call -> expr.args.forEach { scanExpr(it, node, scan) }
        is Expr.Unary -> scanExpr(expr.operand, node, scan)
        is Expr.Binary -> {
            scanExpr(expr.left, node, scan)
            scanExpr(expr.right, node, scan)
        }

        is Expr.If -> {
            scanExpr(expr.cond, node, scan)
            scanExpr(expr.then, node, scan)
            scanExpr(expr.otherwise, node, scan)
        }

        is Expr.ListLiteral -> expr.items.forEach { scanExpr(it, node, scan) }
        is Expr.Template -> expr.parts.forEach { scanExpr(it, node, scan) }
    }
}

private fun scanValue(value: Value, node: NodeId, scan: Scan) {
    when (value) {
        is Value.Ref -> scan.reference(node, value.kind, value.id)
        is Value.ListOf -> value.items.forEach { scanValue(it, node, scan) }
        is Value.MapOf -> {
            scanValue(value.value, node, scan)
            value.entries.values.forEach { scanValue(it, node, scan) }
        }

        is Value.Obj -> value.fields.values.forEach { scanValue(it, node, scan) }
        // Every remaining variant is a leaf: a scalar, an entry name, a URL, an icon or a
        // token. Listed rather than an `else`, so a nineteenth variant fails to compile here
        // instead of quietly stopping being scanned.
        is Value.Null, is Value.Bool, is Value.Int32, is Value.Int64, is Value.Float32,
        is Value.Float64, is Value.Str, is Value.Color, is Value.Dp, is Value.Sp,
        is Value.Enum, is Value.Url, is Value.Icon, is Value.Token -> Unit
    }
}
