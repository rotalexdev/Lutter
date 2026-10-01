package dev.rotalex.lutter.serialization.storage

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The storage contract where a filesystem exists.
 *
 * `desktopTest`, not `commonTest`: the wasmJs canary also compiles `commonTest`, and
 * `SystemFileSystem` throws in a browser. The fake covers the contract; the file store
 * covers the `nonWebMain` implementation against a real directory.
 */
class DocumentStorageTest {

    private val payload = byteArrayOf(0, 1, 2, 7, 127, -1)

    @Test
    fun `a store round-trips bytes at distinct locations`() = runBlocking {
        val storage: DocumentStorage = InMemoryDocumentStorage()
        storage.write("first.forge", payload)
        storage.write("second.forge", "text\n".encodeToByteArray())

        assertContentEquals(payload, storage.read("first.forge"))
        assertContentEquals("text\n".encodeToByteArray(), storage.read("second.forge"))
    }

    @Test
    fun `a store names its missing locations`() = runBlocking {
        val storage: DocumentStorage = InMemoryDocumentStorage()
        val failure = assertFailsWith<IllegalStateException> { storage.read("absent.forge") }

        assertTrue(failure.message?.contains("absent.forge") == true, "missing location names itself")
    }

    @Test
    fun `the file store round-trips bytes and replaces on rewrite`() = runBlocking {
        val directory = Files.createTempDirectory("lutter-storage-test")
        try {
            val storage: DocumentStorage = FileDocumentStorage()
            val location = directory.resolve("doc.forge").toString()

            storage.write(location, payload)
            assertContentEquals(payload, storage.read(location))

            val replacement = "replacement\n".encodeToByteArray()
            storage.write(location, replacement)
            assertContentEquals(replacement, storage.read(location))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the file store names its missing locations`() = runBlocking {
        val directory = Files.createTempDirectory("lutter-storage-test")
        try {
            val storage: DocumentStorage = FileDocumentStorage()
            val location = directory.resolve("absent.forge").toString()
            val failure = assertFailsWith<FileNotFoundException> { storage.read(location) }

            assertTrue(failure.message?.contains(location) == true, "missing location names itself")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

// The contract without a filesystem: entries keyed by location, misses named like the file store.
private class InMemoryDocumentStorage : DocumentStorage {
    private val entries = mutableMapOf<String, ByteArray>()

    override suspend fun read(location: String): ByteArray =
        entries[location] ?: throw IllegalStateException("no document at '$location'")

    override suspend fun write(location: String, bytes: ByteArray) {
        entries[location] = bytes.copyOf()
    }
}
