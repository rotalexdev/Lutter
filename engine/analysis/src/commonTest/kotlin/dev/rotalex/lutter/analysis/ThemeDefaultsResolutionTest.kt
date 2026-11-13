package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.resolved.PropOrigin
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.buildDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * §14.2's per-component defaults, applied in the resolver: a node that names no value gets
 * the theme's default, a theme default beats the spec's, and the document's own value beats
 * both — each tier observable through [PropOrigin], which is also the codegen's omit rule.
 */
class ThemeDefaultsResolutionTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())
    private val pageId: PageId = PageId("p_home")

    @Test
    fun `a theme default lands when the node names nothing`() {
        val resolved = resolvedOf(
            themedDocument {
                componentDefaults(ComponentType("core.Column")) {
                    prop("axis", Value.Str("vertical"))
                }
            },
        )

        val root = assertNotNull(resolved.pages[pageId]).root
        val axis = assertNotNull(root.props[PropertyKey("axis")])
        assertEquals(PropOrigin.Theme, axis.origin)
        assertEquals(Value.Str("vertical"), (axis.value as PropertyValue.Const).value)
    }

    @Test
    fun `a theme default beats the spec default`() {
        val resolved = resolvedOf(
            themedDocument {
                componentDefaults(ComponentType("core.Column")) {
                    prop("spacing", Value.Dp(12f))
                }
            },
        )

        val root = assertNotNull(resolved.pages[pageId]).root
        val spacing = assertNotNull(root.props[PropertyKey("spacing")])
        assertEquals(PropOrigin.Theme, spacing.origin)
        assertEquals(Value.Dp(12f), (spacing.value as PropertyValue.Const).value)
    }

    @Test
    fun `an explicit value beats a theme default`() {
        val resolved = resolvedOf(
            themedDocument {
                componentDefaults(ComponentType("core.Column")) {
                    prop("spacing", Value.Dp(12f))
                }
            },
        ) {
            node(ColumnType) {
                prop("spacing", Value.Dp(4f))
            }
        }

        val root = assertNotNull(resolved.pages[pageId]).root
        val spacing = assertNotNull(root.props[PropertyKey("spacing")])
        assertEquals(PropOrigin.Specified, spacing.origin)
        assertEquals(Value.Dp(4f), (spacing.value as PropertyValue.Const).value)
    }

    @Test
    fun `a prop the theme does not default falls to the spec default`() {
        val resolved = resolvedOf(
            themedDocument {
                componentDefaults(ComponentType("core.Column")) {
                    prop("axis", Value.Str("vertical"))
                }
            },
        )

        val root = assertNotNull(resolved.pages[pageId]).root
        val spacing = assertNotNull(root.props[PropertyKey("spacing")])
        assertEquals(PropOrigin.Default, spacing.origin)
        assertEquals(Value.Dp(0f), (spacing.value as PropertyValue.Const).value)
    }

    /** A document whose single theme carries [block]; the unselected single theme applies. */
    private fun themedDocument(
        themeBlock: dev.rotalex.lutter.model.dsl.ThemeScope.() -> Unit,
        pageBlock: dev.rotalex.lutter.model.dsl.PageScope.() -> Unit = {
            node(ColumnType) { slot("children", emptyList()) }
        },
    ): UiDocument = buildDocument("demo") {
        theme("t", "Light", block = themeBlock)
        page(name = "Home", route = "home", id = pageId, block = pageBlock)
    }

    private fun resolvedOf(document: UiDocument) = assertNotNull(analyzer.analyze(document).resolved)
}
