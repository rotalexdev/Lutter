package dev.rotalex.lutter.runtime

/**
 * The interpreter seam: function implementations plus action handlers.
 *
 * A3 owns both maps and fills this holder. The skeleton carries none because the
 * skeleton has no expressions and no actions, and an empty map here would claim
 * coverage the runtime cannot honour.
 */
public class Implementations {
    public companion object {
        /** No implementations. The skeleton's only value. */
        public val None: Implementations = Implementations()
    }
}
