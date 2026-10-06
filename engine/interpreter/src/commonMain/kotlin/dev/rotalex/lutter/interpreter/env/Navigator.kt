package dev.rotalex.lutter.interpreter.env

import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.value.Value

/**
 * Where a navigation step goes, as the interpreter sees it.
 *
 * Neither method suspends although a handler does, so a document cannot wait for a
 * destination to arrive: timing is the host's business and a document has no way to ask.
 */
public interface Navigator {

    /** Opens [page] with [args]. Page params are already validated by analysis. */
    public fun navigate(page: PageId, args: Map<ParamName, Value>)

    /** Steps back. False when there is nowhere to go. */
    public fun back(): Boolean
}
