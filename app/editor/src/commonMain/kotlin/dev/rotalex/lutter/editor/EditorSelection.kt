package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId

/**
 * What the editor is pointing at, as a value a UI can render and a session can hold (§25.1).
 *
 * Three cases and no fourth: a document is edited by selecting something in it, and the two
 * things a selection can be are a node and a page. A whole-document mode would be a place for
 * commands that have no subject, and the commands that do — `AddPage`, `SetTheme` — already
 * name their own target.
 *
 * **Not a `NodeId?` plus a boolean**: "nothing selected" and "the selected node was deleted"
 * are the same state, and making them two fields would let a session hold one without the
 * other.
 */
public sealed interface EditorSelection {

    /** Nothing is selected: an empty canvas, or a document just opened. */
    public data object None : EditorSelection

    /** One node, whose properties the inspector shows. */
    public data class Node(public val id: NodeId) : EditorSelection

    /**
     * Several nodes, acted on together — a marquee's catch, moved or removed as one gesture.
     *
     * [primary] is the one the inspector shows and the one a single-node action falls back to,
     * because a dock with three buttons has no room to explain that "Properties" is ambiguous.
     * A selection of one is a [Node], not a `Nodes` of size one: two representations of the same
     * state would let a session hold one and a test assert the other.
     */
    public data class Nodes(
        public val ids: Set<NodeId>,
        public val primary: NodeId,
    ) : EditorSelection

    /** One page, selected in the page list rather than through a node. */
    public data class Page(public val id: PageId) : EditorSelection
}
