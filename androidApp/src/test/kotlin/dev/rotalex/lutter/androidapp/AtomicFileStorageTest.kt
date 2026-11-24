package dev.rotalex.lutter.androidapp

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The Android storage's half of the contract: a real file, replaced in one step, with nothing left
 * beside it.
 *
 * The saving *rules* are tested once in `:app:editor` against a map. What is tested here is only
 * what a map cannot show — that `renameTo` really replaces an existing file on this platform, and
 * that a write leaves no interrupted copy for the next read to trip over.
 */
class AtomicFileStorageTest {

    private val temporary: File =
        File.createTempFile("lutter-storage", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }

    @AfterTest
    fun removeTheTemporaryDirectory() {
        temporary.deleteRecursively()
    }

    private val storage = AtomicFileStorage()

    @Test
    fun `a written document is read back byte for byte`() = runBlocking {
        val location = File(temporary, "document.json").path
        val bytes = """{"hello":"world"}""".encodeToByteArray()

        storage.write(location, bytes)

        assertContentEquals(bytes, storage.read(location))
    }

    @Test
    fun `a second write replaces the first instead of appending to it`() = runBlocking {
        val location = File(temporary, "document.json").path
        storage.write(location, "the first version".encodeToByteArray())

        storage.write(location, "the second".encodeToByteArray())

        assertEquals("the second", storage.read(location).decodeToString())
    }

    @Test
    fun `a completed write leaves no temporary file`() = runBlocking {
        storage.write(File(temporary, "document.json").path, "{}".encodeToByteArray())

        assertEquals(
            listOf("document.json"),
            temporary.list()?.sorted().orEmpty(),
            "an interrupted-write copy is visible after a completed write",
        )
    }

    @Test
    fun `a missing parent directory is created`() = runBlocking {
        val deep = File(temporary, "files/document.json")

        storage.write(deep.path, "{}".encodeToByteArray())

        assertTrue(deep.isFile, "the storage refused to create the directory it was written into")
    }

    @Test
    fun `reading a document that is not there fails loudly rather than returning nothing`() {
        assertFailsWith<IllegalStateException> {
            runBlocking { storage.read(File(temporary, "absent.json").path) }
        }
    }
}
