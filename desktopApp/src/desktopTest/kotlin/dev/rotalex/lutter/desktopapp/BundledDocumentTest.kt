package dev.rotalex.lutter.desktopapp

import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The document a window opens on when nobody hands it one, checked where it actually lives.
 *
 * ### The two ways it is read, and why both are tested
 *
 * `gradle run` reads [DEFAULT_DOCUMENT] from disk, relative to the module directory. A packaged
 * build cannot: the working directory belongs to whoever double-clicked the launcher, so
 * `readDocument` falls back to `/default-document.json` out of the jar. Both paths must land on
 * the same starter, and neither is exercised by any other test — the pane tests hand the session
 * a document built inline.
 *
 * The disk assertion is the load-bearing one and it is the mistake this repository has made
 * twice: a module-relative path written one directory too high (`../../` instead of `../`)
 * compiles, passes review, and fails at run time with a file-not-found. Here it fails at test
 * time, with the absolute path it resolved, which is the only part a reader cannot guess.
 *
 * ### Why the resource is analyzed
 *
 * Being present is not the claim. The claim on the release page is "install it and a document
 * opens", and the way that becomes false is a document the engine refuses — a typed property
 * renamed in the schema, a slot made required, a page root pointing at a node that moved. So the
 * bundled copy is resolved with the same [BuiltinEngine] the window builds, and a refusal names
 * the diagnostics.
 */
class BundledDocumentTest {

    @Test
    fun the_starter_document_is_on_disk_where_gradle_run_reads_it() {
        val file = java.io.File(DEFAULT_DOCUMENT)
        assertTrue(
            file.isFile,
            "'$DEFAULT_DOCUMENT' is not a file relative to the test's working directory " +
                "(${java.io.File(".").absolutePath}); `gradle run` would open on nothing",
        )
    }

    @Test
    fun the_starter_document_is_in_the_jar_where_the_launcher_reads_it() {
        assertNotNull(
            UiDocument::class.java.getResourceAsStream("/default-document.json"),
            "/default-document.json is not on the classpath, so a packaged build has no document " +
                "and the launcher dies with jpackage's 'Failed to launch JVM'",
        ).close()
    }

    @Test
    fun the_starter_document_resolves_with_the_engine_the_window_builds() {
        val decoded = JsonDocumentCodec.decode(bundledBytes()).document
        val schema = BuiltinEngine.schema()
        val result = BuiltinEngine.analyze(schema, decoded)

        assertFalse(
            result.hasErrors,
            "the starter document does not resolve: ${result.diagnostics}",
        )
        val resolved = assertNotNull(result.resolved)
        assertEquals(2, resolved.pages.size, "the starter is two pages; one was lost on the way in")
        assertTrue(
            decoded.app.startPage in resolved.pages,
            "the start page '${decoded.app.startPage}' is not one of the resolved pages",
        )
        assertTrue(
            resolved.nodes.isNotEmpty(),
            "the starter resolved to no nodes, so the canvas would draw nothing",
        )
    }

    private fun bundledBytes(): ByteArray =
        checkNotNull(UiDocument::class.java.getResourceAsStream("/default-document.json"))
            .use { it.readBytes() }
}
