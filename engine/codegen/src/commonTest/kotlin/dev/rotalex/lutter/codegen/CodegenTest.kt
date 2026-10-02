package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.resolved.PropOrigin
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedModifier
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.analysis.resolved.ResolvedProp
import dev.rotalex.lutter.analysis.resolved.ResolvedTheme
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EmitCase
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.Positional
import dev.rotalex.lutter.schema.component.ScopeId
import dev.rotalex.lutter.schema.component.ValueEmit
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.modifier.ModifierEmit
import dev.rotalex.lutter.schema.modifier.ModifierMetadata
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.modifier.modifier
import dev.rotalex.lutter.schema.types.EnumEntrySpec
import dev.rotalex.lutter.schema.types.EnumTypeSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.schema.types.type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Codegen over hand-built resolved documents: no analyzer, no fixtures, no filesystem. */
class CodegenTest {

    private val columnType: ComponentType = ComponentType("test.Column")
    private val textType: ComponentType = ComponentType("test.Text")
    private val ghostType: ComponentType = ComponentType("test.Ghost")
    private val rowType: ComponentType = ComponentType("test.Row")
    private val boxType: ComponentType = ComponentType("test.Box")
    private val choiceType: ComponentType = ComponentType("test.Choice")
    private val paddingType: ModifierType = ModifierType("test.padding")
    private val casedPaddingType: ModifierType = ModifierType("test.casedPadding")
    private val weightType: ModifierType = ModifierType("test.weight")
    private val alignType: ModifierType = ModifierType("test.align")
    private val textKey: PropertyKey = PropertyKey("text")
    private val spacingKey: PropertyKey = PropertyKey("spacing")
    private val valueKey: PropertyKey = PropertyKey("value")
    private val allKey: PropertyKey = PropertyKey("all")
    private val horizontalKey: PropertyKey = PropertyKey("horizontal")
    private val verticalKey: PropertyKey = PropertyKey("vertical")
    private val weightKey: PropertyKey = PropertyKey("weight")
    private val alignKey: PropertyKey = PropertyKey("alignment")
    private val arrangementKey: PropertyKey = PropertyKey("horizontalArrangement")
    private val childrenSlot: SlotName = SlotName("children")
    private val arrangementId: TypeId = TypeId("testArrangement")
    private val alignmentId: TypeId = TypeId("testAlignment")
    private val rowScope: ScopeId = ScopeId("compose.RowScope")
    private val columnScope: ScopeId = ScopeId("compose.ColumnScope")
    private val boxScope: ScopeId = ScopeId("compose.BoxScope")

    // One entry, member-qualified the way an `EnumTypeSpec` declares it: the owner is the import.
    private val arrangementEnum: EnumTypeSpec = EnumTypeSpec(
        arrangementId,
        listOf(
            EnumEntrySpec("Center", KotlinSymbol("androidx.compose.foundation.layout", "Arrangement.Center")),
        ),
    )

    @Test
    fun `same document prints byte-identical output twice`() {
        val document = homeDocument(columnNode("n_1", emptyMap(), listOf(textNode("n_2", "Hi"))))
        val first = generate(document)
        val second = generate(document)

        assertEquals(first.files.files.map { it.path }, second.files.files.map { it.path })
        for (index in first.files.files.indices) {
            assertEquals(first.files.files[index].content, second.files.files[index].content)
        }
        for (file in first.files.files) {
            assertTrue(file.content.endsWith("\n"), "no trailing newline in ${file.path}")
            assertTrue(!file.content.contains("\r"), "CR in ${file.path}")
        }
    }

    @Test
    fun `a column and text screen prints the golden file`() {
        val document = homeDocument(columnNode("n_1", emptyMap(), listOf(textNode("n_2", "Hi"))))
        val result = generate(document)

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val screen = result.files.files.single { it.path == "screens/HomeScreen.kt" }
        // The import lines below spell `${"import"}` through a template: NoComposeInPureModulesTest
        // scans line-starts for `import androidx`, and a golden spelling them plainly trips it.
        assertEquals(
            """
            // Generated by Forge. Do not edit.
            package com.example.app.screens

            ${"import"} androidx.compose.foundation.layout.Column
            ${"import"} androidx.compose.material3.Text
            ${"import"} androidx.compose.runtime.Composable
            ${"import"} androidx.compose.ui.Modifier

            @Composable
            public fun HomeScreen(modifier: Modifier = Modifier) {
                Column(
                    modifier = modifier,
                ) {
                    Text("Hi")
                }
            }

            """.trimIndent(),
            screen.content,
        )
    }

    @Test
    fun `conflicting function names alias deterministically`() {
        val alpha = ComponentType("test.Alpha")
        val beta = ComponentType("test.Beta")
        val schema: Schema<ComponentSpec, ModifierSpec, String, String, String> =
            Schema.build {
                component(columnStub(columnType))
                component(textStub(alpha, KotlinSymbol("com.example.a", "Text")))
                component(textStub(beta, KotlinSymbol("com.example.b", "Text")))
            }
        val root = columnNode(
            "n_1",
            emptyMap(),
            listOf(textAs("n_2", alpha, "A"), textAs("n_3", beta, "B")),
        )
        val result = KotlinGenerator(schema, CodegenOptions("com.example.app"))
            .generate(homeDocument(root))

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val screen = result.files.files.single { it.path == "screens/HomeScreen.kt" }
        assertTrue(screen.content.contains("import com.example.a.Text\n"), screen.content)
        assertTrue(screen.content.contains("import com.example.b.Text as BText\n"), screen.content)
        assertTrue(screen.content.contains("Text(\"A\")"), screen.content)
        assertTrue(screen.content.contains("BText(\"B\")"), screen.content)
    }

    @Test
    fun `files come out sorted by path`() {
        val pages = linkedMapOf(
            PageId("p_zeta") to ResolvedPage(
                PageId("p_zeta"),
                "Zeta",
                "zeta",
                columnNode("n_1", emptyMap(), listOf(textNode("n_2", "Z"))),
            ),
            PageId("p_alpha") to ResolvedPage(
                PageId("p_alpha"),
                "Alpha",
                "alpha",
                columnNode("n_3", emptyMap(), listOf(textNode("n_4", "A"))),
            ),
        )
        val result = generate(ResolvedDocument(pages, emptyMap(), indexOf(pages), ResolvedTheme(null)))

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val paths = result.files.files.map { it.path }
        assertEquals(paths.sorted(), paths)
        assertEquals(listOf("App.kt", "screens/AlphaScreen.kt", "screens/ZetaScreen.kt"), paths)
    }

    @Test
    fun `a document carrying errors emits nothing`() {
        val document = homeDocument(columnNode("n_1", emptyMap(), listOf(textNode("n_2", "Hi"))))
        val incoming = listOf(
            Diagnostic(Severity.Error, DiagnosticCodes.ComponentUnknown, DiagnosticLocation(), "boom"),
        )
        val result = generate(document, incoming)

        assertTrue(result.files.files.isEmpty())
        assertEquals(incoming, result.diagnostics)
    }

    @Test
    fun `a node without a spec reports no binding`() {
        val root = ResolvedNode(NodeId("n_1"), ghostType, emptyMap(), emptyList(), emptyMap(), emptySet())
        val result = generate(homeDocument(root))

        assertTrue(result.files.files.isEmpty())
        assertEquals(listOf("codegen.no_binding"), result.diagnostics.map { it.code.value })
    }

    @Test
    fun `a computed property reports unsupported`() {
        val prop = ResolvedProp(
            textKey,
            PropertyValue.Computed(Expr.Const(Value.Str("x"))),
            PropOrigin.Specified,
            null,
        )
        val root = ResolvedNode(NodeId("n_1"), textType, mapOf(textKey to prop), emptyList(), emptyMap(), emptySet())
        val result = generate(homeDocument(root))

        assertTrue(result.files.files.isEmpty())
        assertEquals(listOf("codegen.strategy_unsupported"), result.diagnostics.map { it.code.value })
    }

    @Test
    fun `a spacing reads as an arrangement call`() {
        val props = mapOf(
            spacingKey to ResolvedProp(
                spacingKey,
                PropertyValue.Const(Value.Dp(8f)),
                PropOrigin.Specified,
                null,
            ),
        )
        val result = generate(homeDocument(columnNode("n_1", props, listOf(textNode("n_2", "Hi")))))

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val screen = result.files.files.single { it.path == "screens/HomeScreen.kt" }
        assertTrue(
            screen.content.contains("verticalArrangement = Arrangement.spacedBy(8.0.dp)"),
            screen.content,
        )
        assertTrue(
            screen.content.contains("import androidx.compose.foundation.layout.Arrangement\n"),
            screen.content,
        )
    }

    @Test
    fun `modifiers chain off the modifier parameter`() {
        val entry = ResolvedModifier(
            paddingType,
            mapOf(
                PropertyKey("all") to ResolvedProp(
                    PropertyKey("all"),
                    PropertyValue.Const(Value.Dp(8f)),
                    PropOrigin.Specified,
                    null,
                ),
            ),
        )
        val root = ResolvedNode(
            NodeId("n_1"),
            columnType,
            emptyMap(),
            listOf(entry),
            mapOf(childrenSlot to listOf(textNode("n_2", "Hi"))),
            emptySet(),
        )
        val result = generate(homeDocument(root))

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val screen = result.files.files.single { it.path == "screens/HomeScreen.kt" }
        assertTrue(screen.content.contains("modifier = modifier\n"), screen.content)
        assertTrue(screen.content.contains(".padding(all = 8.0.dp)"), screen.content)
        assertTrue(
            screen.content.contains("import androidx.compose.foundation.layout.padding\n"),
            screen.content,
        )
    }

    @Test
    fun `a modifier case picks the call its arguments match`() {
        val shapes = listOf(
            listOf(allKey to 8f) to ".padding(8.0.dp)",
            listOf(horizontalKey to 8f, verticalKey to 4f) to
                ".padding(horizontal = 8.0.dp, vertical = 4.0.dp)",
            listOf(horizontalKey to 8f) to ".padding(horizontal = 8.0.dp)",
            listOf(verticalKey to 4f) to ".padding(vertical = 4.0.dp)",
        )

        for ((args, expected) in shapes) {
            val content = paddedScreen(args)
            assertTrue(content.contains(expected), "$args\n$content")
            assertTrue(
                content.contains("import androidx.compose.foundation.layout.padding\n"),
                content,
            )
            // A value filled into a pattern carries its own import: the pattern is text.
            assertTrue(content.contains("import androidx.compose.ui.unit.dp\n"), content)
        }
    }

    @Test
    fun `the first declared modifier case wins`() {
        val content = paddedScreen(listOf(allKey to 8f, verticalKey to 4f))

        assertTrue(content.contains(".padding(8.0.dp)"), content)
        assertTrue(!content.contains("vertical"), content)
    }

    @Test
    fun `a named arrangement emits the symbol its enum declares`() {
        val result = arrangementScreen(withEnum = true)

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val screen = result.files.files.single { it.path == "screens/HomeScreen.kt" }
        assertTrue(
            screen.content.contains("horizontalArrangement = Arrangement.Center"),
            screen.content,
        )
        assertTrue(
            screen.content.contains("import androidx.compose.foundation.layout.Arrangement\n"),
            screen.content,
        )
    }

    @Test
    fun `an enum entry with no registered symbol refuses`() {
        val result = arrangementScreen(withEnum = false)

        assertTrue(result.files.files.isEmpty())
        assertEquals(listOf("codegen.strategy_unsupported"), result.diagnostics.map { it.code.value })
    }

    @Test
    fun `a scope member's name is not written as an import`() {
        val content = screenOf(scopedResult(rowType, weightEntry()), "row weight")

        // `RowScope.weight` has no importable FQN; the call resolves through the receiver the
        // parent's content lambda opens, so an import would name something that does not exist.
        assertTrue(content.contains(".weight(weight = 1f)"), content)
        assertTrue(!content.contains("import androidx.compose.foundation.layout.weight"), content)
    }

    @Test
    fun `align reads the vertical band inside a row`() {
        val content = screenOf(scopedResult(rowType, alignEntry()), "row align")

        assertTrue(content.contains(".align(Alignment.Bottom)"), content)
        assertTrue(content.contains("import androidx.compose.ui.Alignment\n"), content)
        assertTrue(!content.contains("import androidx.compose.foundation.layout.align"), content)
    }

    @Test
    fun `align reads the horizontal band inside a column`() {
        val content = screenOf(scopedResult(columnType, alignEntry()), "column align")

        assertTrue(content.contains(".align(AbsoluteAlignment.Right)"), content)
        assertTrue(content.contains("import androidx.compose.ui.AbsoluteAlignment\n"), content)
    }

    @Test
    fun `align reads both axes inside a box`() {
        val content = screenOf(scopedResult(boxType, alignEntry()), "box align")

        assertTrue(content.contains(".align(AbsoluteAlignment.BottomRight)"), content)
    }

    @Test
    fun `the nearest open scope reads the entry`() {
        // A row inside a box: the row is what the aligned node sits in, so the row's axis wins.
        val content = screenOf(scopedResult(boxType, alignEntry(), nested = true), "row in box")

        assertTrue(content.contains(".align(Alignment.Bottom)"), content)
        assertTrue(!content.contains("BottomRight"), content)
    }

    @Test
    fun `align outside a layout scope refuses`() {
        val root = textAs("n_root", textType, "Hi").copy(modifiers = listOf(alignEntry()))
        val result = KotlinGenerator(scopedSchema(), CodegenOptions("com.example.app"))
            .generate(homeDocument(root))

        assertTrue(result.files.files.isEmpty())
        assertEquals(listOf("codegen.strategy_unsupported"), result.diagnostics.map { it.code.value })
    }

    @Test
    fun `a parameter reads the property the node carries and nothing else`() {
        val value = ResolvedProp(valueKey, PropertyValue.Const(Value.Str("Hi")), PropOrigin.Specified, null)
        val root = ResolvedNode(
            NodeId("n_1"),
            choiceType,
            mapOf(valueKey to value),
            emptyList(),
            emptyMap(),
            emptySet(),
        )
        val result = generate(homeDocument(root))

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val screen = result.files.files.single { it.path == "screens/HomeScreen.kt" }
        assertTrue(screen.content.contains("value = \"Hi\""), screen.content)
        assertTrue(!screen.content.contains("label"), screen.content)
    }

    private fun paddedScreen(args: List<Pair<PropertyKey, Float>>): String {
        val entry = ResolvedModifier(
            casedPaddingType,
            args.associate { (key, value) ->
                key to ResolvedProp(key, PropertyValue.Const(Value.Dp(value)), PropOrigin.Specified, null)
            },
        )
        val root = ResolvedNode(NodeId("n_1"), columnType, emptyMap(), listOf(entry), emptyMap(), emptySet())
        val result = generate(homeDocument(root))

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        return result.files.files.single { it.path == "screens/HomeScreen.kt" }.content
    }

    /**
     * A layout of [parent] type at the root holding one Text that carries [modifier].
     *
     * [nested] puts a row between them, which is the only way a node can have two open scopes.
     */
    private fun scopedResult(
        parent: ComponentType,
        modifier: ResolvedModifier,
        nested: Boolean = false,
    ): CodegenResult {
        val child = textAs("n_a", textType, "Hi").copy(modifiers = listOf(modifier))
        val kids = if (nested) listOf(layoutNode("n_mid", rowType, listOf(child))) else listOf(child)
        val root = layoutNode("n_root", parent, kids)
        return KotlinGenerator(scopedSchema(), CodegenOptions("com.example.app"))
            .generate(homeDocument(root))
    }

    private fun screenOf(result: CodegenResult, fixture: String): String {
        assertTrue(result.diagnostics.isEmpty(), "$fixture: ${result.diagnostics}")
        return result.files.files.single { it.path == "screens/HomeScreen.kt" }.content
    }

    private fun weightEntry(): ResolvedModifier = ResolvedModifier(
        weightType,
        mapOf(
            weightKey to ResolvedProp(
                weightKey,
                PropertyValue.Const(Value.Float32(1f)),
                PropOrigin.Specified,
                null,
            ),
        ),
    )

    private fun alignEntry(): ResolvedModifier = ResolvedModifier(
        alignType,
        mapOf(
            alignKey to ResolvedProp(
                alignKey,
                PropertyValue.Const(Value.Enum("BottomRight")),
                PropOrigin.Specified,
                null,
            ),
        ),
    )

    /** One entry, the three projections `AlignModifier` declares for it. */
    private val alignEntries: Map<ScopeId, Map<String, KotlinSymbol>> = mapOf(
        rowScope to mapOf("BottomRight" to KotlinSymbol("androidx.compose.ui", "Alignment.Bottom")),
        columnScope to mapOf("BottomRight" to KotlinSymbol("androidx.compose.ui", "AbsoluteAlignment.Right")),
        boxScope to mapOf("BottomRight" to KotlinSymbol("androidx.compose.ui", "AbsoluteAlignment.BottomRight")),
    )

    private fun scopedSchema(): Schema<ComponentSpec, ModifierSpec, String, String, TypeSpec> =
        Schema.build<ComponentSpec, ModifierSpec, String, String, TypeSpec> {
            component(layoutStub(rowType, "Row", rowScope))
            component(layoutStub(columnType, "Column", columnScope))
            component(layoutStub(boxType, "Box", boxScope))
            component(textStub(textType, KotlinSymbol("androidx.compose.material3", "Text")))
            modifier(weightStub())
            modifier(alignStub())
            type(
                EnumTypeSpec(
                    alignmentId,
                    listOf(
                        EnumEntrySpec(
                            "BottomRight",
                            KotlinSymbol("androidx.compose.ui", "AbsoluteAlignment.BottomRight"),
                        ),
                    ),
                ),
            )
        }

    /** A layout whose `children` slot provides [scope], which is what the scope tests walk into. */
    private fun layoutStub(type: ComponentType, name: String, scope: ScopeId): ComponentSpec =
        componentSpec(type, 1) {
            metadata(name, Category.Layout)
            slot("children", Cardinality.Many, provides = setOf(scope))
            composeCall(KotlinSymbol("androidx.compose.foundation.layout", name)) {
                slot("children", LambdaTarget.Trailing)
            }
        }

    private fun weightStub(): ModifierSpec = ModifierSpec(
        weightType,
        ModifierMetadata("Weight"),
        listOf(prop<Float>("weight", TypeRef.Float32)),
        requiresScope = setOf(rowScope),
        emit = ModifierEmit(
            KotlinSymbol("androidx.compose.foundation.layout", "weight"),
            scopeMember = true,
        ),
    )

    private fun alignStub(): ModifierSpec = ModifierSpec(
        alignType,
        ModifierMetadata("Align"),
        listOf(prop<String>("alignment", TypeRef.Enum(alignmentId), required = true)),
        emit = ModifierEmit(
            KotlinSymbol("androidx.compose.foundation.layout", "align"),
            listOf(EmitCase(setOf(alignKey), "align({alignment})")),
            scopeMember = true,
            scopeEntries = alignEntries,
        ),
    )

    // The enum is registered or not, which is the whole difference between the two arrangement
    // tests: a symbol nobody declared cannot be written down.
    private fun arrangementScreen(withEnum: Boolean): CodegenResult {
        val schema: Schema<ComponentSpec, ModifierSpec, String, String, TypeSpec> =
            Schema.build<ComponentSpec, ModifierSpec, String, String, TypeSpec> {
                component(rowStub())
                if (withEnum) type(arrangementEnum)
            }
        val entry = ResolvedProp(
            arrangementKey,
            PropertyValue.Const(Value.Enum("Center")),
            PropOrigin.Specified,
            null,
        )
        val root = ResolvedNode(
            NodeId("n_1"),
            rowType,
            mapOf(entry.key to entry),
            emptyList(),
            emptyMap(),
            emptySet(),
        )
        return KotlinGenerator(schema, CodegenOptions("com.example.app")).generate(homeDocument(root))
    }

    private fun generate(
        document: ResolvedDocument,
        incoming: List<Diagnostic> = emptyList(),
    ): CodegenResult =
        KotlinGenerator(testSchema(), CodegenOptions("com.example.app")).generate(document, incoming)

    private fun homeDocument(root: ResolvedNode): ResolvedDocument {
        val pages = mapOf(PageId("p_home") to ResolvedPage(PageId("p_home"), "Home", "home", root))
        return ResolvedDocument(pages, emptyMap(), indexOf(pages), ResolvedTheme(null))
    }

    private fun indexOf(pages: Map<PageId, ResolvedPage>): Map<NodeId, ResolvedNode> {
        val index = LinkedHashMap<NodeId, ResolvedNode>()
        fun walk(node: ResolvedNode) {
            index[node.id] = node
            for (kids in node.slots.values) kids.forEach(::walk)
        }
        for (page in pages.values) walk(page.root)
        return index
    }

    private fun textNode(id: String, text: String): ResolvedNode = textAs(id, textType, text)

    private fun textAs(id: String, type: ComponentType, text: String): ResolvedNode = ResolvedNode(
        NodeId(id),
        type,
        mapOf(
            textKey to ResolvedProp(
                textKey,
                PropertyValue.Const(Value.Str(text)),
                PropOrigin.Specified,
                null,
            ),
        ),
        emptyList(),
        emptyMap(),
        emptySet(),
    )

    private fun columnNode(
        id: String,
        props: Map<PropertyKey, ResolvedProp>,
        kids: List<ResolvedNode>,
    ): ResolvedNode = ResolvedNode(
        NodeId(id),
        columnType,
        props,
        emptyList(),
        if (kids.isEmpty()) emptyMap() else mapOf(childrenSlot to kids),
        emptySet(),
    )

    private fun layoutNode(id: String, type: ComponentType, kids: List<ResolvedNode>): ResolvedNode =
        ResolvedNode(
            NodeId(id),
            type,
            emptyMap(),
            emptyList(),
            if (kids.isEmpty()) emptyMap() else mapOf(childrenSlot to kids),
            emptySet(),
        )

    private fun testSchema(): Schema<ComponentSpec, ModifierSpec, String, String, String> =
        Schema.build {
            val spacing = prop<Float>("spacing", TypeRef.Dp)
            component(
                componentSpec(columnType, 1) {
                    metadata("Column", Category.Layout)
                    property(spacing)
                    slot("children", Cardinality.Many)
                    composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Column")) {
                        param(
                            "verticalArrangement",
                            from = listOf(spacing.key),
                            emit = ValueEmit.Cases(
                                listOf(
                                    EmitCase(
                                        setOf(spacing.key),
                                        "Arrangement.spacedBy({spacing})",
                                        listOf(
                                            KotlinSymbol(
                                                "androidx.compose.foundation.layout",
                                                "Arrangement",
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        )
                        slot("children", LambdaTarget.Trailing)
                    }
                },
            )
            component(textStub(textType, KotlinSymbol("androidx.compose.material3", "Text")))
            component(choiceStub())
            modifier(
                ModifierSpec(
                    paddingType,
                    ModifierMetadata("Padding"),
                    listOf(prop<Float>("all", TypeRef.Dp)),
                    emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation.layout", "padding")),
                ),
            )
            modifier(casedPaddingStub())
        }

    // `PaddingModifier`'s four cases in the same order: the first match is what `all` relies on.
    private fun casedPaddingStub(): ModifierSpec = ModifierSpec(
        casedPaddingType,
        ModifierMetadata("Padding"),
        listOf(
            prop<Float>("all", TypeRef.Dp),
            prop<Float>("horizontal", TypeRef.Dp),
            prop<Float>("vertical", TypeRef.Dp),
        ),
        emit = ModifierEmit(
            KotlinSymbol("androidx.compose.foundation.layout", "padding"),
            listOf(
                EmitCase(setOf(allKey), "padding({all})"),
                EmitCase(
                    setOf(horizontalKey, verticalKey),
                    "padding(horizontal = {horizontal}, vertical = {vertical})",
                ),
                EmitCase(setOf(horizontalKey), "padding(horizontal = {horizontal})"),
                EmitCase(setOf(verticalKey), "padding(vertical = {vertical})"),
            ),
        ),
    )

    // `RowSpec`'s arrangement half, with the document's enum spelled the way a document would.
    private fun rowStub(): ComponentSpec {
        val arrangement = prop<String?>("horizontalArrangement", TypeRef.Nullable(TypeRef.Enum(arrangementId)))
        return componentSpec(rowType, 1) {
            metadata("Row", Category.Layout)
            property(arrangement)
            composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Row")) {
                param(
                    "horizontalArrangement",
                    from = listOf(arrangement.key),
                    emit = ValueEmit.Cases(
                        listOf(EmitCase(setOf(arrangement.key), "{horizontalArrangement}")),
                    ),
                )
            }
        }
    }

    // Two declared properties, one parameter: the node carries the second, and `from` lists the
    // first, which is the order a naive fill reads in the wrong direction.
    private fun choiceStub(): ComponentSpec {
        val label = prop<String>("label", TypeRef.Str)
        val value = prop<String>("value", TypeRef.Str)
        return componentSpec(choiceType, 1) {
            metadata("Choice", Category.Basic)
            property(label)
            property(value)
            composeCall(KotlinSymbol("com.example.ui", "Choice")) {
                param("value", from = listOf(label.key, value.key))
            }
        }
    }

    private fun columnStub(type: ComponentType): ComponentSpec =
        componentSpec(type, 1) {
            metadata("Column", Category.Layout)
            slot("children", Cardinality.Many)
            composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Column")) {
                slot("children", LambdaTarget.Trailing)
            }
        }

    private fun textStub(type: ComponentType, function: KotlinSymbol): ComponentSpec {
        val text = prop<String>("text", TypeRef.Str, required = true)
        return componentSpec(type, 1) {
            metadata("Text", Category.Basic)
            property(text)
            composeCall(function) {
                param("text", from = text, positional = Positional.WhenSole)
            }
        }
    }
}
