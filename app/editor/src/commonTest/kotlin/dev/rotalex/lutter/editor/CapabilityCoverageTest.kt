package dev.rotalex.lutter.editor

import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.node.Node
import dev.rotalex.lutter.model.node.NodeTable
import dev.rotalex.lutter.schema.component.ComponentSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Every capability the installed schema advertises has somewhere in the editor to reach it.
 *
 * ### Why this is driven by the registry and not by a list
 *
 * The bug this exists for is the one nobody notices: a component ships, the schema registers it,
 * and the palette never learns about it — so it is in the schema, in the generated code and in the
 * documentation, and nowhere in the app. A hand-written test that names the components it checks
 * cannot catch that, because the component it forgets is exactly the one nobody added. Reading
 * `BuiltinEngine.schema()` instead means the test grows with the product and fails the moment a
 * registry entry has no surface.
 *
 * ### The two surfaces, and the three that are commands instead
 *
 * The editor reaches the registry through two *panes*: the palette lists components and the
 * inspector lists properties. Modifiers, actions and functions are not panes: they are authored
 * through `Command` variants (`SetModifierArgs` is the one that exists today), and their coverage
 * is `CommandLegalityTest`'s and `CommandPlannerRoundTripTest`'s job, where a missing arm is a
 * compile error rather than an invisible gap. This test therefore holds the panes to their own
 * registries and does not pretend a modifier has a palette entry.
 */
class CapabilityCoverageTest {

    private val schema = BuiltinEngine.schema()

    /**
     * Components the palette deliberately omits, with the reason recorded in `Palette`.
     *
     * Exact, not a subset: a component that gains or loses a surface must change this line, which
     * is the review this test is asking for.
     */
    private val withoutSurface: Set<ComponentType> = setOf(
        // Engine plumbing: it renders a component's slot parameter, and a page has none.
        ComponentType("core.SlotOutlet"),
        // Its `source` is a required resource reference, and no resource is shipped to point at.
        ComponentType("m3.Image"),
    )

    private fun offered(): Set<ComponentType> =
        Palette.of(schema).flatMap { section -> section.entries.map { it.type } }.toSet()

    private fun installed(): Set<ComponentType> = schema.components.all().map { it.type }.toSet()

    @Test
    fun `every installed component has a palette entry or a recorded reason`() {
        val unrecorded: Set<ComponentType> = installed() - offered() - withoutSurface

        assertEquals(
            emptySet(),
            unrecorded,
            "components registered in the schema with no palette entry and no recorded reason: $unrecorded",
        )
    }

    @Test
    fun `the components without a surface are still exactly the recorded ones`() {
        assertEquals(
            withoutSurface,
            installed() - offered(),
            "a component gained or lost a palette entry: update the recorded exceptions",
        )
    }

    @Test
    fun `every palette entry's inspector shows every property the spec declares`() {
        val missing: List<String> = Palette.of(schema)
            .flatMap { section -> section.entries }
            .mapNotNull { entry ->
                val spec: ComponentSpec = assertNotNull(
                    schema.components[entry.type],
                    "the palette offers ${entry.type}, which the schema does not register",
                )
                val model = assertNotNull(
                    Inspector.of(documentWith(entry.type), schema, ROOT),
                    "the inspector cannot describe ${entry.type}",
                )
                val declared = spec.properties.map { it.key }
                val shown = model.properties.map { it.key }
                if (declared == shown) null else "${entry.type}: declared $declared, shown $shown"
            }

        assertEquals(emptyList(), missing, "properties with no editing surface")
    }

    /**
     * A document holding one node of [type] as the page root.
     *
     * The inspector reads the node and its spec and nothing else, so the smallest document that
     * names the component is enough — and it needs no legal drop target, which is what lets this
     * test ask about every component instead of only the ones the fixture page can host.
     */
    private fun documentWith(type: ComponentType): UiDocument = UiDocument(
        meta = DocumentMeta("Coverage"),
        app = AppSpec("com.example.coverage", HOME),
        pages = mapOf(HOME to Page(id = HOME, name = "Home", route = "/home", root = ROOT)),
        components = emptyMap(),
        nodes = NodeTable.EMPTY.with(Node(id = ROOT, type = type)),
    )

    private val ROOT: NodeId = NodeId("n_coverage_root")
}
