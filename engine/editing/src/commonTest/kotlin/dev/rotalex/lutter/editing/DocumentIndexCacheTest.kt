package dev.rotalex.lutter.editing

import dev.rotalex.lutter.editing.command.plannerDocument
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * §29.1: `DocumentIndex` is "model, cached in `editing` by `revision`".
 *
 * The cache is keyed on the document **instance**, and that is the honest form of "by
 * revision" here: a revision advances only by applying a patch, and applying a patch produces
 * a new immutable `UiDocument`. So the same instance is the same revision, and a different
 * instance is a different one — no separate key to fall out of step with the document it
 * describes.
 *
 * Identity and not equality: two documents that are `==` but arrived independently are two
 * separate values, and answering one from the other would be a cache making a semantic claim
 * about documents it was not asked to compare.
 */
class DocumentIndexCacheTest {

    /**
     * The whole point of the cache: every question about one revision costs one build.
     *
     * Before this, one planned command built the index once per planner that needed it
     * (`WrapNode` and `ExtractComponent` each build it twice), and a drag-and-drop session
     * that asks the same document for legal targets and then plans the drop built it again
     * for each question.
     */
    @Test
    fun `the same document yields the same index`() {
        val cache = DocumentIndexCache()
        val document = plannerDocument()

        assertSame(cache.of(document), cache.of(document))
    }

    /**
     * A new revision is a new document instance, and it must not be answered from the old
     * index — a stale index answers about nodes a patch has already removed.
     */
    @Test
    fun `a different document instance yields a different index`() {
        val cache = DocumentIndexCache()
        val first = plannerDocument()
        val second = plannerDocument()

        val fromFirst = cache.of(first)

        assertNotSame(fromFirst, cache.of(second))
    }
}
