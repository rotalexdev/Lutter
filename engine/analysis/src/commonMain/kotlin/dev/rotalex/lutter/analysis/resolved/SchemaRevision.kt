package dev.rotalex.lutter.analysis.resolved

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Which schema context a [ResolvedDocument] was lowered against, as one opaque identity.
 *
 * §29.1:2048's third cache component. The first two — node identity and inherited scope —
 * are already carried by the resolved tree itself; this one is the input no per-node guard
 * can recover after the fact: a resolution produced against one `SchemaView` is only
 * reusable against the same one, because what a view holds decides the defaults, property
 * keys and slots every node resolves through. A declaration edit or a version note changes
 * that surface without touching a node, and a different analyzer is a different base view
 * even when the content is equal — so the carry rule the pass applies is: reuse only from a
 * run whose revision matches this one's, and recompute otherwise, wholesale rather than per
 * node.
 *
 * Ids come from a process-wide counter, so two analyzers never share one and the comparison
 * needs no content walk to stay honest; the cost of that conservatism is that a carry across
 * two analyzers built from equal schemas is refused, which is a reuse the dirty set cannot
 * offer anyway and never a wrong answer.
 *
 * [NONE] marks a document built by hand rather than by the analyzer — a context nothing can
 * vouch for, and the reason the carry refuses it like any other it does not recognize.
 */
@JvmInline
public value class SchemaRevision(public val id: Long) {
    public companion object {

        /** The unknown context: hand-built documents carry it and are never reused from. */
        public val NONE: SchemaRevision = SchemaRevision(0)
    }
}

/** Mints [SchemaRevision] ids, one per distinct schema context this process has lowered. */
@OptIn(ExperimentalAtomicApi::class)
internal object SchemaRevisionMinter {
    private val next = AtomicLong(1)
    public fun mint(): SchemaRevision = SchemaRevision(next.addAndFetch(1L))
}
