package dev.rotalex.lutter.samples.desktoppreview

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import dev.rotalex.lutter.serialization.storage.DocumentStorage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking

/**
 * What happens when the file behind the window changes (PLAN §32 Phase 10).
 *
 * ### Why the watcher is scripted rather than timed
 *
 * The feature under test is a loop with a clock in it, which is the shape tests lie about most
 * easily: a test that sleeps "long enough" for a poll is green on an idle machine and red on a
 * loaded one, and it is red in CI where it matters. Every test here drives
 * [DocumentReloader]'s scheduling seam — a `sleep` it supplies — so a run is a fixed number of
 * polls decided before the first byte is read. No test in this file waits, and none of them can
 * be slow.
 *
 * What is given up is the one thing a scripted clock cannot check: that a real `delay` really
 * returns after the interval it was asked for. That is the standard library's, it is not what
 * this module implements, and testing it here would be the flaky test in exchange for no
 * coverage. The intervals the loop asked for *are* asserted, which is the half that is ours.
 */
class DocumentReloaderTest {

    @Test
    fun `a file nobody touched is loaded once and then left alone`() = pollsUntilDone {
        val storage = ScriptedStorage(HOME)
        val polls = Polls(stopAfter = 3)

        runBlocking { polls.reloader(storage).run() }

        assertEquals(1, polls.loaded.size, "the first poll loads; the next two see the same bytes")
        assertEquals(PageId("p_home"), polls.loaded.single().decoded.app.startPage)
        assertEquals(
            listOf(PreviewStatus.Watching(LOCATION)),
            polls.statuses.distinct(),
            "a healthy poll says nothing beyond watching",
        )
        assertEquals(List(3) { INTERVAL }, polls.intervals, "one wait per poll, and the same one")
    }

    @Test
    fun `a saved change is loaded and the snapshot moves to it`() = pollsUntilDone {
        val storage = ScriptedStorage(HOME)
        val polls = Polls(stopAfter = 3, betweenPolls = { if (it == 1) storage.next = PROFILE })

        runBlocking { polls.reloader(storage).run() }

        assertEquals(2, polls.loaded.size, "one load for what the window opened on, one for the save")
        assertEquals(PageId("p_profile"), polls.loaded.last().decoded.app.startPage)
        // The lowered half travels with the decoded one: the window cannot open a document it
        // has only the resolved form of, because lowering drops the start page.
        assertEquals(2, polls.loaded.last().resolved.pages.size)
    }

    /**
     * The failure a preview has to survive, twice over.
     *
     * A file saved half-written is the ordinary case rather than an edge: an editor truncates
     * before it writes. Two claims, and they are different ones — the document on screen is not
     * replaced by bytes that are not a document, and the same broken bytes are decoded once
     * rather than on every poll. The second is what keeps a file that stays broken from costing
     * an analysis four times a second for as long as it stays broken.
     */
    @Test
    fun `a half-written file is refused once and replaces nothing`() = pollsUntilDone {
        val storage = ScriptedStorage(HOME)
        val polls = Polls(stopAfter = 4, betweenPolls = { if (it == 1) storage.next = TRUNCATED })

        runBlocking { polls.reloader(storage).run() }

        assertEquals(1, polls.loaded.size, "the last good document is the one on screen")
        val refusals = polls.statuses.filterIsInstance<PreviewStatus.Rejected>()
        assertEquals(1, refusals.size, "three polls of the same broken bytes, one refusal")
        assertTrue(refusals.single().isFailure)
        assertTrue(
            checkNotNull(refusals.single().message).isNotBlank(),
            "a refusal that says nothing is not one a user can act on",
        )
    }

    /**
     * The other failure, and the one a rename produces: bytes that decode perfectly well and are
     * still not a document this engine renders.
     *
     * It is a separate path from a decode failure — `resolve` refuses where the codec succeeded
     * — so a preview that handled only the first would blank on a page rename, which is the
     * edit a document author makes most.
     */
    @Test
    fun `a document that decodes and does not resolve is refused the same way`() = pollsUntilDone {
        val storage = ScriptedStorage(HOME)
        val polls = Polls(stopAfter = 3, betweenPolls = { if (it == 1) storage.next = NO_SUCH_PAGE })

        runBlocking { polls.reloader(storage).run() }

        assertEquals(1, polls.loaded.size, "the last good document is the one on screen")
        val refusal = polls.statuses.filterIsInstance<PreviewStatus.Rejected>().single()
        assertTrue(
            checkNotNull(refusal.reason).contains("does not resolve"),
            "the reason names the refusal rather than the codec: ${refusal.reason}",
        )
    }

    @Test
    fun `a document that stops being broken is loaded`() = pollsUntilDone {
        val storage = ScriptedStorage(HOME)
        val polls = Polls(
            stopAfter = 3,
            betweenPolls = { if (it == 1) storage.next = TRUNCATED else if (it == 2) storage.next = PROFILE },
        )

        runBlocking { polls.reloader(storage).run() }

        assertEquals(2, polls.loaded.size, "the loop survived the broken file and took the repair")
        assertEquals(PageId("p_profile"), polls.loaded.last().decoded.app.startPage)
    }

    /**
     * Undoing a bad edit takes the error off the screen.
     *
     * The bytes the screen was built from are kept for the whole run, so a file put back is
     * recognised as "the bytes already on screen" — and that branch clears what was refused.
     * Without that, a `git checkout` of the very file a user had just broken would leave the
     * refusal sitting above a document that had never stopped rendering, which is a complaint
     * about a failure that no longer exists.
     */
    @Test
    fun `putting a broken file back clears the refusal and a later break reports again`() =
        pollsUntilDone {
            val storage = ScriptedStorage(HOME)
            val polls = Polls(
                stopAfter = 4,
                betweenPolls = { tick ->
                    storage.next = when (tick) {
                        1 -> TRUNCATED
                        2 -> HOME
                        else -> TRUNCATED
                    }
                },
            )

            runBlocking { polls.reloader(storage).run() }

            assertEquals(1, polls.loaded.size, "the same bytes are not a change")
            assertEquals(
                listOf(false, true, false, true),
                polls.statuses.map { it.isFailure },
                "watching, refused, watching again, refused again",
            )
        }

    /**
     * A file that is not there is not a document that failed to decode.
     *
     * A rename-into-place, a lock, a deleted file: none of them ends the loop, and none of them
     * is a reason to think the last document on screen is wrong. And the file coming back with
     * the bytes it had is not a change either — which is the other half of why the loop compares
     * content rather than trusting that something happened.
     */
    @Test
    fun `a file that is not there is reported without ending the loop, and its return is not a change`() =
        pollsUntilDone {
            val storage = ScriptedStorage(HOME)
            val polls = Polls(
                stopAfter = 4,
                betweenPolls = { tick ->
                    if (tick == 1) storage.failure = IllegalStateException("no document at '$LOCATION'")
                    if (tick == 2) storage.failure = null
                },
            )

            runBlocking { polls.reloader(storage).run() }

            assertEquals(1, polls.loaded.size, "the file came back as it was")
            assertEquals(1, polls.statuses.count { it is PreviewStatus.Unreadable })
            assertEquals(
                listOf(false, true, false, false),
                polls.statuses.map { it.isFailure },
                "watching, unreadable, watching again, and quiet",
            )
        }

    /**
     * A cancelled read is a cancelled read and not an unreadable file.
     *
     * On the JVM `CancellationException` extends `IllegalStateException`, so a `catch` for
     * ordinary failures above a catch for this one would take the cancellation with it and the
     * loop would keep running after its window had gone. The order in [DocumentReloader] is the
     * thing under test, and the only way to hold it is to let the loop meet one.
     */
    @Test
    fun `a cancelled read is not swallowed as an unreadable file`() {
        val storage = ScriptedStorage(HOME)
        val polls = Polls(stopAfter = 4, betweenPolls = { if (it == 1) storage.failure = CancelledRead() })

        assertFailsWith<CancellationException> { runBlocking { polls.reloader(storage).run() } }

        assertTrue(
            polls.statuses.none { it is PreviewStatus.Unreadable },
            "a cancellation was reported as a file that could not be read: ${polls.statuses}",
        )
    }
}

/**
 * That a re-render is a value swap, asserted on the window rather than on the loop.
 *
 * The tests above prove the loop notices a change and produces a new snapshot. This one proves
 * the snapshot reaches the screen, which is the half that is Compose's and would fail silently:
 * a host that forgot to read the state, or keyed the screen on something that did not change,
 * would keep drawing the old document with no error anywhere.
 *
 * The same fixture as above and the same swap: `button_navigate.json` with its start page moved
 * from `p_home` to `p_profile`, so "Welcome" and "Continue" become "Profile" and nothing else.
 */
@OptIn(ExperimentalTestApi::class)
class PreviewContentTest {

    @Test
    fun `a new snapshot re-renders the window on the new document's own start page`() =
        runComposeUiTest {
            val runtime = BuiltinEngine.runtime(BuiltinEngine.schema())
            val loaded = mutableStateOf(PreviewFixture.loaded("p_home"))
            val status = mutableStateOf<PreviewStatus>(PreviewStatus.Watching(LOCATION))

            setContent { PreviewContent(runtime, loaded, status) }
            // No line above it either: a healthy preview is silent, and that is the assertion
            // about the banner before there is ever anything wrong to report.
            assertEquals(
                listOf("Welcome", "Continue"),
                renderedTexts(),
                "the start page, and nothing else",
            )

            loaded.value = PreviewFixture.loaded("p_profile")
            waitForIdle()

            assertEquals(
                listOf("Profile"),
                renderedTexts(),
                "the new document's start page, not the page the previous one was on",
            )
        }

    /**
     * The failure reaches the screen and does not take the document with it.
     *
     * Order-independent on purpose — which of the two lines is drawn first is a layout detail,
     * and what matters is that both are there. A preview that replaced the screen with its error
     * would lose the context of what is being edited, and one that swallowed the error would
     * leave a user staring at a document that stopped responding to their saves.
     */
    @Test
    fun `a refusal is shown above the document that is still there`() = runComposeUiTest {
        val runtime = BuiltinEngine.runtime(BuiltinEngine.schema())
        val loaded = mutableStateOf(PreviewFixture.loaded("p_home"))
        val status = mutableStateOf<PreviewStatus>(PreviewStatus.Watching(LOCATION))

        setContent { PreviewContent(runtime, loaded, status) }

        status.value = PreviewStatus.Rejected("SerializationException: the file was half-written")
        waitForIdle()

        val shown = renderedTexts()
        assertTrue(shown.contains("Welcome"), "the last good document is still on screen: $shown")
        assertTrue(
            shown.any { it.startsWith("The document on disk was refused:") },
            "and the refusal is above it: $shown",
        )
    }

    /** Every text in the composed tree, in tree order. */
    private fun ComposeUiTest.renderedTexts(): List<String> {
        val found = mutableListOf<String>()
        collect(onRoot().fetchSemanticsNode(), found)
        return found
    }

    private fun collect(node: SemanticsNode, into: MutableList<String>) {
        read(node.config, SemanticsProperties.Text)?.forEach { into += it.text }
        for (child in node.children) collect(child, into)
    }

    // `SemanticsConfiguration` has no optional read in this Compose version, so this is a `get`
    // that may throw and `runCatching` is the whole of the optionality.
    private fun <T> read(config: SemanticsConfiguration, key: SemanticsPropertyKey<T>): T? =
        runCatching { config.get(key) }.getOrNull()
}

/**
 * The bytes and the documents these tests move around.
 *
 * One fixture, three spellings: what the repository holds, the same document opening somewhere
 * else, and the same document naming a page that is not there. Going through the model rather
 * than editing text keeps the differences to exactly the one each test is about, so a failure
 * is the change and not the formatting around it.
 */
private object PreviewFixture {

    /** The repository fixture, as the sample ships it. Also what `readDocument` falls back to. */
    val home: ByteArray = resource().use { it.readBytes() }

    /** The same document, opening on [startPage] instead of on `p_home`. */
    fun bytes(startPage: String): ByteArray {
        val document = JsonDocumentCodec.decode(home).document
        return JsonDocumentCodec.encode(document.startingOn(PageId(startPage)))
    }

    /** [startPage]'s document, decoded and lowered — what a reload hands the window. */
    fun loaded(startPage: String): LoadedDocument {
        val decoded = JsonDocumentCodec.decode(home).document.startingOn(PageId(startPage))
        return LoadedDocument(decoded, BuiltinEngine.resolve(BuiltinEngine.schema(), decoded))
    }

    private fun UiDocument.startingOn(startPage: PageId): UiDocument =
        copy(app = app.copy(startPage = startPage))

    private fun resource(): java.io.InputStream = checkNotNull(
        JsonDocumentCodec::class.java.getResourceAsStream("/default-document.json"),
    ) { "/default-document.json is not on the test classpath; the packaged app opens on nothing" }
}

/** The same document opening on a page it does not declare: valid JSON, not a document. */
private val NO_SUCH_PAGE: ByteArray = PreviewFixture.bytes("p_nowhere")

/** The same document opening on `p_profile`, which is what a reload looks like. */
private val PROFILE: ByteArray = PreviewFixture.bytes("p_profile")

private val HOME: ByteArray = PreviewFixture.home

/**
 * The fixture as a file being saved while it is read: cut inside the object, so it is not a
 * document, which is what every editor's save briefly makes it.
 */
private val TRUNCATED: ByteArray = PreviewFixture.home.copyOf(PreviewFixture.home.size / 2)

/** How long a test's loop waits between polls. Never waited: the clock is injected. */
private val INTERVAL: Duration = 5.milliseconds

private const val LOCATION: String = "/tmp/lutter-preview-test/document.json"

/** A [DocumentStorage] a test rewrites between polls, with no filesystem anywhere. */
private class ScriptedStorage(bytes: ByteArray) : DocumentStorage {

    /** What the next poll finds. */
    var next: ByteArray = bytes

    /** What the next poll throws instead of finding, or null for a clean read. */
    var failure: Exception? = null

    override suspend fun read(location: String): ByteArray {
        failure?.let { throw it }
        return next
    }

    override suspend fun write(location: String, bytes: ByteArray) {
        next = bytes
    }
}

/**
 * One scripted run: what the loop loaded, what it said, and how long it asked to wait.
 *
 * [sleep] is the seam and the whole of this class's reason to exist — see the file's KDoc on why
 * nothing here is timed.
 */
private class Polls(
    private val stopAfter: Int,
    private val betweenPolls: (Int) -> Unit = {},
) {

    /** The intervals the loop asked to wait, in order. */
    val intervals: MutableList<Duration> = mutableListOf()
    val loaded: MutableList<LoadedDocument> = mutableListOf()
    val statuses: MutableList<PreviewStatus> = mutableListOf()

    /**
     * Called at the *bottom* of a poll, because [DocumentReloader] waits after it has read.
     *
     * [betweenPolls] is therefore handed the number of polls already finished, and writes what
     * the *next* one will find.
     */
    val sleep: suspend (Duration) -> Unit = { requested ->
        intervals += requested
        betweenPolls(intervals.size)
        if (intervals.size >= stopAfter) throw StopPolling()
    }

    fun reloader(storage: DocumentStorage): DocumentReloader =
        DocumentReloader(
            storage = storage,
            location = LOCATION,
            schema = BuiltinEngine.schema(),
            onLoaded = { loaded += it },
            onStatus = { statuses += it },
            interval = INTERVAL,
            sleep = sleep,
        )
}

/**
 * How a scripted run ends.
 *
 * The loop is `while (true)` with one way out — an exception out of the injected `sleep` — so
 * this is both the stop condition and the assertion that the run stopped where the script said
 * it would rather than somewhere else or nowhere.
 */
private class StopPolling : RuntimeException("the scripted polls are done")

/** A read cancelled the way the window closing cancels one. */
private class CancelledRead : CancellationException("the window is gone")

/**
 * Runs [block] to the end of its script.
 *
 * The assertion is the point: a loop that stopped early has lost polls the test then reasons
 * about, and one that never stopped would hang the suite rather than fail it.
 */
private fun pollsUntilDone(block: () -> Unit) {
    assertFailsWith<StopPolling>("the loop did not stop where the script said it would") { block() }
}
