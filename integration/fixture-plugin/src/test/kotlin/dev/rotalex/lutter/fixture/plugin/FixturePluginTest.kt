package dev.rotalex.lutter.fixture.plugin

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PluginId
import dev.rotalex.lutter.model.plugin.PluginApiVersion
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the fixture plugin claims, checked from outside it.
 *
 * The three claims are the ones P6 and P7 are built on:
 *
 * 1. it installs through `SchemaBuilder.install` like any other contribution, and the result
 *    records it in `contributors`;
 * 2. its manifest identity is the one the container will be written with;
 * 3. `SCHEMA_ENTRY_POINT` names the object a reflective loader actually finds — a string that
 *    drifted from the declaration would make P6 package a container nothing can load, and this is
 *    the cheapest place to notice.
 */
class FixturePluginTest {

    private typealias Specs = SchemaBuilder<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>

    private fun schema(
        block: Specs.() -> Unit = { install(FixturePlugin) },
    ): Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> = Schema.build(block)

    @Test
    fun `installing the fixture records it as a contributor`() {
        val built = schema()

        assertEquals(listOf(PluginId("forge.fixture")), built.contributors.map { it.id })
        assertEquals(listOf("1.0.0"), built.contributors.map { it.version })
    }

    @Test
    fun `the contributed component is the one the spec declares`() {
        val built = schema()

        assertSame(FixturePlugin.spec, built.components.get(ComponentType("fixture.badge.Badge")))
    }

    /** The manifest the packaging plugin writes and the descriptor the schema records are one value. */
    @Test
    fun `the manifest identity matches the descriptor`() {
        assertEquals(FixturePlugin.descriptor.id, PluginId("forge.fixture"))
        assertEquals(FixturePlugin.descriptor.version, "1.0.0")
        assertTrue(
            FixturePlugin.descriptor.apiVersion == PluginApiVersion.Supported,
            "The fixture must stay loadable by the engine it ships beside.",
        )
    }

    /** No `dependsOn`: the fixture needs nothing but the engine, so a load order can never refuse it. */
    @Test
    fun `the fixture declares no dependencies`() {
        assertTrue(FixturePlugin.descriptor.dependsOn.isEmpty())
    }

    /**
     * P7's loader resolves the entry point by this exact name, and by the `INSTANCE` field rather
     * than `KClass.objectInstance`: the CLI has no `kotlin-reflect` on its classpath, so a loader
     * that needed it would not run where it ships. A drifted string fails here instead.
     */
    @Test
    fun `the declared entry point names the object`() {
        val loaded = Class.forName(FixturePlugin.SCHEMA_ENTRY_POINT)

        assertSame(FixturePlugin, loaded.getField("INSTANCE").get(null))
    }
}
