package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.PropertyKey

/**
 * The one modifier that gives a node a place.
 *
 * ### Why the names are here and not imported
 *
 * `:engine:builtins` is a test-scope edge of this module — the architecture rule that the editor
 * composes the engine and never the reverse — so the canvas cannot see `OffsetModifier` in
 * `commonMain`. The names are not unchecked, though: `PlacementTest`, which runs where the builtin
 * schema *is* visible, asserts that the installed schema declares this type with these two `Dp`
 * arguments. A rename upstream fails that test instead of leaving the canvas writing a modifier
 * the renderer ignores.
 *
 * ### Why a node's place is a modifier and not a field
 *
 * `NodeBounds` says it: a document is a layout, and a node has no coordinates. Absolute placement
 * is therefore something a node *carries* — a `layout.offset` in its chain — which is what keeps a
 * freeform page a plain `core.Box` whose children are placed, and what lets a hand-written
 * document say the same thing a drag says.
 */
internal object Placement {

    /** The modifier type a drag writes. */
    val offset: ModifierType = ModifierType("layout.offset")

    /** Its first argument, the horizontal distance. */
    val x: PropertyKey = PropertyKey("x")

    /** Its second argument, the vertical distance. */
    val y: PropertyKey = PropertyKey("y")
}
