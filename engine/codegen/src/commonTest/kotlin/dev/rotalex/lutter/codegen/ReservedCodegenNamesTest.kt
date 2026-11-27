package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.analysis.resolved.ResolvedState
import dev.rotalex.lutter.analysis.resolved.ResolvedTheme
import dev.rotalex.lutter.model.doc.DataModelDecl
import dev.rotalex.lutter.model.doc.EnumEntryDecl
import dev.rotalex.lutter.model.doc.EnumTypeDecl
import dev.rotalex.lutter.model.doc.FieldDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which names the generator keeps for itself, and what a document that takes one is told.
 *
 * The refusals are the substance: a name nobody is refused today is one only the compile harness
 * finds. The accepted case is here for the other reason — a rule that only ever says no is
 * indistinguishable from one that says no too much, and §12.1 writes a state name as a class
 * member, which is the fact that keeps this from refusing every declaration of a state.
 */
class ReservedCodegenNamesTest {

    private val options: CodegenOptions = CodegenOptions("com.example.app")

    @Test
    fun `the generator reserves every name it writes itself`() {
        assertEquals(
            setOf(
                "AppHost",
                "SnackbarHost",
                "Route",
                "AppNavigator",
                "AppRoot",
                "AppState",
                "LocalAppState",
                "PersistentStore",
                "LocalPersistentStore",
            ),
            ReservedCodegenNames.literals(options),
        )
    }

    @Test
    fun `the navigation strategy reserves every name it emits`() {
        assertEquals(
            setOf("Route", "AppNavigator", "AppRoot"),
            SimpleBackStack("com.example.app").reservedTopLevelNames,
        )
    }

    @Test
    fun `the state strategy reserves every name it emits`() {
        assertEquals(
            setOf("AppState", "LocalAppState", "PersistentStore", "LocalPersistentStore"),
            ComposeSnapshotState.reservedTopLevelNames,
        )
    }

    @Test
    fun `a page's derived names are its screen, its state and the state's constructor`() {
        assertEquals(
            setOf("HomeScreen", "HomeScreenState", "rememberHomeScreenState"),
            ReservedCodegenNames.derivedFor("Home", ComposeSnapshotState),
        )
    }

    @Test
    fun `under the viewmodel strategy the reserved names say which layer is which`() {
        // The state keeps its name and the ViewModel takes its own, so a document cannot declare
        // either one out from under the file: two names, two layers, nothing renamed into a lie.
        assertEquals(
            setOf("HomeScreen", "HomeScreenState", "HomeViewModel"),
            ReservedCodegenNames.derivedFor("Home", ViewModelState),
        )
    }

    @Test
    fun `a page named after a generated route is refused`() {
        val document: ResolvedDocument = document(pages = listOf(home to "Route"))

        assertEquals(listOf("name.reserved"), codesOf(document))
    }

    @Test
    fun `a page named after the generated host interface is refused`() {
        val document: ResolvedDocument = document(pages = listOf(home to "AppHost"))

        assertEquals(listOf("name.reserved"), codesOf(document))
    }

    @Test
    fun `a component named after the generated navigator is refused`() {
        val document: ResolvedDocument = document(components = listOf(ComponentDeclId("AppNavigator")))

        assertEquals(listOf("name.reserved"), codesOf(document))
    }

    @Test
    fun `a page named after another page's screen is refused`() {
        val document: ResolvedDocument = document(pages = listOf(home to "Home", settings to "HomeScreen"))

        assertEquals(listOf("name.reserved"), codesOf(document))
    }

    @Test
    fun `a component named after another page's screen is refused`() {
        val document: ResolvedDocument = document(
            pages = listOf(home to "Home"),
            components = listOf(ComponentDeclId("HomeScreenState")),
        )

        assertEquals(listOf("name.reserved"), codesOf(document))
    }

    @Test
    fun `a state named after a generated type is not refused`() {
        // §12.1 writes a declaration as a member of the page or app holder, or as a local of the
        // composable. None of the reserved names is in that scope, so naming one is legal today —
        // and it is the review's list that says otherwise, not the generator.
        val document: ResolvedDocument =
            document(pages = listOf(home to "Home"), pageState = listOf(held("Route")))

        assertEquals(emptyList<String>(), codesOf(document))
    }

    @Test
    fun `a document that declares no generated name is not refused`() {
        val document: ResolvedDocument = document(
            pages = listOf(home to "Home", settings to "Settings"),
            components = listOf(ComponentDeclId("ProfileCard")),
            pageState = listOf(held("count")),
        )

        assertEquals(emptyList<String>(), codesOf(document))
    }

    @Test
    fun `the refusal names the page whose screen it collides with`() {
        val document: ResolvedDocument = document(pages = listOf(home to "Home", settings to "HomeScreen"))

        assertEquals(
            "'Page HomeScreen' is a reserved codegen name: page 'Home' emits a screen called 'HomeScreen'",
            ReservedCodegenNames.findings(document, options).single().message,
        )
    }

    @Test
    fun `the refusal points at the page rather than at a node`() {
        val document: ResolvedDocument = document(pages = listOf(home to "Route"))

        assertEquals(home, ReservedCodegenNames.findings(document, options).single().location.pageId)
    }

    @Test
    fun `a reserved name stops the generator before it writes a file`() {
        val result: CodegenResult =
            KotlinGenerator(schema(), options).generate(document(pages = listOf(home to "Route")))

        assertTrue(result.files.files.isEmpty(), "got ${result.files.files.map { it.path }}")
    }

    @Test
    fun `a model named after a generated route is refused`() {
        // D27 could not check this one: the name was not on `ResolvedDocument`, so nothing could
        // read it. `model/Models.kt` emits it as a top-level declaration, which is the same fact
        // that makes a page named `Route` a refusal.
        val document: ResolvedDocument = document(dataModels = listOf(model("Route")))

        assertEquals(listOf("name.reserved"), codesOf(document))
    }

    @Test
    fun `a model named after a page's screen is refused`() {
        val document: ResolvedDocument =
            document(pages = listOf(home to "Home"), dataModels = listOf(model("HomeScreen")))

        assertEquals(listOf("name.reserved"), codesOf(document))
    }

    @Test
    fun `a model and a component sharing a name are refused`() {
        // One declaration emitted twice: the component becomes a function called `User` and the
        // model becomes a data class called `User`. Neither of them is a reserved literal, which
        // is why the check has to remember what it has already claimed.
        val document: ResolvedDocument = document(
            components = listOf(ComponentDeclId("User")),
            dataModels = listOf(model("User")),
        )

        assertEquals(listOf("name.reserved"), codesOf(document))
        assertEquals(
            "'Data model User' is a reserved codegen name: component 'User' is emitted under the same name",
            ReservedCodegenNames.findings(document, options).single().message,
        )
    }

    @Test
    fun `two models sharing a name are refused`() {
        val document: ResolvedDocument = document(
            dataModels = listOf(model("User", id = "dm_user"), model("User", id = "dm_other")),
        )

        assertEquals(listOf("name.reserved"), codesOf(document))
    }

    @Test
    fun `an enum named after a generated type is refused`() {
        val document: ResolvedDocument = document(enums = listOf(enumeration("AppState")))

        assertEquals(listOf("name.reserved"), codesOf(document))
    }

    @Test
    fun `a model whose name nothing else emits is not refused`() {
        val document: ResolvedDocument = document(
            pages = listOf(home to "Home"),
            dataModels = listOf(model("User")),
            enums = listOf(enumeration("Role")),
        )

        assertEquals(emptyList<String>(), codesOf(document))
    }

    private fun codesOf(document: ResolvedDocument): List<String> =
        ReservedCodegenNames.findings(document, options).map { it.code.value }

    private val home: PageId = PageId("p_home")
    private val settings: PageId = PageId("p_settings")
    private val columnType: ComponentType = ComponentType("test.Column")

    private fun document(
        pages: List<Pair<PageId, String>> = emptyList(),
        components: List<ComponentDeclId> = emptyList(),
        pageState: List<ResolvedState> = emptyList(),
        dataModels: List<DataModelDecl> = emptyList(),
        enums: List<EnumTypeDecl> = emptyList(),
    ): ResolvedDocument {
        val nodes: MutableMap<NodeId, ResolvedNode> = LinkedHashMap()
        val resolved: MutableMap<PageId, ResolvedPage> = LinkedHashMap()
        for ((id, name) in pages) {
            val root: NodeId = NodeId("n_" + id.value)
            nodes[root] = column(root)
            resolved[id] = ResolvedPage(id, name, name.lowercase(), nodes.getValue(root), pageState)
        }
        val declared: MutableMap<ComponentDeclId, ResolvedNode> = LinkedHashMap()
        for (id in components) {
            val root: NodeId = NodeId("n_" + id.value)
            nodes[root] = column(root)
            declared[id] = nodes.getValue(root)
        }
        return ResolvedDocument(
            resolved,
            declared,
            nodes,
            ResolvedTheme(null),
            emptyList(),
            if (components.isEmpty()) emptyMap() else components.associateWith { pageState },
            dataModels = dataModels.associateBy { it.id },
            enums = enums.associateBy { it.id },
        )
    }

    private fun model(name: String, id: String = "dm_" + name.lowercase()): DataModelDecl =
        DataModelDecl(
            DataModelId(id),
            name,
            listOf(FieldDecl(PropertyKey("name"), TypeRef.Str)),
        )

    private fun enumeration(name: String): EnumTypeDecl =
        EnumTypeDecl(TypeId("t_" + name.lowercase()), name, listOf(EnumEntryDecl("Admin")))

    private fun column(id: NodeId): ResolvedNode =
        ResolvedNode(id, columnType, emptyMap(), emptyList(), emptyMap(), emptySet())

    private fun held(name: String): ResolvedState =
        ResolvedState(StateDecl(StateId("s_" + name), name, TypeRef.Int32, Value.Int32(0)), null)

    private fun schema(): Schema<ComponentSpec, ModifierSpec, String, FunctionSpec, String> =
        Schema.build {
            component(
                componentSpec(columnType, 1) {
                    metadata("Column", Category.Layout)
                    slot("children", Cardinality.Many)
                    composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Column")) {
                        slot("children", LambdaTarget.Trailing)
                    }
                },
            )
        }
}