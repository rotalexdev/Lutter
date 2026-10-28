package dev.rotalex.lutter.interpreter.env

import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.value.Value

/**
 * Where a navigation step goes, as the interpreter sees it, and where it has got to.
 *
 * [current] is on the interface rather than left to a stack's own type because rendering is not
 * the only reader: a composition has to be able to ask where it is, and one that cannot would
 * leave every caller holding a downcast or a second reference to the same stack.
 *
 * Neither method suspends although a handler does, so a document cannot wait for a
 * destination to arrive: timing is the host's business and a document has no way to ask.
 */
public interface Navigator {

    /** Where the navigator is now. Reading it from a composition subscribes that composition. */
    public val current: Destination

    /** Opens [page] with [args]. Page params are already validated by analysis. */
    public fun navigate(page: PageId, args: Map<ParamName, Value>)

    /** Steps back. False when there is nowhere to go. */
    public fun back(): Boolean
}
