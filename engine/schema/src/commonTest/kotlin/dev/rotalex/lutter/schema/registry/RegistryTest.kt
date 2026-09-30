package dev.rotalex.lutter.schema.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The registry contract: duplicates name the key, reads never lie, builds are snapshots. */
class RegistryTest {

    @Test
    fun `duplicate registration throws naming the key`() {
        val builder = RegistryBuilder<String, String>()
        builder.register("a", "1")

        val failure = assertFailsWith<DuplicateKeyException> { builder.register("a", "2") }

        assertTrue(failure.message?.contains("a") == true)
    }

    @Test
    fun `registerAll stops at the first duplicate and names it`() {
        val builder = RegistryBuilder<String, String>()

        val failure = assertFailsWith<DuplicateKeyException> {
            builder.registerAll(listOf("a" to "1", "b" to "2", "a" to "3"))
        }

        assertTrue(failure.message?.contains("a") == true)
        assertTrue("b" in builder.build())
    }

    @Test
    fun `unknown keys fail naming the key`() {
        val registry = RegistryBuilder<String, String>().build()

        assertNull(registry["missing"])
        assertFalse("missing" in registry)
        val failure = assertFailsWith<NoSuchElementException> { registry.require("missing") }
        assertTrue(failure.message?.contains("missing") == true)
    }

    @Test
    fun `all iterates values sorted by key`() {
        val builder = RegistryBuilder<String, String>()
        builder.register("b", "2")
        builder.register("c", "3")
        builder.register("a", "1")

        assertEquals(listOf("1", "2", "3"), builder.build().all())
    }

    @Test
    fun `built registries ignore later builder writes`() {
        val builder = RegistryBuilder<String, String>()
        builder.register("a", "1")
        val registry = builder.build()
        builder.register("b", "2")

        assertEquals(listOf("1"), registry.all())
        assertFalse("b" in registry)
    }

    @Test
    fun `empty registry reads empty`() {
        val registry = RegistryBuilder<String, String>().build()

        assertEquals(emptyList(), registry.all())
        assertFalse("a" in registry)
    }
}
