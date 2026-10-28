package dev.rotalex.lutter.interpreter.env

import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.value.Value

/**
 * One entry on a navigator's stack: a page and the arguments it was opened with.
 *
 * One type rather than a page beside an argument map because the two only ever travel together,
 * and a reader holding a page alone cannot tell which call opened it.
 *
 * It says nothing about which pages exist. A document's pages are not visible from here, so a
 * page never heard of is a destination like any other and the page that knows nothing is where
 * an unknown one is reported.
 */
public data class Destination(
    public val page: PageId,
    public val args: Map<ParamName, Value>,
)
