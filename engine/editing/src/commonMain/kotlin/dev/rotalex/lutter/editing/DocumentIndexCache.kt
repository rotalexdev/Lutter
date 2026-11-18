package dev.rotalex.lutter.editing

import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.index.DocumentIndex

/**
 * §29.1's `DocumentIndex` "model, cached in `editing` by `revision`", with the revision
 * spelled as the document **instance**.
 *
 * A revision advances only when a patch is applied, and applying a patch produces a new
 * immutable [UiDocument]. So the instance is the revision: there is no separate key that could
 * drift from the document it claims to describe, and there is no way to answer with an index
 * built from anything else.
 *
 * ### One slot, not a map
 *
 * An editing session is a sequence of revisions, and the questions about one revision arrive
 * together — the legal targets for a drag, then the plan for the drop, then a redraw. Keeping
 * the last one is enough for all of that and bounds the memory an undo history of a thousand
 * revisions would otherwise pin. A map would be a cache that grows with the session's age.
 *
 * ### Why identity and not equality
 *
 * Two documents can be `==` and still be two values that arrived independently: undo back to a
 * revision, then edit forward again, and the second document is equal to a document that
 * existed before. Reusing an index across that is sound for the index's own content — it is a
 * pure function of the document — but it is a cache making a claim about documents it was not
 * asked to compare, and `==` on a ten-thousand-node table is not cheaper than what it saves.
 *
 * Not thread-safe, and deliberately so: it is owned by [DocumentController], which is single
 * threaded like the state it publishes.
 */
internal class DocumentIndexCache {

    private var lastDocument: UiDocument? = null

    private var lastIndex: DocumentIndex? = null

    /**
     * The index of [document], built once per instance.
     *
     * The caller passes the document it is about to plan against, so the answer cannot belong
     * to a different one: a miss rebuilds, and a hit returns an index that was built from
     * exactly this instance.
     */
    fun of(document: UiDocument): DocumentIndex {
        val cached = lastIndex
        if (cached != null && lastDocument === document) return cached

        val built = DocumentIndex(document)
        lastDocument = document
        lastIndex = built
        return built
    }
}
