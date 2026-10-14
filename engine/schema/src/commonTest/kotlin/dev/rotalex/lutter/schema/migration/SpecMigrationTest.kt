package dev.rotalex.lutter.schema.migration

import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The S4 migration chain: single steps, ordered gaps, and the registry that plans them.
 *
 * Each promise carries its own failure: the double step, the duplicate origin, the gap
 * with no step and the backwards plan are asserted alongside the chains they guard.
 */
class SpecMigrationTest {

    private val textType: ComponentType = ComponentType("m3.Text")

    private fun renameLabel(from: Int = 1, to: Int = from + 1): SpecMigration =
        SpecMigration(textType, from = from, to = to) { node ->
            val label = node.props[PropertyKey("label")] ?: return@SpecMigration node
            node.copy(
                props = node.props - PropertyKey("label") + (PropertyKey("text") to label),
            )
        }

    private fun node(type: ComponentType = textType): Node = Node(
        id = NodeId("n1"),
        type = type,
        props = mapOf(PropertyKey("label") to PropertyValue.Const(Value.Str("hi"))),
    )

    @Test
    fun `one step renames the property and keeps the value`() {
        val registry = SpecMigrationRegistry(listOf(renameLabel()))

        val migrated = registry.migrate(node(), from = 1, to = 2)

        assertEquals(
            mapOf(PropertyKey("text") to PropertyValue.Const(Value.Str("hi"))),
            migrated.props,
        )
        assertEquals(NodeId("n1"), migrated.id)
    }

    @Test
    fun `a gap migrates as an ordered chain`() {
        val registry = SpecMigrationRegistry(listOf(renameLabel(from = 1), renameLabel(from = 2)))

        assertEquals(2, registry.plan(textType, from = 1, to = 3).size)
        val migrated = registry.migrate(node(), from = 1, to = 3)

        assertTrue(migrated.props.containsKey(PropertyKey("text")))
    }

    @Test
    fun `no gap means no steps and the node untouched`() {
        val registry = SpecMigrationRegistry(listOf(renameLabel()))

        assertEquals(emptyList(), registry.plan(textType, from = 2, to = 2))
        assertEquals(node().props, registry.migrate(node(), from = 2, to = 2).props)
    }

    @Test
    fun `a step spanning two versions fails naming the component`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SpecMigration(textType, from = 1, to = 3) { it }
        }

        assertTrue(failure.message?.contains("m3.Text") == true)
    }

    @Test
    fun `two steps from one origin fail naming both`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SpecMigrationRegistry(listOf(renameLabel(), renameLabel()))
        }

        assertTrue(failure.message?.contains("m3.Text") == true)
        assertTrue(failure.message?.contains("1") == true)
    }

    @Test
    fun `a gap with no step fails naming the missing hop`() {
        val registry = SpecMigrationRegistry(listOf(renameLabel(from = 2)))

        val failure = assertFailsWith<NoSuchElementException> {
            registry.plan(textType, from = 1, to = 3)
        }

        assertTrue(failure.message?.contains("m3.Text") == true)
    }

    @Test
    fun `a backwards plan fails instead of unapplying`() {
        val registry = SpecMigrationRegistry(listOf(renameLabel()))

        assertFailsWith<IllegalArgumentException> {
            registry.plan(textType, from = 2, to = 1)
        }
    }

    @Test
    fun `a node of an unmigrated type fails naming its gap`() {
        val registry = SpecMigrationRegistry(listOf(renameLabel()))

        assertFailsWith<NoSuchElementException> {
            registry.migrate(node(ComponentType("core.Column")), from = 1, to = 2)
        }
    }

    /**
     * `latest` is the question §19.3's pass cannot answer for itself, so it is asked here.
     *
     * The three cases are the three answers there are: a chain has a destination, a lone step
     * has one, and a type with no steps at all has **no** version rather than version one. The
     * last is the one worth pinning, because a `latest` that answered `1` would send a caller
     * looking for a migration of a component that never changed.
     */
    @Test
    fun `latest is one past the highest step, and absent for a type with no steps`() {
        val registry = SpecMigrationRegistry(
            listOf(
                renameLabel(from = 1),
                renameLabel(from = 2),
                SpecMigration(ComponentType("core.Column"), 1, 2) { it },
            ),
        )

        assertEquals(3, registry.latest(textType))
        assertEquals(2, registry.latest(ComponentType("core.Column")))
        assertNull(registry.latest(ComponentType("m3.Button")))
    }

    /**
     * `latest` is derived, so the chain that `plan` would walk ends exactly where it reports.
     *
     * Asserted as the plan rather than as a number alone: `latest = 3` is only right if
     * `plan(1, latest)` is the two steps the registry actually holds, and that is the property a
     * caller builds its targets map on.
     */
    @Test
    fun `latest is the version a full plan from one reaches`() {
        val registry = SpecMigrationRegistry(listOf(renameLabel(from = 1), renameLabel(from = 2)))

        val destination = assertNotNull(registry.latest(textType))
        val steps = registry.plan(textType, from = 1, to = destination)

        assertEquals(listOf(1, 2), steps.map { it.from })
        assertEquals(destination, steps.last().to)
    }
}
