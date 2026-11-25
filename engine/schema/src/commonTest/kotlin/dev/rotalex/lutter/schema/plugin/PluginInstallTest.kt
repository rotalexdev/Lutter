package dev.rotalex.lutter.schema.plugin

import dev.rotalex.lutter.model.doc.PluginRequirement
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PluginId
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuildException
import dev.rotalex.lutter.schema.SchemaBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `install` (§8.2, §27.2, S6/S7): the builder records what it installed, refuses the two
 * load-order mistakes before the plugin contributes anything, and keeps the installation order.
 */
class PluginInstallTest {

    private typealias Stubs = Schema<String, String, String, String, String>

    private fun buildStubs(
        block: SchemaBuilder<String, String, String, String, String>.() -> Unit,
    ): Stubs = Schema.build(block)

    /** A contribution over the stub registries: one component, under the plugin's own key. */
    private fun contribution(
        id: String,
        version: String = "1.0.0",
        dependsOn: List<PluginRequirement> = emptyList(),
        component: String = id,
    ): SchemaContribution<String, String, String, String, String> {
        val descriptor = PluginDescriptor(
            id = PluginId(id),
            version = version,
            apiVersion = 1,
            dependsOn = dependsOn,
        )
        return object : SchemaContribution<String, String, String, String, String> {
            override val descriptor: PluginDescriptor = descriptor

            override fun contribute(builder: SchemaBuilder<String, String, String, String, String>) {
                builder.component(ComponentType(component), id)
            }
        }
    }

    @Test
    fun `an installed contribution reaches the schema and is recorded`() {
        val schema = buildStubs { install(contribution("forge.badge")) }

        assertEquals("forge.badge", schema.components.get(ComponentType("forge.badge")))
        assertEquals(listOf(PluginId("forge.badge")), schema.contributors.map { it.id })
    }

    /** Declaration order is the contract: a host reports what it loaded in the order it loaded. */
    @Test
    fun `contributors keep installation order`() {
        val schema = buildStubs {
            install(contribution("forge.second"))
            install(contribution("forge.first"))
        }

        assertEquals(
            listOf(PluginId("forge.second"), PluginId("forge.first")),
            schema.contributors.map { it.id },
        )
    }

    @Test
    fun `a schema with no plugins has no contributors`() {
        val schema = buildStubs { component(ComponentType("core.Column"), "stub") }

        assertEquals(emptyList(), schema.contributors)
    }

    @Test
    fun `one id installed twice is refused and the second contributes nothing`() {
        val failure = assertFailsWith<SchemaBuildException> {
            buildStubs {
                install(contribution("forge.badge", version = "1.0.0", component = "forge.first"))
                install(contribution("forge.badge", version = "2.0.0", component = "forge.second"))
            }
        }

        assertTrue(failure.message!!.contains("forge.badge"), failure.message!!)
        assertTrue(failure.message!!.contains("1.0.0"), failure.message!!)
        assertTrue(failure.message!!.contains("2.0.0"), failure.message!!)
    }

    @Test
    fun `a refused duplicate did not register its components`() {
        val builder = SchemaBuilder<String, String, String, String, String>().apply {
            install(contribution("forge.badge", component = "forge.first"))
        }

        assertFailsWith<SchemaBuildException> {
            builder.install(contribution("forge.badge", component = "forge.second"))
        }

        val schema = builder.build()
        assertEquals("forge.badge", schema.components.get(ComponentType("forge.first")))
        assertEquals(null, schema.components.get(ComponentType("forge.second")))
        assertEquals(1, schema.contributors.size)
    }

    @Test
    fun `a missing dependency is refused before contributing`() {
        val failure = assertFailsWith<SchemaBuildException> {
            buildStubs {
                install(
                    contribution(
                        "forge.badge",
                        dependsOn = listOf(PluginRequirement(PluginId("forge.core"), "1.0.0")),
                    ),
                )
            }
        }

        assertTrue(failure.message!!.contains("forge.badge"), failure.message!!)
        assertTrue(failure.message!!.contains("forge.core"), failure.message!!)
        assertTrue(failure.message!!.contains("not installed"), failure.message!!)
    }

    /** Order is the dependency order: installing the dependency first is the whole point. */
    @Test
    fun `a dependency installed first satisfies the requirement`() {
        val schema = buildStubs {
            install(contribution("forge.core"))
            install(
                contribution(
                    "forge.badge",
                    dependsOn = listOf(PluginRequirement(PluginId("forge.core"), "1.0.0")),
                ),
            )
        }

        assertEquals(
            listOf(PluginId("forge.core"), PluginId("forge.badge")),
            schema.contributors.map { it.id },
        )
    }

    /** ADR-018: exact string equality. A different version is a refusal naming both. */
    @Test
    fun `a dependency at another version is refused naming both`() {
        val failure = assertFailsWith<SchemaBuildException> {
            buildStubs {
                install(contribution("forge.core", version = "1.0.0"))
                install(
                    contribution(
                        "forge.badge",
                        dependsOn = listOf(PluginRequirement(PluginId("forge.core"), "2.0.0")),
                    ),
                )
            }
        }

        assertTrue(failure.message!!.contains("2.0.0"), failure.message!!)
        assertTrue(failure.message!!.contains("1.0.0"), failure.message!!)
    }

    /** A duplicate registry key inside `contribute()` is still the builder's own failure. */
    @Test
    fun `a contribution that duplicates a key fails as a schema build failure`() {
        val failure = assertFailsWith<SchemaBuildException> {
            buildStubs {
                component(ComponentType("forge.badge"), "already here")
                install(contribution("forge.badge"))
            }
        }

        assertTrue(failure.message!!.contains("forge.badge"), failure.message!!)
    }
}
