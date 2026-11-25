package dev.rotalex.lutter.editor

import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.model.doc.UiDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorPreviewTest {

    private val schema = editorSchema()

    /** The fixture document lowered once, so a stub can hand the same good render back. */
    private val resolved: ResolvedDocument =
        Analyzer(schema).analyze(editorDocument()).resolved
            ?: error("the preview fixtures must resolve, or no render case is reachable")

    @Test
    fun `a fresh preview renders the session document`() {
        val document = editorDocument()
        val preview = EditorPreview(session(document), analyze = realAnalyzer())

        val state = assertIs<PreviewState.Rendered>(preview.refresh())

        assertEquals(PreviewOrigin.Session, state.origin)
        assertEquals(0L, state.revision)
        assertEquals(document, state.document)
    }

    @Test
    fun `an unchanged revision is analyzed once`() {
        var calls = 0
        val preview = EditorPreview(
            session = session(),
            analyze = {
                calls += 1
                AnalysisResult(emptyList(), resolved)
            },
        )

        preview.refresh()
        preview.refresh()
        preview.refresh()

        assertEquals(1, calls)
    }

    @Test
    fun `an edit re-resolves at the new revision`() {
        var analyzed: UiDocument? = null
        val session = session()
        val preview = EditorPreview(
            session = session,
            analyze = { document ->
                analyzed = document
                AnalysisResult(emptyList(), resolved)
            },
        )
        preview.refresh()

        assertIs<PropertyWrite.Applied>(session.setProperty(LEAF, TEXT, "\"Bye\""))
        val state = assertIs<PreviewState.Rendered>(preview.refresh())

        assertEquals(1L, state.revision)
        assertTrue(analyzed === state.document)
    }

    @Test
    fun `a refusal keeps the last good render and retries the same revision`() {
        var refusing = false
        var calls = 0
        val session = session()
        val preview = EditorPreview(
            session = session,
            analyze = {
                calls += 1
                if (refusing) AnalysisResult(emptyList(), null) else AnalysisResult(emptyList(), resolved)
            },
        )
        val good = assertIs<PreviewState.Rendered>(preview.refresh())

        session.dispatch(Command.RemoveNode(LEAF))
        refusing = true
        val refused = assertIs<PreviewState.Refused>(preview.refresh())

        assertEquals(PreviewOrigin.Session, refused.origin)
        assertEquals(good, refused.lastRendered)
        assertEquals(2, calls)

        // A refused revision is not adopted, so the next refresh asks again rather than trusting
        // a failure it never rendered.
        refusing = false
        val recovered = assertIs<PreviewState.Rendered>(preview.refresh())
        assertEquals(1L, recovered.revision)
        assertEquals(3, calls)
    }

    @Test
    fun `a refusal with nothing on screen has no last render`() {
        val preview = EditorPreview(session(), analyze = { AnalysisResult(emptyList(), null) })

        val refused = assertIs<PreviewState.Refused>(preview.refresh())

        assertNull(refused.lastRendered)
        assertTrue(refused.diagnostics.isEmpty())
        assertNull(refused.reason)
    }

    @Test
    fun `a preview with no refresh yet is empty`() {
        val preview = EditorPreview(session(), analyze = { AnalysisResult(emptyList(), resolved) })

        assertEquals(PreviewState.Empty, preview.state.value)
    }

    private fun realAnalyzer(): (UiDocument) -> AnalysisResult = { Analyzer(schema).analyze(it) }
}
