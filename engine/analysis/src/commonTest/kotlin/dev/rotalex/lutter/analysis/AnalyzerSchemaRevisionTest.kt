package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.model.doc.SlotDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.buildDocument
import dev.rotalex.lutter.model.index.DirtyNodes
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * §29.1:2048's third cache component at the seam: the schema revision.
 *
 * The dirty set says which *nodes* an edit touched; the revision says which *schema* the
 * previous run resolved against. Both are inputs no per-node guard can recover after the
 * fact: a declaration edit reaches every instance of it through the overlay, a version note
 * reaches the spec every instance resolves against, and a different `SchemaView` reaches
 * every default the spec carries — none of which shows up as a changed scope set or a
 * changed typing. So the seam's carry rule is: reuse only from a run whose schema revision
 * matches this one's, and recompute otherwise.
 *
 * Every stale scenario here models the narrow caller — a dirty set folded from the ids the
 * edit names, which is what a per-node bridge answers — because that is the caller the
 * revision exists to catch. The conservative caller (record ops folded to `ALL`) reuses
 * nothing and needs no guard.
 */
class AnalyzerSchemaRevisionTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())

    /**
     * One page holding a `doc.hero` instance beside a leaf, and the declaration itself.
     * [withSlot] is the only difference the pair models: the declaration gains a content
     * slot, which changes the spec every instance of it resolves against without touching
     * any node the page carries.
     */
    private fun componentDocument(withSlot: Boolean): UiDocument = buildDocument("demo") {
        component(
            ComponentDeclId("hero"),
            "Hero",
            slots = if (withSlot) listOf(SlotDecl(SlotName("content"))) else emptyList(),
        ) {
            node(ColumnType, id = NodeId("c_root")) {
                val title = node(TextType, id = NodeId("c_title")) { prop("text", Value.Str("Hero")) }
                slot("children", listOf(title))
            }
        }
        page(name = "Home", route = "home") {
            node(ColumnType, id = NodeId("n_root")) {
                val hero = node(ComponentType("doc.hero"), id = NodeId("n_hero")) {}
                val leaf = node(TextType, id = NodeId("n_leaf")) { prop("text", Value.Str("Tail")) }
                slot("children", listOf(hero, leaf))
            }
        }
    }

    /**
     * The bridge's answer for the declaration edits the pair above models: the ids the edit
     * names, folded over their ancestor chains. The instance is on neither chain — a narrow
     * bridge cannot see that a declaration edit changes every instance's spec.
     */
    private fun dirtyOf(patched: UiDocument): DirtyNodes =
        DirtyNodes.of(setOf(NodeId("c_root")), DocumentIndex(patched))

    @Test
    fun `an overlay bump recomputes the instances, not carries them`() {
        val before = assertNotNull(analyzer.analyze(componentDocument(withSlot = false)).resolved)
        val patched = componentDocument(withSlot = true)
        val after = assertNotNull(analyzer.analyze(patched, dirtyOf(patched), before).resolved)
        val full = assertNotNull(analyzer.analyze(patched).resolved)

        // The instance's own spec changed shape: the full run answers with the gained slot,
        // and a carried instance would answer with the old one's.
        assertEquals(full.nodes[NodeId("n_hero")], after.nodes[NodeId("n_hero")])
        assertEquals(full, after)
    }

    @Test
    fun `an unchanged component surface still carries at reference identity`() {
        val before = assertNotNull(analyzer.analyze(componentDocument(withSlot = false)).resolved)
        val patched = componentDocument(withSlot = false)
        val after = assertNotNull(analyzer.analyze(patched, dirtyOf(patched), before).resolved)

        assertSame(before.nodes[NodeId("n_hero")], after.nodes[NodeId("n_hero")])
    }

    @Test
    fun `a component-version note refuses the memoized resolutions`() {
        val base = componentDocument(withSlot = false)
        val before = assertNotNull(analyzer.analyze(base).resolved)
        val patched = base.copy(
            meta = base.meta.copy(componentVersions = mapOf(ComponentType("doc.hero") to 2)),
        )
        val after = assertNotNull(analyzer.analyze(patched, dirtyOf(patched), before).resolved)

        // The version note is a record op — the honest bridge folds it to ALL and needs no
        // guard. The narrow bridge above is the caller the revision exists for, and its
        // answer here is observable: a memoized instance would be the very object the
        // previous run built.
        assertNotSame(before.nodes[NodeId("n_hero")], after.nodes[NodeId("n_hero")])
        val full = assertNotNull(analyzer.analyze(patched).resolved)
        assertEquals(full, after)
    }

    /**
     * The same vocabulary, rebuilt with one default the walking skeleton's `m3.Text` does
     * not carry, so the two analyzers are different schema contexts by construction.
     */
    private fun tagDefaultSchema(): Schema<ComponentSpec, ModifierSpec, String, String, String> =
        Schema.build<ComponentSpec, ModifierSpec, String, String, String> {
            component(
                componentSpec(ColumnType, 1) {
                    metadata("Column", Category.Layout)
                    property(prop<Float>("spacing", TypeRef.Dp, default = Value.Dp(0f)))
                    property(prop<String>("axis", TypeRef.Str))
                    slot("children", Cardinality.Many, provides = setOf(ColumnScope))
                    composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Column"))
                },
            )
            component(
                componentSpec(TextType, 1) {
                    metadata("Text", Category.Basic)
                    property(prop<String>("text", TypeRef.Str, required = true))
                    property(prop<Int>("maxLines", TypeRef.Int32, default = Value.Int32(1)))
                    property(prop<String>("tag", TypeRef.Str, bindable = false, default = Value.Str("")))
                    property(prop<String>("style", TypeRef.Nullable(TypeRef.Token(TokenKind.Typography))))
                    property(prop<String>("align", TypeRef.Enum(TypeId("Align"))))
                    composeCall(KotlinSymbol("androidx.compose.material3", "Text"))
                },
            )
        }

    /** One Column holding a Text that specifies no `tag`, so only the schema decides it. */
    private fun textDocument(): UiDocument = homeDocument {
        node(ColumnType, id = NodeId("n_root")) {
            val title = node(TextType, id = NodeId("n_title")) { prop("text", Value.Str("Hi")) }
            slot("children", listOf(title))
        }
    }

    @Test
    fun `a carry across schema contexts is recomputed against the new schema`() {
        val document = textDocument()
        val before = assertNotNull(Analyzer(testSchema()).analyze(document).resolved)
        val strict = Analyzer(tagDefaultSchema())
        val after = assertNotNull(
            strict.analyze(document, DirtyNodes.of(emptySet(), DocumentIndex(document)), before).resolved,
        )
        val full = assertNotNull(strict.analyze(document).resolved)

        // Nothing names n_title dirty, so only the revision can refuse the carry — and the
        // recomputed text then answers with the new schema's `tag` default, which a full run
        // of the same schema also answers with.
        assertEquals(full, after)
    }
}
