package dev.rotalex.lutter.runtime

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.value.Value

/**
 * A navigation stack over snapshot state, so a composition reading [current] recomposes on a
 * navigation rather than rendering a page the document already left.
 *
 * It holds page ids and nothing else. No document is visible from here, so a page it has never
 * heard of is pushed like any other and `UiScreen` is where an unknown page is reported — the
 * navigator cannot answer, because it is not the thing that knows which pages exist.
 */
public class BackStackNavigator(start: PageId) : Navigator {

    // A whole immutable list per change rather than a snapshot list of entries: a push and a
    // pop are the same act here, and a reader that read `current` keeps the destination it was
    // given even after the stack moves on.
    private val stack: MutableState<List<Destination>> =
        mutableStateOf(listOf(Destination(start, emptyMap())))

    /** The page on top of the stack, with the arguments it was opened with. */
    public val current: Destination get() = stack.value.last()

    /** Opens [page] on top of the stack, so a later [back] returns to where this came from. */
    override fun navigate(page: PageId, args: Map<ParamName, Value>) {
        // Copied because a caller that reuses or mutates the map it passed must not be able to
        // change a destination the stack already holds.
        stack.value = stack.value + Destination(page, args.toMap())
    }

    /** Pops one entry. False at the root, where the start page is all there is. */
    override fun back(): Boolean {
        val entries = stack.value
        if (entries.size <= 1) return false
        stack.value = entries.dropLast(1)
        return true
    }

    /** One entry on the stack: a page and the arguments it was opened with. */
    public data class Destination(
        public val page: PageId,
        public val args: Map<ParamName, Value>,
    )
}
