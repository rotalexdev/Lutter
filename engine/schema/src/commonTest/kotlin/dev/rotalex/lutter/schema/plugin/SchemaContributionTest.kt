package dev.rotalex.lutter.schema.plugin

import dev.rotalex.lutter.model.doc.PluginRequirement
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PluginId
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuildException
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The S4 plugin facet: descriptors, contributions, and the builder they install into.
 *
 * Each promise carries its own failure: the double install proves duplicates still fail
 * through the plugin path rather than around it.
 */
class SchemaContributionTest {

    private val textSpec: ComponentSpec =
        componentSpec(ComponentType("m3.Text"), version = 1) {
            metadata(displayName = "Text", category = Category.Basic)
            intrinsic()
        }

    private val descriptor: PluginDescriptor = PluginDescriptor(
        id = PluginId("forge.material3"),
        version = "1.0",
        apiVersion = 1,
    )

    private val contribution: SchemaContribution<ComponentSpec, String, String, String, String> =
        object : SchemaContribution<ComponentSpec, String, String, String, String> {
            override val descriptor: PluginDescriptor = this@SchemaContributionTest.descriptor

            override fun contribute(
                builder: SchemaBuilder<ComponentSpec, String, String, String, String>,
            ): Unit {
                builder.component(textSpec)
            }
        }

    @Test
    fun `a descriptor carries identity version api and no deps by default`() {
        assertEquals(PluginId("forge.material3"), descriptor.id)
        assertEquals("1.0", descriptor.version)
        assertEquals(1, descriptor.apiVersion)
        assertEquals(emptyList(), descriptor.dependsOn)
    }

    @Test
    fun `a descriptor carries its dependencies`() {
        val plugin = PluginDescriptor(
            id = PluginId("forge.extended"),
            version = "2.0",
            apiVersion = 1,
            dependsOn = listOf(PluginRequirement(PluginId("forge.material3"), "1.0")),
        )

        assertEquals(
            listOf(PluginRequirement(PluginId("forge.material3"), "1.0")),
            plugin.dependsOn,
        )
    }

    @Test
    fun `a contribution installs its specs into the builder`() {
        val schema: Schema<ComponentSpec, String, String, String, String> =
            Schema.build<ComponentSpec, String, String, String, String> {
                contribution.contribute(this)
            }

        assertSame(textSpec, schema.components.require(ComponentType("m3.Text")))
    }

    @Test
    fun `installing twice fails naming the key and the registry`() {
        val failure = assertFailsWith<SchemaBuildException> {
            Schema.build<ComponentSpec, String, String, String, String> {
                contribution.contribute(this)
                contribution.contribute(this)
            }
        }

        assertTrue(failure.message?.contains("m3.Text") == true)
        assertTrue(failure.message?.contains("components") == true)
    }
}
