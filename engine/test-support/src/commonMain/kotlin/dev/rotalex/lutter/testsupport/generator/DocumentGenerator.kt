package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.ColorRole
import dev.rotalex.lutter.model.doc.ColorSpec
import dev.rotalex.lutter.model.doc.ComponentDecl
import dev.rotalex.lutter.model.doc.DataModelDecl
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.EnumEntryDecl
import dev.rotalex.lutter.model.doc.EnumTypeDecl
import dev.rotalex.lutter.model.doc.FieldDecl
import dev.rotalex.lutter.model.doc.HostFunctionDecl
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.ParamDecl
import dev.rotalex.lutter.model.doc.Persistence
import dev.rotalex.lutter.model.doc.ShapeRole
import dev.rotalex.lutter.model.doc.SlotDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.TextRole
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.expr.UnaryOp
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EventSpec
import dev.rotalex.lutter.schema.component.PropertyRule
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.ScopeId
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.overlay.documentDeclIdOf
import dev.rotalex.lutter.schema.types.EnumTypeSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import kotlin.random.Random

/**
 * Builds `UiDocument`s from a seed, and reports what it meant to build.
 *
 * PLAN §36 wants a thousand documents per property, three of those properties, and a nightly
 * batch over two hundred more. Nothing in this repository produces a document that was not
 * written out by hand, and §5.6 is why that is a problem rather than an inconvenience: the
 * normal form means a document put together in the wrong order is *identical* to one put
 * together in the right order, so every existence check and every count a test might write
 * passes on a document the engine cannot read. The only way to notice is to ask the engine.
 *
 * ### Determinism, and why it is a seed and not a library
 *
 * Every draw comes from one [Random] seeded from [seed], so the same seed is the same document
 * and a failing case is one integer. §36 asks for reproducibility across runs and operating
 * systems, and a seed is what buys it. The alternative — a property-testing framework — would be
 * a new dependency on both of §21's targets and on every test source set that used it, in a
 * repository that has twice chosen to write a small thing rather than take a dependency that does
 * not fit (`PLAN.md` §4.1's C6 row is the same argument about KotlinPoet). What a framework
 * would add is shrinking; what it would cost is a transitive surface nobody here has read.
 * Shrinking is a nicety and the seed is the requirement.
 *
 * The seed is folded to an `Int` because that is what `Random`'s constructor takes. Two `Long`
 * seeds agreeing in their low thirty-two bits agree here, which is the whole of the caveat.
 *
 * ### Every document this class produces is valid, and that is a test's claim
 *
 * Nothing here refuses to build an invalid document — a generator with a validity filter is a
 * generator whose failures have been thrown away. The construction only ever writes shapes the
 * analysis passes accept: a required property is always written, a `Cardinality` is always
 * honoured, a `ChildFilter` is consulted before a child is chosen rather than after one is
 * built, and `layout.weight` is only applied where `ScopeAnalysis` will have put `RowScope` in
 * reach. `DocumentGeneratorValidityTest` holds it to that by running every result through
 * `Analyzer.analyze` and requiring no error-severity diagnostic.
 *
 * ### What the schema has to supply
 *
 * A schema whose components, modifiers, actions, enums and functions are all real: the
 * generator reads `Registry.all()` and writes against what it finds, so a stub registry yields
 * a document no pass can resolve. [generatorSchema] is the one this module ships.
 *
 * ### The contract, stated once
 *
 * `generate()` is a pure function of [seed]. Calling it twice on one instance produces the same
 * document both times, and that is not an accident of how [reset] happens to be written — see
 * the two lines at the top of the method.
 *
 * The stronger half of that promise is **the only random source this class reads is the one it
 * constructs from [seed]**. Everything in the stdlib that picks an element for you —
 * `randomOrNull()`, `random()`, `shuffled()` — reaches `Random.Default` instead, which is seeded
 * by the platform, and a document that consulted one would still look deterministic to a test
 * that builds it once or that reuses one generator. `DocumentGeneratorDeterminismTest`'s
 * isolation case is the test that holds it: it disturbs the platform generator between two builds
 * of the same seed and requires the documents to be unchanged.
 *
 * ### The one thing it will not do
 *
 * A node's `type` is written from the registry and from the document's own `doc.*` names, and
 * from nowhere else. There is no branch over component types in this file, and §7.4 forbids one
 * in the engine; a component this registry does not hold therefore never appears, which is the
 * same constraint the engine itself works under.
 */
public class DocumentGenerator(
    schema: SchemaView<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,

    /** The seed every draw comes from, and the first word of every failure about a document. */
    public val seed: Long,
) {

    private val view: SchemaView<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> = schema

    /**
     * The components this generator may place, read once.
     *
     * `Registry.all()` returns a copy, so holding the list costs nothing and keeps every later
     * choice off the registry: a document that looked a component up per node would have bytes
     * that depend on lookup order.
     */
    private val components: List<ComponentSpec> = view.components.all()

    /** The modifiers this generator may apply, narrowed per node by the scopes in reach. */
    private val modifierSpecs: List<ModifierSpec> = view.modifiers.all()

    private var random: Random = Random(seed.toInt())

    private var mint: IdMint = IdMint(random)

    // -------------------------------------------------------------------------------------
    // What the run has decided so far.
    //
    // Every field is emptied by reset(), which is why generate() is the only public way to get
    // a document: a second call on the same instance starts clean rather than appending to the
    // first document's leftovers, and the class never has to be defensive about being reused.
    // -------------------------------------------------------------------------------------

    private val nodes: MutableList<Node> = mutableListOf()
    private val componentUsage: MutableMap<ComponentType, Int> = linkedMapOf()
    private val slotShapes: MutableList<SlotShape> = mutableListOf()
    private val filledSlots: MutableList<FilledSlot> = mutableListOf()
    private val modifierUsage: MutableList<ModifierType> = mutableListOf()
    private val actionUsage: MutableList<ActionId> = mutableListOf()
    private val branchedUsage: MutableList<ActionId> = mutableListOf()
    private val hostUsage: MutableList<String> = mutableListOf()
    private val navigationUsage: MutableList<PageId> = mutableListOf()
    private val declaredComponents: MutableList<ComponentDeclId> = mutableListOf()
    private val instantiatedComponents: MutableList<ComponentDeclId> = mutableListOf()
    private val derivedUsage: MutableList<StateId> = mutableListOf()

    private var computedProperties: Int = 0
    private var handlerCount: Int = 0
    private var stateCount: Int = 0

    /** The document's declared models, keyed by the id a `TypeRef.Object` names. */
    private var declaredModels: Map<DataModelId, DataModelDecl> = emptyMap()

    /** The document's declared enums, keyed by the id a `TypeRef.Enum` names. */
    private var declaredEnums: Map<TypeId, EnumTypeDecl> = emptyMap()

    /** The document's `host.call` callees. Never empty, so the action is always reachable. */
    private var hosts: List<HostFunctionDecl> = emptyList()

    /** The pages a `nav.navigate` may name: the ones with no route arguments at all. */
    private var parameterlessPages: List<PagePlan> = emptyList()

    private var pagePlans: List<PagePlan> = emptyList()
    private var componentPlans: List<ComponentPlan> = emptyList()
    private var appStates: List<StateDecl> = emptyList()
    private var tokenVocabulary: List<String> = MATERIAL_TOKENS

    /**
     * One document, and what the generator meant by it.
     *
     * The plan is not a summary of [GeneratedDocument.document]: it records the decisions taken
     * while building it, and the coverage test checks the two against each other rather than
     * against anything written out by hand. See [GenerationPlan] for why that matters here more
     * than anywhere else in the repository's test code.
     */
    public fun generate(): GeneratedDocument {
        // The seed is re-applied on every call, so `generate()` is a pure function of it and a
        // second call on the same instance produces the same document as the first. The obvious
        // alternative — one stream for the lifetime of the generator — makes the second document
        // a function of how many documents came before it, which is exactly the property §36
        // asks a property test to have and exactly the one a shared generator in a loop would
        // quietly break. `Random` is a `var` for that reason and for no other.
        random = Random(seed.toInt())
        mint = IdMint(random)
        reset()

        val mode = pickThemeMode()
        val themes = buildThemes(mode)
        val selection = if (mode == ThemeSelection.Selected) themes.keys.first() else null
        tokenVocabulary = tokenNamesFor(themes, selection)

        appStates = buildAppState()
        stateCount += appStates.size
        declaredModels = mapOf(PROBE_MODEL_DATA.id to PROBE_MODEL_DATA)
        declaredEnums = mapOf(PROBE_GRADE_DATA.id to PROBE_GRADE_DATA)
        hosts = buildHostFunctions()
        pagePlans = buildPagePlans()
        parameterlessPages = pagePlans.filter { it.params.isEmpty() }
        componentPlans = buildComponentPlans()

        val pages = linkedMapOf<PageId, Page>()
        for (plan in pagePlans) {
            val root = pageRoot(Scope(appStates, plan.state, plan.params), instancesFor(plan))
            pages[plan.id] = Page(
                id = plan.id,
                name = plan.name,
                route = plan.route,
                params = plan.params,
                state = plan.state,
                root = root,
            )
        }

        val components = linkedMapOf<ComponentDeclId, ComponentDecl>()
        for (plan in componentPlans) {
            val root = componentRoot(Scope(appStates, plan.state, plan.params), plan.slots.map { it.name })
            components[plan.id] = ComponentDecl(
                id = plan.id,
                name = plan.name,
                params = plan.params,
                slots = plan.slots,
                state = plan.state,
                root = root,
            )
        }

        var table = NodeTable.EMPTY
        for (node in nodes) table = table.with(node)

        val document = UiDocument(
            meta = DocumentMeta(name = "generated", componentVersions = recordedVersions()),
            app = AppSpec(packageName = "com.example.generated", startPage = pagePlans.first().id),
            pages = pages,
            components = components,
            nodes = table,
            appState = appStates,
            dataModels = declaredModels,
            enums = declaredEnums,
            hostFunctions = hosts,
            themes = themes,
            theme = selection,
        )

        return GeneratedDocument(seed, document, planFor(mode))
    }

    private fun reset() {
        nodes.clear()
        componentUsage.clear()
        slotShapes.clear()
        filledSlots.clear()
        modifierUsage.clear()
        actionUsage.clear()
        branchedUsage.clear()
        hostUsage.clear()
        navigationUsage.clear()
        declaredComponents.clear()
        instantiatedComponents.clear()
        derivedUsage.clear()
        computedProperties = 0
        handlerCount = 0
        stateCount = 0
        declaredModels = emptyMap()
        declaredEnums = emptyMap()
        hosts = emptyList()
        parameterlessPages = emptyList()
        pagePlans = emptyList()
        componentPlans = emptyList()
        appStates = emptyList()
        tokenVocabulary = MATERIAL_TOKENS
    }

    // -------------------------------------------------------------------------------------
    // The document's declarations
    // -------------------------------------------------------------------------------------

    /**
     * Four app-scoped declarations, one of them derived.
     *
     * The derived one exists so §12.3's write rule has something to be right about: pass 6
     * refuses `state.set` against a computed declaration as `action.state_not_writable`, and a
     * generator that only ever declared held state would never produce a document where that
     * rule has to be obeyed by *omission*. Its body reads `appCount` because §12.1 puts the app
     * store at the root, above every page's and every component's — which is also the only store
     * a derived *app* declaration can read.
     */
    private fun buildAppState(): List<StateDecl> {
        val count = heldState("appCount", TypeRef.Int32, Value.Int32(smallInt()))
        return listOf(
            heldState("appText", TypeRef.Str, Value.Str("gen")),
            count,
            heldState("appFlag", TypeRef.Bool, Value.Bool(random.nextBoolean())),
            derivedState(
                label = "appTotal",
                declared = TypeRef.Int32,
                body = Expr.Binary(
                    op = BinaryOp.Add,
                    left = Expr.Ref(RefTarget.State(count.id)),
                    right = Expr.Const(Value.Int32(smallInt())),
                ),
            ),
        )
    }

    /** Two or three screens, the first of which never takes a route argument. */
    private fun buildPagePlans(): List<PagePlan> {
        val count = 2 + random.nextInt(2)
        val plans = mutableListOf<PagePlan>()
        for (index in 0 until count) {
            val params = if (index == 0 || random.nextInt(2) == 0) {
                emptyList()
            } else {
                listOf(ParamDecl(ParamName("pageParam$index"), TypeRef.Str))
            }
            val state = listOf(
                heldState("pageText$index", TypeRef.Str, Value.Str("gen")),
                heldState("pageCount$index", TypeRef.Int32, Value.Int32(smallInt())),
            )
            stateCount += state.size
            plans += PagePlan(
                id = mint.page(),
                name = "GenScreen$index",
                route = "/generated/$index",
                params = params,
                state = state,
            )
        }
        return plans
    }

    /**
     * Zero to two document-defined components.
     *
     * Zero is in the range on purpose. §8.4's overlay is built by `Analyzer` only when
     * `document.components` is not empty, so a document with no declaration is analysed against
     * the static schema and one with a declaration against the overlay — and a generator that
     * always declared one would never produce the first kind, which is the kind a W2 permutation
     * test has to survive.
     */
    private fun buildComponentPlans(): List<ComponentPlan> {
        val plans = mutableListOf<ComponentPlan>()
        for (index in 0 until random.nextInt(3)) {
            val slots = mutableListOf(SlotDecl(SlotName("content")))
            if (random.nextInt(2) == 0) slots += SlotDecl(SlotName("actions"))
            val state = listOf(heldState("compText$index", TypeRef.Str, Value.Str("gen")))
            stateCount += state.size
            val plan = ComponentPlan(
                id = mint.componentDecl(),
                name = "GenWidget$index",
                // Every parameter required: `SchemaOverlay.documentComponentSpec` copies the
                // flag, so a `doc.*` node missing an optional parameter would still be valid and
                // one missing a required parameter is `prop.required_missing`. Making them all
                // required is what guarantees every instance below is written completely.
                params = List(random.nextInt(3)) { position ->
                    ParamDecl(
                        name = ParamName("compParam$position"),
                        type = listOf(TypeRef.Str, TypeRef.Int32, TypeRef.Bool)[random.nextInt(3)],
                        required = true,
                    )
                },
                slots = slots,
                state = state,
            )
            plans += plan
            declaredComponents += plan.id
        }
        return plans
    }

    /**
     * One or two host functions, every parameter a `Str`.
     *
     * All `Str` because `ActionPass.checkPositional` typechecks each argument against
     * `HostFunctionDecl.params[position].type`, and a generated argument of the wrong variant is
     * `prop.type_mismatch` on a step that is otherwise entirely legal. `returns` is written
     * explicitly because §11.6 gave the field no default and `null` is the honest answer for a
     * function that answers nothing.
     */
    private fun buildHostFunctions(): List<HostFunctionDecl> {
        val built = mutableListOf<HostFunctionDecl>()
        for (index in 0 until 1 + random.nextInt(2)) {
            val params = if (random.nextInt(2) == 0) {
                emptyList()
            } else {
                listOf(
                    ParamDecl(ParamName("first"), TypeRef.Str),
                    ParamDecl(ParamName("second"), TypeRef.Str),
                )
            }
            built += HostFunctionDecl(
                name = "genHost$index",
                params = params,
                returns = if (random.nextInt(2) == 0) null else TypeRef.Str,
                suspend = random.nextInt(2) == 0,
            )
        }
        return built
    }

    /** The themes for [mode]; nothing at all for [ThemeSelection.None]. */
    private fun buildThemes(mode: ThemeSelection): Map<ThemeId, ThemeDecl> {
        if (mode == ThemeSelection.None) return emptyMap()
        val first = theme(0)
        val themes = linkedMapOf<ThemeId, ThemeDecl>(first.id to first)
        // A second theme only under a selection. §14.2 resolves a selection by id, so an
        // unselected *pair* resolves against Material's base and would turn every custom token in
        // the document into `token.unknown`; declaring the second one together with the
        // selection is what keeps the declared-but-unselected case reachable through
        // `ThemeSelection.Declared` without making it invalid.
        if (mode == ThemeSelection.Selected && random.nextInt(2) == 0) {
            val second = theme(1)
            themes[second.id] = second
        }
        return themes
    }

    /**
     * One theme, with a role in each of the five maps `ReferencePass` and `themeKnows` look at.
     *
     * The five maps are the point rather than the colours. `themeKnows` answers a token question
     * by checking `colors`, `typography`, `shapes`, `dimensions` and `custom` in turn, so a theme
     * carrying one colour and nothing else produces documents whose only legal token is that
     * colour — and the generator reads its vocabulary back out of the theme, so the other four
     * arms would never be reached. One role in each is the smallest theme that keeps all five
     * reachable.
     */
    private fun theme(index: Int): ThemeDecl {
        val typography: Map<TextRole, Map<PropertyKey, Value>> = mapOf(
            TextRole("gen.title") to mapOf(
                PropertyKey("size") to Value.Sp(smallUnit()),
                PropertyKey("label") to Value.Str("gen"),
            ),
        )
        val shapes: Map<ShapeRole, Map<PropertyKey, Value>> = mapOf(
            ShapeRole("gen.round") to mapOf(PropertyKey("radius") to Value.Dp(smallUnit())),
        )
        val dimensions: Map<TokenName, Value.Dp> = mapOf(TokenName("gen.gap") to Value.Dp(smallUnit()))
        val custom: Map<TokenName, Value> = mapOf(TokenName("gen.accent") to Value.Str("gen"))
        val colors: Map<ColorRole, ColorSpec> = mapOf(
            ColorRole("gen.primary") to ColorSpec(
                light = ColorArgb.of(255, smallInt(256), smallInt(256), smallInt(256)),
                dark = ColorArgb.of(255, smallInt(256), smallInt(256), smallInt(256)),
            ),
        )
        return ThemeDecl(
            id = mint.theme(),
            name = "Generated theme $index",
            colors = colors,
            typography = typography,
            shapes = shapes,
            dimensions = dimensions,
            custom = custom,
        )
    }

    /**
     * Every token name a document of this shape may legally write.
     *
     * §14.2 has two rules and they are not the same rule, which is why this reads the theme
     * rather than returning a constant: with nothing selected, `ReferencePass` accepts any name
     * starting `md.`; with a theme resolved, it accepts only a name that theme declares. A
     * generator using one vocabulary for both would produce documents that are valid under one
     * rule and not the other, half the time.
     */
    private fun tokenNamesFor(themes: Map<ThemeId, ThemeDecl>, selection: ThemeId?): List<String> {
        if (selection != null) return namesDeclaredBy(themes.getValue(selection))
        if (themes.size == 1) return namesDeclaredBy(themes.values.first())
        return MATERIAL_TOKENS
    }

    private fun namesDeclaredBy(theme: ThemeDecl): List<String> =
        theme.colors.keys.map { it.value } +
            theme.typography.keys.map { it.value } +
            theme.shapes.keys.map { it.value } +
            theme.dimensions.keys.map { it.value } +
            theme.custom.keys.map { it.value }

    // -------------------------------------------------------------------------------------
    // The node table
    // -------------------------------------------------------------------------------------

    /**
     * A page's root: a `core.Column`, with the `doc.*` instances hung into its `children`.
     *
     * Every root is a `Column`, in pages and in component bodies alike, and it is worth saying
     * why rather than leaving it as a simplification. `Column.children` is `Many` with
     * `ChildFilter.Any`, so it is the one slot in this schema that will take a `doc.*` node of any
     * shape at all — and a document component has to be *instantiated somewhere*, which means a
     * slot that admits it. Choosing the root by draw would mean asking, for every root, whether
     * the component that won has a slot a `doc.*` node may go in.
     *
     * The variety a reader is after is in the subtrees, which is where every other registered
     * component type is reached anyway.
     */
    private fun pageRoot(scope: Scope, instances: List<NodeId>): NodeId {
        val spec = specOf(COLUMN)
        val slots = linkedMapOf<SlotName, List<NodeId>>()
        for (declared in spec.slots) {
            val children = mutableListOf<NodeId>()
            fillSlot(declared, scope, MAX_DEPTH, children, instances)
            slots[declared.name] = children
        }
        return emit(COLUMN, scope, emptySet(), slots, buildProps(spec, scope, slots.keys), atRoot = true)
    }

    /** A component body: a `core.Column` with one `core.SlotOutlet` per declared slot. */
    private fun componentRoot(scope: Scope, slotNames: List<SlotName>): NodeId {
        val spec = specOf(COLUMN)
        val slots = linkedMapOf<SlotName, List<NodeId>>()
        for (declared in spec.slots) {
            val children = mutableListOf<NodeId>()
            fillSlot(declared, scope, MAX_DEPTH, children)
            children += outlet(scope, slotNames)
            slots[declared.name] = children
        }
        return emit(COLUMN, scope, emptySet(), slots, buildProps(spec, scope, slots.keys), atRoot = true)
    }

    /** One `core.SlotOutlet` naming one of the declaration's slots. */
    private fun outlet(scope: Scope, slotNames: List<SlotName>): NodeId {
        // A guard, not decoration: a node of a type no schema registers is `component.unknown`,
        // and a generator that emitted one quietly would turn a schema mistake into a validity
        // failure attributed to the document rather than to the schema.
        specOf(SLOT_OUTLET)
        val name = slotNames[random.nextInt(slotNames.size)]
        val props: Map<PropertyKey, PropertyValue> = mapOf(
            PropertyKey("slot") to PropertyValue.Const(Value.Str(name.value)),
        )
        return emit(SLOT_OUTLET, scope, emptySet(), emptyMap(), props)
    }

    /**
     * The `doc.*` instances this page carries, some of the time.
     *
     * §8.4's overlay is what makes such a node resolvable at all: `Analyzer` builds
     * `SchemaOverlay(schema, document)` whenever `document.components` is not empty, and the
     * synthesized spec is what `SchemaPass` then validates the instance against. Phase 9 found
     * the same thing from the other side — a `DropTargets` check could not resolve a `doc.*`
     * parent through the static schema — so the validity test analyses through `Analyzer`, which
     * is the one path that overlays.
     *
     * The scope is the *page's*, not the declaration's: §5.6 puts an instance's children in the
     * page's table, so `DocumentIndex` reports the page as their owner and that is the scope
     * they resolve against.
     */
    private fun instancesFor(plan: PagePlan): List<NodeId> {
        if (componentPlans.isEmpty()) return emptyList()
        val scope = Scope(appStates, plan.state, plan.params)
        val built = mutableListOf<NodeId>()
        for (decl in componentPlans) {
            if (random.nextInt(100) >= INSTANCE_PERCENT) continue
            instantiatedComponents += decl.id
            built += instance(decl, scope)
        }
        return built
    }

    private fun instance(decl: ComponentPlan, scope: Scope): NodeId {
        val type = ComponentType(DOCUMENT_PREFIX + decl.id.value)
        // The one place the `doc.` namespace is spelled twice in this repository. `SchemaOverlay`
        // keeps its copy private, so the drift check available from here is the inverse
        // function: if `documentDeclIdOf` cannot read this type back to the declaration it came
        // from, the overlay will never find a spec for it and the instance is `component.unknown`.
        check(documentDeclIdOf(type.value) == decl.id) {
            "'$type' does not read back to declaration '${decl.id}'; the doc. namespace has drifted"
        }
        val props = linkedMapOf<PropertyKey, PropertyValue>()
        for (param in decl.params) {
            val value = constantFor(param.type, scope)
                ?: error("Component '${decl.id}' declares a parameter this generator cannot write")
            props[PropertyKey(param.name.value)] = PropertyValue.Const(value)
        }

        // The overlay synthesizes every document slot as `Cardinality.Many` with no filter
        // (`SchemaOverlay.documentComponentSpec`), so the shapes recorded here are read off that
        // fact rather than off a second `SlotSpec` a test could disagree with.
        val declared: List<SlotSpec> = decl.slots.map { SlotSpec(it.name, Cardinality.Many) }
        val slots = linkedMapOf<SlotName, List<NodeId>>()
        for (slot in declared) {
            val children = mutableListOf<NodeId>()
            if (random.nextInt(100) < FILLED_INSTANCE_PERCENT) {
                repeat(random.nextInt(3)) {
                    val child = pickChild(ChildFilter.Any, MAX_DEPTH - 2)
                    children += buildNode(child.type, scope, MAX_DEPTH - 3, emptySet())
                }
            }
            slots[slot.name] = children
        }

        return emit(type, scope, emptySet(), slots, props, declared)
    }

    /**
     * One node and everything below it, recursively.
     *
     * Depth is the only bound, and it is a bound rather than a budget: at [MAX_DEPTH] every child
     * is a leaf, so the table is a few dozen nodes and the recursion terminates whatever the
     * registry holds.
     */
    private fun buildNode(type: ComponentType, scope: Scope, depth: Int, available: Set<ScopeId>): NodeId {
        val spec = specOf(type)
        val slots = linkedMapOf<SlotName, List<NodeId>>()
        for (declared in spec.slots) {
            val children = mutableListOf<NodeId>()
            fillSlot(declared, scope, depth, children)
            slots[declared.name] = children
        }
        return emit(type, scope, available, slots, buildProps(spec, scope, slots.keys))
    }

    /**
     * Fills [children] from [declared], then appends [extra].
     *
     * The cardinality decides how many and [pickChild] decides what, and the filter is consulted
     * before a child is chosen rather than after one is built: a generator that built a child and
     * then asked whether the slot would take it would have to throw the node away, and a node in
     * the table that no root reaches is `struct.orphan`.
     */
    private fun fillSlot(
        declared: SlotSpec,
        scope: Scope,
        depth: Int,
        children: MutableList<NodeId>,
        extra: List<NodeId> = emptyList(),
    ) {
        repeat(childCount(declared.cardinality)) {
            val child = pickChild(declared.accepts, depth)
            children += buildNode(child.type, scope, depth - 1, declared.provides)
        }
        children += extra
    }

    /**
     * A child the slot admits.
     *
     * Two pools, and the choice between them is the depth rule rather than a policy: at the
     * bottom of the tree the child is a leaf, so the subtree cannot grow another level and the
     * termination argument does not depend on the registry happening to hold no containers. If
     * the admissible set has no leaf at all the deeper pool is used anyway, because a tree the
     * depth rule flattened is more useful to a property test than no tree.
     */
    private fun pickChild(filter: ChildFilter, depth: Int): ComponentSpec {
        val admissible = components.filter { admits(filter, it.type) }
        if (admissible.isEmpty()) error("No registered component satisfies the filter '$filter'")
        val leaves = admissible.filter { it.slots.isEmpty() }
        val pool = if (depth <= 0 && leaves.isNotEmpty()) leaves else admissible
        return pool[random.nextInt(pool.size)]
    }

    private fun admits(filter: ChildFilter, child: ComponentType): Boolean = when (filter) {
        is ChildFilter.Any -> true
        is ChildFilter.Only -> child in filter.types
        is ChildFilter.Except -> child !in filter.types
    }

    private fun childCount(cardinality: Cardinality): Int = when (cardinality) {
        Cardinality.ExactlyOne -> 1
        Cardinality.ZeroOrOne -> random.nextInt(2)
        Cardinality.Many -> random.nextInt(3)
    }

    /**
     * Appends the node and records what the plan has to say about it, then returns its id.
     *
     * Every declared slot is written, empty or not, and that is a deliberate choice over writing
     * only the populated ones: [FilledSlot.children] is the assertion the awkward shapes need,
     * and a slot the generator omitted from the node entirely has no count to assert against.
     * An empty `"children": []` on a `Column` is a few bytes that make the plan a complete
     * declaration of what was written rather than a summary of the parts somebody remembered to
     * record.
     *
     * [extraShapes] exists for one caller: a `doc.*` instance has no static `ComponentSpec`, so
     * the shapes it was written against are handed in from the synthesis rules rather than read.
     *
     * [atRoot] names the two roots and nothing else: a node with no ancestor slot above it has no
     * scope for a spec's `emit.scopeEntries` to be read through.
     */
    private fun emit(
        type: ComponentType,
        scope: Scope,
        available: Set<ScopeId>,
        slots: Map<SlotName, List<NodeId>>,
        props: Map<PropertyKey, PropertyValue>,
        extraShapes: List<SlotSpec> = emptyList(),
        atRoot: Boolean = false,
    ): NodeId {
        val spec = view.components[type]
        for (declared in spec?.slots.orEmpty() + extraShapes) recordShape(type, declared)

        val id = mint.node()
        for (entry in slots) {
            filledSlots += FilledSlot(node = id, owner = type, slot = entry.key, children = entry.value.size)
        }

        nodes += Node(
            id = id,
            type = type,
            name = if (random.nextInt(3) == 0) "gen-" + smallInt(100000) else null,
            props = props,
            modifiers = buildModifiers(available, atRoot),
            slots = slots,
            events = buildEvents(spec, scope),
        )
        componentUsage[type] = (componentUsage[type] ?: 0) + 1
        return id
    }

    private fun specOf(type: ComponentType): ComponentSpec =
        view.components[type] ?: error("Component '$type' is not in the schema this generator reads")

    private fun recordShape(owner: ComponentType, declared: SlotSpec) {
        val shape = SlotShape(owner, declared.name, declared.cardinality, declared.accepts)
        if (shape !in slotShapes) slotShapes += shape
    }

    // -------------------------------------------------------------------------------------
    // Properties
    // -------------------------------------------------------------------------------------

    /**
     * The properties one node carries, and the rules it must not break.
     *
     * The order below is the order the rules need rather than an arbitrary sequence: what a spec
     * *requires* first, then what its own rules require, then what a draw would like, and the
     * exclusivity last because it removes keys the earlier steps added. A `Column` is the case
     * that makes the ordering matter — `spacing` and `verticalArrangement` are mutually exclusive,
     * both optional, and writing both is `component.conflicting_properties`, which is the one
     * diagnostic a document can pick up from a generator that did nothing wrong locally.
     */
    private fun buildProps(
        spec: ComponentSpec,
        scope: Scope,
        filled: Set<SlotName>,
    ): Map<PropertyKey, PropertyValue> {
        val declared: Map<PropertyKey, PropertySpec<*>> = spec.properties.associateBy { it.key }
        val bounds = rangesOf(spec.rules)
        val wanted: MutableSet<PropertyKey> = mutableSetOf()

        for (property in spec.properties) if (property.required) wanted += property.key

        for (rule in spec.rules) {
            if (rule is PropertyRule.RequiresOneOf) takeOneOf(rule.keys, declared, wanted)
            if (rule is PropertyRule.SlotRequires && rule.slot in filled) {
                takeOneOf(rule.keys, declared, wanted)
            }
        }

        for (property in spec.properties) {
            if (property.required || property.key in wanted) continue
            if (random.nextInt(100) < OPTIONAL_PROPERTY_PERCENT) wanted += property.key
        }

        for (rule in spec.rules) {
            if (rule !is PropertyRule.MutuallyExclusive) continue
            val present = rule.keys.filter { it in wanted }.sortedBy { it.value }
            if (present.size <= 1) continue
            val keeper = present[random.nextInt(present.size)]
            for (key in present) if (key != keeper) wanted -= key
        }

        val props = linkedMapOf<PropertyKey, PropertyValue>()
        for (key in wanted) {
            val value = propertyValueFor(declared.getValue(key), scope, bounds[key])
            if (value != null) props[key] = value
        }
        return props
    }

    private fun takeOneOf(
        keys: Set<PropertyKey>,
        declared: Map<PropertyKey, PropertySpec<*>>,
        wanted: MutableSet<PropertyKey>,
    ) {
        val options = keys.filter { it in declared }
        if (options.isEmpty()) return
        wanted += options[random.nextInt(options.size)]
    }

    private fun rangesOf(rules: List<PropertyRule>): Map<PropertyKey, NumberBounds> {
        val bounds = linkedMapOf<PropertyKey, NumberBounds>()
        for (rule in rules) {
            if (rule is PropertyRule.Range) bounds[rule.key] = NumberBounds(rule.min, rule.max)
        }
        return bounds
    }

    /**
     * One property's value, a constant or an expression.
     *
     * An expression is only ever written for a **required** property, and only for the four
     * types [computedFor] knows how to produce. The restriction earns its keep: a computed value
     * is pass 5's to check against the declared type, and each of those four is one it can check
     * against the same tree. A property typed `TypeRef.Object` or `TypeRef.Icon` has no
     * expression form the checker settles without a registry, and a generator that guessed would
     * produce `expr.type_mismatch` on a shape it believed was fine.
     */
    private fun propertyValueFor(
        property: PropertySpec<*>,
        scope: Scope,
        bounds: NumberBounds?,
    ): PropertyValue? {
        if (property.required && random.nextInt(100) < COMPUTED_PROPERTY_PERCENT) {
            val computed = computedFor(property.type, scope)
            if (computed != null) {
                computedProperties += 1
                return computed
            }
        }
        val constant = constantFor(property.type, scope, bounds) ?: return null
        return PropertyValue.Const(constant)
    }

    /**
     * A constant that fits [declared], or `null` when this generator writes none.
     *
     * `null` means "leave the property out" and never "write something wrong". It is reachable
     * for `TypeRef.Dimension`, which `SchemaPass.acceptsValue` refuses every value on by name,
     * and for a `TypeRef.Ref(RefKind.Resource)`, which this module never declares — and both are
     * cases where a placeholder would produce `prop.type_mismatch` or `ref.dangling` instead.
     */
    private fun constantFor(
        declared: TypeRef,
        scope: Scope,
        bounds: NumberBounds? = null,
    ): Value? = when (declared) {
        TypeRef.Bool -> Value.Bool(random.nextBoolean())
        TypeRef.Int32 -> Value.Int32(bounded(bounds, smallInt().toDouble()).toInt())
        TypeRef.Int64 -> Value.Int64(bounded(bounds, smallInt().toDouble()).toLong())
        TypeRef.Float32 -> Value.Float32(bounded(bounds, smallUnit().toDouble()).toFloat())
        TypeRef.Float64 -> Value.Float64(bounded(bounds, smallUnit().toDouble()))
        TypeRef.Str -> Value.Str("gen-" + smallInt(100000))
        TypeRef.Url -> Value.Url("https://example.com/gen/" + smallInt(100000))
        TypeRef.Color -> Value.Color(ColorArgb.of(255, smallInt(256), smallInt(256), smallInt(256)))
        TypeRef.Dp -> Value.Dp(bounded(bounds, smallUnit().toDouble()).toFloat())
        TypeRef.Sp -> Value.Sp(bounded(bounds, smallUnit().toDouble()).toFloat())
        // `SchemaPass.acceptsValue` refuses every value on a `Dimension`, so leaving the property
        // out is the only correct answer here.
        TypeRef.Dimension -> null
        is TypeRef.Nullable ->
            if (random.nextInt(4) == 0) Value.Null else constantFor(declared.inner, scope, bounds)
        is TypeRef.Enum -> enumValueFor(declared.id)
        is TypeRef.Ref -> referenceFor(declared.kind)
        is TypeRef.Token ->
            Value.Token(declared.kind, tokenVocabulary[random.nextInt(tokenVocabulary.size)])
        is TypeRef.Icon -> Value.Icon(declared.set ?: PROBE_ICON_SET, "gen-icon-" + smallInt(16))
        is TypeRef.ListOf -> listValueFor(declared.element, scope, bounds)
        is TypeRef.MapOf -> mapValueFor(declared.value, scope, bounds)
        is TypeRef.Object -> objectValueFor(declared.id, scope, bounds)
    }

    private fun bounded(bounds: NumberBounds?, value: Double): Double = bounds?.clamp(value) ?: value

    /**
     * An enum entry the schema's or the document's enum declares.
     *
     * `SchemaPass.enumEntries` reads the schema's `EnumTypeSpec` first and falls back to
     * `document.enums` only for a `TypeId` the schema declared nothing for. `gen.Probe.grade` is
     * the second case and `Alignment`, `Shape` and the two arrangements are the first, which is
     * why both arms are here rather than one.
     */
    private fun enumValueFor(id: TypeId): Value? {
        val entries = (view.types[id] as? EnumTypeSpec)?.entries?.map { it.name }
            ?: declaredEnums[id]?.entries?.map { it.name }
            ?: return null
        if (entries.isEmpty()) return null
        return Value.Enum(entries[random.nextInt(entries.size)])
    }

    /**
     * A reference to something this document actually declares.
     *
     * Every arm that can name a live target does, because `ReferencePass.checkRef` compares the
     * bare string against the document's four maps and reports `ref.dangling` for anything
     * missing. The resource arm answers `null` rather than a fabricated id: this module declares
     * no `ResourceDecl`, so the property is left out entirely, which is a smaller lie than a
     * reference to a resource nobody wrote.
     */
    private fun referenceFor(kind: RefKind): Value? = when (kind) {
        RefKind.Page -> pagePlans.firstOrNull()?.let { Value.Ref(kind, it.id.value) }
        RefKind.Component -> componentPlans.firstOrNull()?.let { Value.Ref(kind, it.id.value) }
        RefKind.DataModel -> Value.Ref(kind, PROBE_MODEL_DATA.id.value)
        RefKind.Resource -> null
    }

    private fun listValueFor(element: TypeRef, scope: Scope, bounds: NumberBounds?): Value? {
        val items = mutableListOf<Value>()
        repeat(random.nextInt(3)) {
            items += (constantFor(element, scope, bounds) ?: return null)
        }
        return Value.ListOf(items)
    }

    private fun mapValueFor(value: TypeRef, scope: Scope, bounds: NumberBounds?): Value? {
        val entries = mutableMapOf<PropertyKey, Value>()
        repeat(random.nextInt(3)) { position ->
            entries[PropertyKey("entry$position")] =
                (constantFor(value, scope, bounds) ?: return null)
        }
        return Value.MapOf(Value.Str("gen-map"), entries)
    }

    /**
     * A `Value.Obj` over a declared data model.
     *
     * `ReferencePass.checkObj` accepts an object type the document declares as a model or an
     * enum, and the comparison is on `TypeId.value` because the two maps are one value space
     * (§5.5), so the model is found by its id's string whatever type the map was keyed with.
     */
    private fun objectValueFor(id: TypeId, scope: Scope, bounds: NumberBounds?): Value? {
        val model = declaredModels.entries.firstOrNull { it.key.value == id.value }?.value ?: return null
        val fields = mutableMapOf<PropertyKey, Value>()
        for (field in model.fields) {
            fields[field.name] = constantFor(field.type, scope, bounds) ?: return null
        }
        return Value.Obj(id, fields)
    }

    // -------------------------------------------------------------------------------------
    // Expressions
    // -------------------------------------------------------------------------------------

    /**
     * An expression of the declared type, or `null` when this generator writes none for it.
     *
     * Every shape below is one §10.4 settles outright, and the reasons are at the branches:
     * `+` over two `i32` answers `i32` because `OperatorRules.SAME_NUMERIC` demands the operands
     * agree and `Assignability` never widens; `str.trim` answers `Str` because that is its
     * signature; a template is `Str` because §10.4 admits exactly `str`, `i32`, `i64` and `bool`
     * as parts and every part written here is a `Str`.
     */
    private fun computedFor(declared: TypeRef, scope: Scope): PropertyValue? = when (declared) {
        TypeRef.Str -> PropertyValue.Computed(strExpression(scope, 2))
        TypeRef.Int32 -> PropertyValue.Computed(intExpression(scope, 2))
        TypeRef.Bool -> PropertyValue.Computed(boolExpression(scope, 2, null))
        is TypeRef.ListOf -> if (declared.element == TypeRef.Str) {
            PropertyValue.Computed(
                Expr.ListLiteral(List(random.nextInt(3)) { strExpression(scope, 1) }),
            )
        } else {
            null
        }
        else -> null
    }

    /**
     * A `Str` expression.
     *
     * Reads a `Str` state whenever the scope has one, because a property depending on nothing in
     * the document is a weaker document than one that reads it, and §17.1's `expr.unresolved_ref`
     * is a diagnostic this generator can only produce on purpose. The `if` arm is there because
     * §10.1 counts conditionals among the nine variants: both branches answer `Str`, so the
     * checker settles it without a schema.
     */
    private fun strExpression(scope: Scope, depth: Int): Expr {
        val declared = scope.stateOf(TypeRef.Str)
        val base: Expr = declared
            ?.let { Expr.Ref(RefTarget.State(it.id)) }
            ?: Expr.Const(Value.Str("gen-" + smallInt(100000)))
        if (depth <= 0) return base
        return when (random.nextInt(4)) {
            0 -> base
            1 -> Expr.Call(STR_TRIM, listOf(base))
            2 -> Expr.Template(listOf(Expr.Const(Value.Str("[")), base, Expr.Const(Value.Str("]"))))
            else -> Expr.If(
                cond = Expr.Const(Value.Bool(random.nextBoolean())),
                then = base,
                otherwise = Expr.Const(Value.Str("-")),
            )
        }
    }

    /** An `i32` expression: a state read, a sum, or a `str.length` over a `Str`. */
    private fun intExpression(scope: Scope, depth: Int): Expr {
        val declared = scope.stateOf(TypeRef.Int32)
        val base: Expr = declared
            ?.let { Expr.Ref(RefTarget.State(it.id)) }
            ?: Expr.Const(Value.Int32(smallInt()))
        if (depth <= 0) return base
        return when (random.nextInt(3)) {
            0 -> base
            1 -> Expr.Binary(BinaryOp.Add, base, Expr.Const(Value.Int32(smallInt())))
            else -> Expr.Call(STR_LENGTH, listOf(strExpression(scope, depth - 1)))
        }
    }

    /**
     * A `Bool` expression.
     *
     * [event] is `null` everywhere a property is written and non-null inside a handler, and the
     * difference is load-bearing rather than a convenience: `ExprScope.eventArgs` is filled by
     * pass 6 alone (`AnalysisPass.scopeOf` defaults it to empty), so a property reading an event
     * argument would be `expr.unresolved_ref` on every document that carried it. The read is
     * therefore guarded on the event actually declaring one, and on its type being `Str` — the
     * only type the `Eq` arm below settles without a conversion.
     */
    private fun boolExpression(scope: Scope, depth: Int, event: EventSpec?): Expr {
        val declared = scope.stateOf(TypeRef.Bool)
        val base: Expr = declared
            ?.let { Expr.Ref(RefTarget.State(it.id)) }
            ?: Expr.Const(Value.Bool(random.nextBoolean()))
        if (depth <= 0) return base
        val argument = event?.args?.firstOrNull { it.type == TypeRef.Str }
        return when {
            argument != null && random.nextInt(2) == 0 -> Expr.Binary(
                op = BinaryOp.Eq,
                left = Expr.Ref(RefTarget.EventArg(argument.name)),
                right = Expr.Const(Value.Str("gen")),
            )

            random.nextInt(4) == 0 -> base
            random.nextInt(3) == 0 -> Expr.Unary(UnaryOp.Not, base)
            random.nextInt(2) == 0 -> Expr.Call(STR_IS_BLANK, listOf(Expr.Const(Value.Str(" "))))
            else -> base
        }
    }

    // -------------------------------------------------------------------------------------
    // Modifiers
    // -------------------------------------------------------------------------------------

    /**
     * The modifier chain, chosen from what [available] allows.
     *
     * [available] is the scope set of the slot this node was placed in rather than the full set
     * `ScopeAnalysis` computes for it, and that is a deliberate under-approximation: the
     * analysis unions the `provides` of every ancestor slot, so the immediate slot's contribution
     * is always a subset of what the node will end up with, and a modifier applied against it can
     * never be `modifier.scope_missing`. Losing the outer scopes costs coverage of modifiers
     * nested three levels down and buys a chain that is correct by construction at every depth.
     *
     * [atRoot] closes a second, independent gate: `SchemaPass` enforces only `requiresScope`,
     * while `KotlinGenerator.emitModifier` refuses any spec with a non-empty `emit.scopeEntries`
     * outside a scope it declares, and a root has no ancestor to open one. Gating on
     * `available.isEmpty()` instead would delete live coverage, because an outlet and an instance
     * also pass an empty set and both sit inside the root Column's `children`.
     */
    private fun buildModifiers(available: Set<ScopeId>, atRoot: Boolean): List<ModifierEntry> {
        val usable = modifierSpecs.filter { spec ->
            spec.requiresScope.all { it in available } && (!atRoot || spec.emit.scopeEntries.isEmpty())
        }
        if (usable.isEmpty() || random.nextInt(100) >= MODIFIER_PERCENT) return emptyList()
        val entries = mutableListOf<ModifierEntry>()
        repeat(1 + random.nextInt(2)) {
            val spec = usable[random.nextInt(usable.size)]
            modifierUsage += spec.type
            entries += ModifierEntry(spec.type, buildArgs(spec))
        }
        return entries
    }

    /**
     * One modifier's arguments.
     *
     * Only required parameters are guaranteed, and that is the whole of what
     * `SchemaPass.checkModifierArgs` insists on beyond the keys being declared. The optional
     * ones — `layout.padding`'s three shapes — are drawn or not independently, which is what
     * makes all four of its `EmitCase` arms reachable, including the one where `all` wins.
     */
    private fun buildArgs(spec: ModifierSpec): Map<PropertyKey, PropertyValue> {
        val args = linkedMapOf<PropertyKey, PropertyValue>()
        for (param in spec.params) {
            if (!param.required && random.nextInt(100) >= OPTIONAL_ARGUMENT_PERCENT) continue
            val value = constantFor(param.type, NO_SCOPE) ?: continue
            args[param.key] = PropertyValue.Const(value)
        }
        return args
    }

    // -------------------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------------------

    /** Handlers for every event [spec] declares, some of the time. */
    private fun buildEvents(spec: ComponentSpec?, scope: Scope): Map<EventKey, ActionSequence> {
        if (spec == null || spec.events.isEmpty()) return emptyMap()
        if (random.nextInt(100) >= HANDLER_PERCENT) return emptyMap()
        val events = linkedMapOf<EventKey, ActionSequence>()
        for (event in spec.events) {
            events[event.key] = handler(event, scope)
            handlerCount += 1
        }
        return events
    }

    private fun handler(event: EventSpec, scope: Scope): ActionSequence {
        val steps = mutableListOf<ActionStep>()
        repeat(1 + random.nextInt(2)) { steps += step(event, scope) }
        return ActionSequence(steps)
    }

    /**
     * One action step, drawn from the six §11.4 registers analysis can check without a runtime.
     *
     * Every arm is written out in full rather than half: §11.4 declares six actions and all six
     * are here, because an action a generator never writes is an action
     * `RoundTripPropertyTest` and `PatchInversePropertyTest` would only ever meet in a
     * hand-written fixture. `nav.navigate` is the constrained one — §13.1 makes the target's
     * `ParamDecl`s the step's arguments, so it may only name a page with none, which is what
     * `parameterlessPages` holds. `state.set` is the other: its `target` must be a computed read
     * of a *writable* declaration in the handler's own scope, and §12.3 refuses a derived one
     * as `action.state_not_writable`.
     */
    private fun step(event: EventSpec, scope: Scope): ActionStep {
        val options: MutableList<ActionStep> = mutableListOf()

        options += ActionStep(NAV_BACK)

        options += ActionStep(
            action = SHOW_SNACKBAR,
            args = mapOf(PropertyKey("message") to PropertyValue.Computed(strExpression(scope, 1))),
        )

        options += ActionStep(
            action = CONDITIONAL,
            args = mapOf(PropertyKey("cond") to PropertyValue.Computed(boolExpression(scope, 1, event))),
            branches = mapOf(
                BranchName("then") to ActionSequence(listOf(ActionStep(NAV_BACK))),
                BranchName("else") to ActionSequence(
                    listOf(
                        ActionStep(
                            action = SHOW_SNACKBAR,
                            args = mapOf(
                                PropertyKey("message") to PropertyValue.Computed(strExpression(scope, 0)),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val writable = scope.writable()
        if (writable.isNotEmpty()) {
            val target = writable[random.nextInt(writable.size)]
            options += ActionStep(
                action = SET_STATE,
                args = mapOf(
                    PropertyKey("target") to PropertyValue.Computed(Expr.Ref(RefTarget.State(target.id))),
                    PropertyKey("value") to writtenValueFor(target, scope),
                ),
            )
        }

        if (hosts.isNotEmpty()) {
            val host = hosts[random.nextInt(hosts.size)]
            // The argument list is written even when the declaration takes none. An omitted
            // positional rule is checked against an empty list, so "passes nothing" reads the
            // same way for a zero-parameter function and a two-parameter one and only one of
            // them is right.
            options += ActionStep(
                action = HOST_CALL,
                args = mapOf(
                    PropertyKey("name") to PropertyValue.Const(Value.Str(host.name)),
                    PropertyKey("args") to PropertyValue.Const(
                        Value.ListOf(host.params.map { Value.Str("gen") }),
                    ),
                ),
            )
        }

        val destination = drawFrom(parameterlessPages)
        if (destination != null) {
            options += ActionStep(
                action = NAVIGATE,
                args = mapOf(
                    PropertyKey("page") to PropertyValue.Const(Value.Ref(RefKind.Page, destination.id.value)),
                ),
            )
        }

        val chosen = options[random.nextInt(options.size)]
        record(chosen)
        return chosen
    }

    /** What `state.set` writes: a constant, or an expression of the declaration's own type. */
    private fun writtenValueFor(state: StateDecl, scope: Scope): PropertyValue {
        val constant: Value = constantFor(state.type, scope)
            ?: error("State '${state.id}' is declared over a type this generator cannot write")
        if (random.nextInt(100) < WRITTEN_CONSTANT_PERCENT) return PropertyValue.Const(constant)
        return computedFor(state.type, scope) ?: PropertyValue.Const(constant)
    }

    /** Records what [step] used, branch arms included, for the plan. */
    private fun record(step: ActionStep) {
        actionUsage += step.action
        if (step.branches.isNotEmpty()) branchedUsage += step.action

        val named = (step.args[PropertyKey("name")] as? PropertyValue.Const)?.value as? Value.Str
        if (step.action == HOST_CALL && named != null) hostUsage += named.v

        val target = (step.args[PropertyKey("page")] as? PropertyValue.Const)?.value as? Value.Ref
        if (step.action == NAVIGATE && target != null) navigationUsage += PageId(target.id)

        for (arm in step.branches.values) for (inner in arm.steps) record(inner)
    }

    // -------------------------------------------------------------------------------------
    // State, versions and the plan
    // -------------------------------------------------------------------------------------

    private fun heldState(label: String, declared: TypeRef, initial: Value): StateDecl = StateDecl(
        id = mint.state(),
        name = label,
        type = declared,
        initial = initial,
        derived = null,
        persistence = Persistence.None,
    )

    private fun derivedState(label: String, declared: TypeRef, body: Expr): StateDecl {
        val id = mint.state()
        derivedUsage += id
        return StateDecl(
            id = id,
            name = label,
            type = declared,
            initial = null,
            derived = body,
            persistence = Persistence.None,
        )
    }

    private fun pickThemeMode(): ThemeSelection {
        val modes = ThemeSelection.entries
        return modes[random.nextInt(modes.size)]
    }

    /**
     * D9's contract versions, for the types the document actually placed.
     *
     * `SchemaOverlay.documentComponentSpec` reads this map for a synthesized spec's version and
     * falls back to one when the document says nothing, so writing the static types' versions is
     * inert today and is the first thing an older engine would consult. It is written from
     * [componentUsage] rather than from the whole registry so the document never claims a
     * contract for a component it does not use.
     */
    private fun recordedVersions(): Map<ComponentType, Int> {
        val versions = linkedMapOf<ComponentType, Int>()
        for (entry in componentUsage) {
            val spec = view.components[entry.key] ?: continue
            versions[entry.key] = spec.version
        }
        return versions
    }

    private fun planFor(mode: ThemeSelection): GenerationPlan = GenerationPlan(
        componentUsage = componentUsage.toMap(),
        slotShapes = slotShapes.toList(),
        filledSlots = filledSlots.toList(),
        modifiers = modifierUsage.toList(),
        declaredComponents = declaredComponents.toList(),
        instantiatedComponents = instantiatedComponents.toList(),
        theme = mode,
        stateDeclarations = stateCount,
        derivedStates = derivedUsage.toList(),
        computedProperties = computedProperties,
        handlers = handlerCount,
        actions = actionUsage.toList(),
        branchedActions = branchedUsage.toList(),
        hostCalls = hostUsage.toList(),
        navigations = navigationUsage.toList(),
    )

    private fun smallInt(bound: Int = 64): Int = random.nextInt(bound.coerceAtLeast(1))

    /** A half-integer in `0.0..999.5` — D1's four-fractional-digit rule has nothing to round. */
    private fun smallUnit(): Float = random.nextInt(2000) / 2f

    /**
     * An element of [pool] drawn off this generator's seeded stream, or null when it is empty.
     *
     * ### Why this is written out rather than `pool.randomOrNull()`
     *
     * Because the stdlib's no-argument `Collection.random()`, `randomOrNull()` and `shuffled()`
     * read **`kotlin.random.Random.Default`** — the platform's global generator — and not any
     * source the caller supplies. There are overloads that take a `Random` precisely because the
     * no-argument ones do not, and a `List.randomOrNull()` reads exactly like one of them at a
     * call site.
     *
     * The cost is invisible in every test that builds one document and invisible in any test that
     * reuses one generator instance, because `Random.Default` advances in step with both. It shows
     * up only where §36 wants it: two generators built from the *same* seed, which differ in one
     * `nav.navigate` step's `page` argument and agree everywhere else, because that was the only
     * draw in this class not taken from [random].
     *
     * Every other collection choice in this file is already an explicit `pool[random.nextInt(...)]`
     * for the same reason, and this helper is the one place that pattern is named — so the next
     * reader has one thing to grep for rather than forty call sites to remember.
     */
    private fun <T> drawFrom(pool: List<T>): T? =
        if (pool.isEmpty()) null else pool[random.nextInt(pool.size)]

    /** A page the generator decided to declare, before its tree exists. */
    private class PagePlan(
        val id: PageId,
        val name: String,
        val route: String,
        val params: List<ParamDecl>,
        val state: List<StateDecl>,
    )

    /** A document-defined component the generator decided to declare. */
    private class ComponentPlan(
        val id: ComponentDeclId,
        val name: String,
        val params: List<ParamDecl>,
        val slots: List<SlotDecl>,
        val state: List<StateDecl>,
    )

    private companion object {

        /** Subtree levels below a root. Four `Many` levels is a few dozen nodes. */
        const val MAX_DEPTH: Int = 3

        /** The namespace §5.5 gives a document component's instances. */
        const val DOCUMENT_PREFIX: String = "doc."

        val COLUMN: ComponentType = ComponentType("core.Column")
        val SLOT_OUTLET: ComponentType = ComponentType("core.SlotOutlet")

        /** §11.4's six MVP actions, by id. */
        val NAVIGATE: ActionId = ActionId("nav.navigate")
        val NAV_BACK: ActionId = ActionId("nav.back")
        val SET_STATE: ActionId = ActionId("state.set")
        val CONDITIONAL: ActionId = ActionId("flow.if")
        val HOST_CALL: ActionId = ActionId("host.call")
        val SHOW_SNACKBAR: ActionId = ActionId("ui.showSnackbar")

        /** §10.2's string functions, by id. */
        val STR_TRIM: FunctionId = FunctionId("str.trim")
        val STR_LENGTH: FunctionId = FunctionId("str.length")
        val STR_IS_BLANK: FunctionId = FunctionId("str.isBlank")

        /**
         * The vocabulary a document with no selected theme may write.
         *
         * §14.2's MVP rule: with nothing resolved, `isMaterialToken` accepts any name under
         * `md.`, and one name per token kind is enough to keep all four `Value.Token` kinds
         * reachable.
         */
        val MATERIAL_TOKENS: List<String> = listOf(
            "md.color.primary",
            "md.typography.bodyLarge",
            "md.shape.medium",
            "md.dimension.gap",
        )

        const val OPTIONAL_PROPERTY_PERCENT: Int = 40
        const val COMPUTED_PROPERTY_PERCENT: Int = 25
        const val OPTIONAL_ARGUMENT_PERCENT: Int = 50
        const val MODIFIER_PERCENT: Int = 55
        const val HANDLER_PERCENT: Int = 50
        const val INSTANCE_PERCENT: Int = 70
        const val FILLED_INSTANCE_PERCENT: Int = 70
        const val WRITTEN_CONSTANT_PERCENT: Int = 50

        /**
         * The model `gen.Probe.record` names, and the enum `gen.Probe.grade` names.
         *
         * Fixed ids rather than minted ones, and the reason is that a `TypeRef` in a registered
         * spec has to agree with what the document declares: `gen.Probe` is built once, when
         * [generatorSchema] runs, and a document that minted its model id afterwards would name a
         * type nothing declares, which is `ref.dangling` on every `Value.Obj` written for it.
         */
        val PROBE_MODEL_DATA: DataModelDecl = DataModelDecl(
            id = DataModelId(PROBE_MODEL.value),
            name = "GenProbe",
            fields = listOf(
                FieldDecl(PropertyKey("label"), TypeRef.Str),
                FieldDecl(PropertyKey("count"), TypeRef.Int32),
            ),
        )

        val PROBE_GRADE_DATA: EnumTypeDecl = EnumTypeDecl(
            id = PROBE_GRADE,
            name = "GenGrade",
            entries = listOf(
                EnumEntryDecl("Low"),
                EnumEntryDecl("Medium"),
                EnumEntryDecl("High"),
            ),
        )

        /** A scope with nothing in it, for the writes whose values reference no document. */
        val NO_SCOPE: Scope = Scope(emptyList(), emptyList(), emptyList())
    }
}

// -------------------------------------------------------------------------------------------
// What an expression inside a node may name
// -------------------------------------------------------------------------------------------

/**
 * §12.1's three stores as one node sees them: the app's, its owner's, and its owner's parameters.
 *
 * This is `AnalysisPass.scopeOf` read the other way round. The analysis walks up to the owner and
 * builds the scope; the generator has to walk down into it and build the same thing, because
 * every expression and every `state.set` it writes is checked against this and against nothing
 * else. `visible` is `appStates + ownerStates` in §12.1's order, so a name declared in both
 * resolves to the owner's — the rule `AnalysisPass.stateOf` documents.
 *
 * A private class rather than a data class: it is a scope, not a value, and nothing compares two
 * of them.
 */
private class Scope(
    val appStates: List<StateDecl>,
    val ownerStates: List<StateDecl>,
    val params: List<ParamDecl>,
) {
    val visible: List<StateDecl> = appStates + ownerStates

    /** The first declaration of [declared] in scope, derived or not. */
    fun stateOf(declared: TypeRef): StateDecl? = visible.firstOrNull { it.type == declared }

    /** Every writable declaration, for a `state.set` to choose from. */
    fun writable(): List<StateDecl> = visible.filter { it.derived == null }
}

/** A numeric bound at one or both ends, read off a `PropertyRule.Range`. */
private class NumberBounds(private val low: Double?, private val high: Double?) {

    /** [value] moved inside the bounds; an absent end leaves that side open. */
    fun clamp(value: Double): Double = when {
        low != null && value < low -> low
        high != null && value > high -> high
        else -> value
    }
}
