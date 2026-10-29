package dev.rotalex.lutter.generated.compile.thirdparty

import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.builtins.registerBuiltinActions
import dev.rotalex.lutter.builtins.registerBuiltinEnums
import dev.rotalex.lutter.builtins.registerBuiltinFunctions
import dev.rotalex.lutter.builtins.registerBuiltinModifiers
import dev.rotalex.lutter.builtins.registerBuiltinSpecs
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.CodegenResult
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.model.diagnostic.DiagnosticCode
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.doc.ParamDecl
import dev.rotalex.lutter.model.doc.PluginRequirement
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.buildDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PluginId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuildException
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EmitterId
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * PLAN §36's row, and the test the row names: a component defined **only in a test module**
 * passes validation, rendering, codegen and serialization with no change to any engine module.
 *
 * ### What "only in a test module" is, concretely
 *
 * `forge.status.Badge` is declared in `integration/generated-compile/src/commonTest`, a module
 * that is not an `engine:*` module at all. Not one line of `:engine:model`, `:engine:schema`,
 * `:engine:serialization`, `:engine:analysis`, `:engine:codegen`, `:engine:runtime`,
 * `:engine:builtins` or `:engine:builtins-compose` mentions it, and nothing here adds one. The
 * only build file involved is this module's, which already declared every edge it needs:
 * `ALLOWED[":integration:generated-compile"]` covers `analysis`, `codegen`, `runtime`,
 * `serialization`, `builtins` and `builtins-compose`, and a test source set may reach everything
 * its own module may reach. So the finding is not "a new module was needed" but "**no
 * module-graph line was needed at all**", which is the outcome §7.4's claim needs and the one
 * that took the looking for.
 *
 * ### The four stages, and what each one cannot see
 *
 * Each stage asserts the strongest thing available to it and names the rest, because a row that
 * ticks four boxes it cannot read is worth less than one that says where its edges are.
 *
 *  * **Validation** is `Analyzer` over a real `UiDocument`. It proves the engine read the plugin's
 *    spec for every check §17.3 has a code for — required, type, range, cardinality, child filter.
 *    It cannot see the plugin's *renderer*, which is not a validation concern.
 *  * **Rendering** here is the registry and `UiRuntime`'s coverage check: the type is reachable by
 *    name, and the engine refuses to start without it. The pixels are `ThirdPartyRenderTest` in
 *    `desktopTest`, because `runComposeUiTest` is desktop-only and this is a `commonTest` file
 *    that also has to run on the Android host.
 *  * **Codegen** is `KotlinGenerator` over the resolved document. It proves the binding drives
 *    emission and that `CodegenCoverage` accepted the spec at construction. It asserts
 *    *fragments*, not a whole file: this repository has no committed generated-Kotlin golden, and
 *    a whole-file expectation written by hand is exactly the hand-written expectation that
 *    describes a different document than the code produces. Each fragment below is derivable from
 *    the binding plus the value it is fed, not from a guess at the printer's layout.
 *  * **Serialization** is `JsonDocumentCodec` both ways. It proves the plugin's type name and its
 *    token survive the wire as data, which is §5.4's D4 and is the only reason a component the
 *    engine has never heard of round-trips at all.
 *
 * ### The two traps, and which side is wrong when they bite
 *
 *  1. **A token whose `kind` disagrees with its property's `TokenKind`.** `Value.Token` carries its
 *    own kind and `ThemeTokenValueKind.accepts` compares it against the declared one, so this is
 *    `prop.type_mismatch`. The corpus got this wrong twice before this branch. **The code is right
 *    and the expectation was the bug**: a document writing `md.typography.bodyLarge` into a
 *    `Token(Color)` property is malformed, because the value names itself as typography and the
 *    property says colour.
 *  2. **A slot the spec declares but the node never writes.** `SchemaPass.checkSlots` iterates
 *    `node.slots`, so a missing key is never visited and `ExactlyOne` is not enforced for it.
 *    **The code is the defect and the expectation is right**: `ExactlyOne` means one, and a node
 *    with no entry for the slot has zero. That row asserts the current behaviour and says so,
 *    because a test that quietly encodes a bug is worse than one that records it.
 */
class ThirdPartyComponentTest {

    private val badge: ComponentType = ComponentType(StatusBadgeSpec.TYPE)

    // -----------------------------------------------------------------------------------------
    // Registration — §7.4 step (3), §8.2
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the contribution installs the plugin's spec into the shared component registry`() {
        val schema = ThirdPartyEngine.schema()

        assertSame(StatusBadgeSpec.spec, schema.components.require(badge))
        assertEquals(
            BUILTIN_COUNT + 1,
            schema.components.all().size,
            "the pack has ten components and the plugin added an eleventh",
        )
        // Side by side, not instead. A contribution that replaced the pack would satisfy the two
        // lines above, and "registry-driven extensibility" is a claim about addition.
        assertEquals("Text", schema.components.require(TEXT).metadata.displayName)
    }

    @Test
    fun `the contribution changes one registry entry and disturbs nothing else`() {
        val extended = ThirdPartyEngine.schema()
        val bare = ThirdPartyEngine.builtinSchema()

        assertEquals(
            bare.components.all().map { it.type },
            extended.components.all().map { it.type }.filter { it != badge },
        )
        assertEquals(bare.modifiers.all().map { it.type }, extended.modifiers.all().map { it.type })
        assertEquals(bare.actions.all().map { it.id }, extended.actions.all().map { it.id })
        assertEquals(bare.functions.all().map { it.id }, extended.functions.all().map { it.id })
        assertEquals(bare.types.all().map { it.id }, extended.types.all().map { it.id })
        assertEquals(1, extended.components.all().count { it.type == badge })
    }

    @Test
    fun `applying the contribution twice fails naming the key and the registry`() {
        val failure = assertFailsWith<SchemaBuildException> {
            Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
                StatusBadgeContribution.contribute(this)
                StatusBadgeContribution.contribute(this)
            }
        }

        assertTrue(failure.message?.contains(StatusBadgeSpec.TYPE) == true, failure.message)
        assertTrue(failure.message?.contains("components") == true, failure.message)
    }

    @Test
    fun `the descriptor carries its identity and its declared dependency`() {
        val descriptor = StatusBadgeContribution.descriptor

        assertEquals(PluginId("forge.status"), descriptor.id)
        assertEquals("0.3.0", descriptor.version)
        assertEquals(1, descriptor.apiVersion)
        assertEquals(
            listOf(PluginRequirement(PluginId("forge.builtins"), "1.0")),
            descriptor.dependsOn,
        )
        // Nothing reads those four fields. §8.2's `Schema` carries a `contributors` list and the
        // one in `:engine:schema` has no such field; §27.2's "checks this at load" belongs to a
        // loader that does not exist. So the dependency above is declared and unenforced, which is
        // why it is asserted here — a host that loaded two plugins could not tell which of them
        // registered `forge.status.Badge`, and nothing would refuse an unsatisfied dependency.
    }

    // -----------------------------------------------------------------------------------------
    // Validation — §17.3 over a document that names the plugin
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a document using the plugin analyses and lowers`() {
        val result = analyze(badgeDocument())

        // Error-severity only, and deliberately: no pass emits a warning today, but the row is
        // about the document being *valid*, and a future advisory finding is not a failure of it.
        assertTrue(errorCodes(result).isEmpty(), "got ${result.diagnostics}")
        // `hasErrors == false` is not "it lowered": `AnalysisResult.resolved` is null whenever a
        // pass errored, and pass 7 is what the runtime and the generator are handed.
        val resolved = assertNotNull(result.resolved, "the document resolved nothing")
        val node = assertNotNull(resolved.pages[HOME]).root.slots.getValue(CHILDREN).single()
        assertEquals(badge, node.type)
        // Pass 7 gives every *declared* slot an entry, filled or not, so this row shows the one
        // the document wrote. The empty case is a separate row, and it is a `SchemaPass` gap
        // rather than a resolution one.
        assertEquals(listOf(TEXT_NODE), node.slots[StatusBadgeSpec.content]?.map { it.id })
    }

    @Test
    fun `the plugin's required rule refuses a badge with no label`() {
        val result = analyze(badgeDocument(badgeProps = listOf("unread" to Value.Int32(1))))

        assertEquals(listOf(DiagnosticCodes.PropRequiredMissing), errorCodes(result))
        assertEquals(
            "Property 'label' is required by '${StatusBadgeSpec.TYPE}' (node '$BADGE')",
            onlyError(result).message,
        )
    }

    @Test
    fun `the plugin's range rule refuses a count outside it`() {
        val result = analyze(
            badgeDocument(badgeProps = DEFAULT_PROPS + ("unread" to Value.Int32(120))),
        )

        assertEquals(listOf(DiagnosticCodes.PropRange), errorCodes(result))
        assertEquals(
            "Property 'unread' is outside its range (node '$BADGE')",
            onlyError(result).message,
        )
    }

    @Test
    fun `a token whose kind the property did not declare is refused`() {
        // The corpus defect, asserted so it cannot come back. `md.typography.bodyLarge` carries
        // `TokenKind.Typography`; `accent` declares `Token(Color)`. `ThemeTokenValueKind.accepts`
        // compares the value's own kind against the declared one, so the *value* is wrong and this
        // expectation is right: there is no version of this document that should have been accepted.
        val result = analyze(
            badgeDocument(
                badgeProps = DEFAULT_PROPS + ("accent" to Value.Token(TokenKind.Typography, "md.typography.bodyLarge")),
            ),
        )

        assertEquals(listOf(DiagnosticCodes.PropTypeMismatch), errorCodes(result))
        assertEquals(
            "Property 'accent' expects Nullable but found Token",
            onlyError(result).message,
        )
        // The node is asserted in `args`, not in the message: `SchemaPass` puts the location in
        // `DiagnosticLocation` and repeats the id as `args["node"]`, so a message that carried
        // it would be the engine writing the same fact twice. Reading it from where the engine
        // actually puts it is a stronger row than the suffix this one asserted.
        assertEquals(BADGE, onlyError(result).args["node"])
    }

    @Test
    fun `two children break cardinality and nothing else`() {
        val result = analyze(
            badgeDocument(
                contentChildren = listOf(
                    "m3.Text" to mapOf("text" to Value.Str("first")),
                    "m3.Text" to mapOf("text" to Value.Str("second")),
                ),
            ),
        )

        // Both children are of the admitted type, so the filter has nothing to say and this row
        // would fail if `checkSlots` reported cardinality as a consequence of the count.
        assertEquals(listOf(DiagnosticCodes.ComponentSlotCardinality), errorCodes(result))
        assertEquals(listOf("content"), onlyError(result).location.path)
    }

    @Test
    fun `one wrong child breaks the filter and nothing else`() {
        // `Cardinality` and `ChildFilter` are two independent facts on `SlotSpec` and two
        // independent checks in `SchemaPass.checkSlots`. The single child here satisfies
        // `ExactlyOne` and violates `ChildFilter.Only({m3.Text})`, so an implementation that
        // conflated them would report both codes and this assertion would fail.
        val result = analyze(
            badgeDocument(contentChildren = listOf("core.Spacer" to emptyMap())),
        )

        assertEquals(listOf(DiagnosticCodes.ComponentChildNotAllowed), errorCodes(result))
        assertEquals(listOf("content", "0"), onlyError(result).location.path)
    }

    @Test
    fun `the slot the node never writes is not checked at all`() {
        // The gap, recorded rather than papered over. `checkSlots` walks `node.slots`, so a spec
        // declaring `ExactlyOne` and a node writing no such key never meet, and the document is
        // clean. If this row ever starts failing, the code was fixed and *this expectation* is the
        // thing that has to change — not the other way round.
        val result = analyze(badgeDocument(writeContentSlot = false))

        assertTrue(errorCodes(result).isEmpty(), "got ${result.diagnostics}")
        assertNotNull(result.resolved)
    }

    @Test
    fun `the plugin's spec survives the document overlay`() {
        // `Analyzer` builds §8.4's `SchemaOverlay` whenever `document.components` is non-empty, and
        // that overlay is a merged registry, not the static one. A plugin's entry has to survive
        // the merge — an overlay that rebuilt from the builtins alone would drop
        // `forge.status.Badge` and every document mixing a `doc.*` component with a plugin
        // component would report `component.unknown`. This document declares both.
        val document = buildDocument("ThirdPartyBadgeWithADeclaration") {
            component(
                id = ComponentDeclId("c_strip"),
                name = "Strip",
                params = listOf(ParamDecl(LABEL_NAME, TypeRef.Str)),
            ) {
                node("core.Column") {
                    slot("children", listOf(node("m3.Text") { prop("text", Value.Str("declared")) }))
                }
            }
            page(name = "Home", route = "home", id = HOME) {
                node("core.Column") {
                    // Both children go into `children`, in that order, because the assertion below
                    // reads the second position and because an unslotted node is an orphan — the
                    // same reason the shared fixture writes its `slot` call explicitly.
                    val strip: NodeId = node("doc.c_strip") { prop("label", Value.Str("x")) }
                    val badgeNode: NodeId = node(StatusBadgeSpec.TYPE) {
                        prop("label", Value.Str("Inbox"))
                        prop("unread", Value.Int32(3))
                    }
                    slot(CHILDREN, listOf(strip, badgeNode))
                }
            }
        }

        val result = analyze(document)

        assertTrue(errorCodes(result).isEmpty(), "got ${result.diagnostics}")
        val resolved = assertNotNull(result.resolved)
        assertEquals(
            badge,
            assertNotNull(resolved.pages[HOME]).root.slots.getValue(CHILDREN)[1].type,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Rendering — §8.3 and §15.2. The pixels are `ThirdPartyRenderTest`; this is the wiring.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the runtime refuses to start when the plugin's renderer is missing`() {
        val failure = assertFailsWith<IllegalStateException> {
            ThirdPartyEngine.runtime(ThirdPartyEngine.schema(), withPluginRenderers = false)
        }

        assertTrue(failure.message?.contains(StatusBadgeSpec.TYPE) == true, failure.message)
    }

    @Test
    fun `the runtime starts once the plugin's renderer is registered`() {
        val runtime = ThirdPartyEngine.runtime(ThirdPartyEngine.schema())

        assertSame(StatusBadgeRenderer, runtime.renderers.require(badge))
        // And the pack's own renderers are still there: the plugin registered into the same
        // registry rather than replacing it.
        assertTrue(TEXT in runtime.renderers)
    }

    @Test
    fun `the generator refuses to start when the plugin's binding is missing`() {
        // The codegen half of §4.5, and the reason the codegen section below can assume a
        // generator exists at all. `KotlinGenerator.init` runs `CodegenCoverage.check`, which
        // reads the binding off the registry entry; a `custom` binding naming an emitter nobody
        // registered refuses construction for the same reason `UiRuntime` refuses a missing
        // renderer, and names the type rather than throwing on the first document.
        val schema = Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
            registerBuiltinSpecs()
            registerBuiltinModifiers()
            registerBuiltinEnums()
            registerBuiltinFunctions()
            registerBuiltinActions()
            component(
                componentSpec(ComponentType(UNBOUND), version = 1) {
                    metadata(displayName = "Unbound", category = Category.Basic)
                    custom(EmitterId("forge.status.nothing"))
                },
            )
        }

        val failure = assertFailsWith<IllegalStateException> {
            KotlinGenerator(schema, CodegenOptions(BASE_PACKAGE))
        }

        assertTrue(failure.message?.contains(UNBOUND) == true, failure.message)
    }

    // -----------------------------------------------------------------------------------------
    // Codegen — §7.2's binding drives §16's emitter
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the generator emits the plugin's component from its binding alone`() {
        val result = generate(badgeDocument())

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        assertTrue(SCREEN in result.files.files.map { it.path }, "got ${result.files}")
        val screen = content(result, SCREEN)

        // The import is the binding's own `KotlinSymbol`, reached through the plugin's registry
        // entry. Nothing in the generator knows `com.forge.status` from anywhere else.
        assertTrue(screen.contains("import com.forge.status.StatusBadge"), screen)
        // One argument per line, because four properties are present and `Positional.WhenSole`
        // only shortens when one is. §7.4's positional shape has its own row below.
        assertTrue(screen.contains("label = \"Inbox\","), screen)
        // The token becomes the Material member its `TokenKind` addresses. `LiteralPrinter` maps
        // `TokenKind.Color` to `colorScheme` and strips the `md.color.` segment; that mapping is
        // the value of the fact, not a guess at the printer's layout.
        assertTrue(screen.contains("accent = MaterialTheme.colorScheme.primary,"), screen)
        assertTrue(screen.contains("unread = 3,"), screen)
        // An engine modifier, folded onto a third-party component by the engine's own modifier
        // registry and the binding's `modifierParam`. `layout.padding` is registered by
        // `:engine:builtins`; the only thing this component had to name is the parameter.
        assertTrue(screen.contains("padding(8.0.dp)"), screen)
        // The slot: `LambdaTarget.Trailing`, holding the `m3.Text` the filter admitted, emitted
        // through the engine's binding for `m3.Text` rather than through anything the plugin said
        // about how a `Text` prints.
        assertTrue(screen.contains("Text(\"body\")"), screen)
    }

    @Test
    fun `a lone label becomes a positional argument`() {
        val result = generate(
            badgeDocument(
                badgeProps = listOf("label" to Value.Str("Inbox")),
                badgeModifiers = false,
                writeContentSlot = false,
            ),
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val screen = content(result, SCREEN)
        // Layout-independently: the value is written without a parameter name, which is the whole
        // of `Positional.WhenSole`. Asserting the exact call text here would be asserting the
        // printer's argument layout rather than the binding's rule, and the layout is what §16.3
        // tunes.
        assertFalse(screen.contains("label ="), "a sole label must not be written as a named argument:\n$screen")
        assertTrue(screen.contains("\"Inbox\""), screen)
    }

    @Test
    fun `a token whose name its own kind does not address analyses clean and is refused by codegen`() {
        // The disagreement, asserted rather than described. `Value.Token(Color, "md.typography.…")`
        // calls itself a colour token while spelling a typography role. Pass 4 accepts it — the
        // kind matches the property and `md.` is Material's namespace, so `token.unknown` does not
        // fire — and pass 7 attaches provenance. Codegen then refuses it, because
        // `LiteralPrinter.materialRole` checks the name's `md.<kind>.` segment against the kind and
        // gets `typography` where it asked for `color`.
        //
        // The runtime answers differently: `DefaultThemeHandle.textStyle` calls `materialRoleOf`
        // with `"typography"` hard-coded, matches, and draws. So one valid document renders on
        // one backend and is refused on the other. `ThirdPartyRenderTest` draws this same
        // document, which is what makes the half of the claim that is not in this file testable
        // rather than merely argued.
        val document = badgeDocument(
            badgeProps = DEFAULT_PROPS + ("accent" to Value.Token(TokenKind.Color, "md.typography.bodyLarge")),
        )

        assertTrue(
            errorCodes(analyze(document)).isEmpty(),
            "the analyzer accepting this is the point, not a formality",
        )

        val result = generate(document)
        assertEquals(listOf(DiagnosticCodes.CodegenStrategyUnsupported), result.diagnostics.map { it.code })
        assertTrue(result.files.files.isEmpty(), "a refused document emits files")
    }

    // -----------------------------------------------------------------------------------------
    // Serialization — §5.4's D4, through the codec the CLI and the preview both use
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the document round-trips through the codec with the plugin's type and token intact`() {
        val document = badgeDocument()
        val decoded = JsonDocumentCodec.decode(JsonDocumentCodec.encode(document)).document

        assertEquals(document, decoded)

        // Equality above is the strong claim; these two are what it is made of, and they say which
        // part of the plugin survived. A codec that dropped unknown component types would still
        // produce a document — `Value` is closed, `Node.type` is a string, and §5.4's D4 says a
        // value naming a type nothing registers decodes on purpose. `forge.status.Badge` is
        // exactly that: `:engine:model` has never heard of it and it decodes anyway.
        val badgeNode = decoded.nodes.require(NodeId(BADGE))
        assertEquals(badge, badgeNode.type)
        // The wrapper, not the token alone. `PropertyValue` is a sealed interface of `Const` and
        // `Computed` and has no `value` member of its own — `value` is declared on `Const` — so
        // the token is only reachable through the arm that holds it. Comparing the wrapper also
        // refuses the `Computed` arm for free, which is the half of "intact" worth stating.
        val accent = assertNotNull(
            badgeNode.props[StatusBadgeSpec.accent.key],
            "the accent token did not survive the wire",
        )
        assertEquals(PropertyValue.Const(Value.Token(TokenKind.Color, "md.color.primary")), accent)
    }

    @Test
    fun `the canonical bytes are stable across a second encode`() {
        val once = JsonDocumentCodec.encode(badgeDocument())
        val twice = JsonDocumentCodec.encode(JsonDocumentCodec.decode(once).document)

        assertContentEquals(once, twice)
    }

    @Test
    fun `the decoded document is the one the engine can still resolve`() {
        // The end of the round trip and the start of the next one. A document that survives the
        // wire has to analyse against a schema the plugin contributed to, or the codec has
        // preserved something the engine cannot use — which is the only way "it serializes" could
        // have been true and meant nothing.
        val decoded = JsonDocumentCodec.decode(JsonDocumentCodec.encode(badgeDocument())).document

        val result = analyze(decoded)
        assertTrue(errorCodes(result).isEmpty(), "got ${result.diagnostics}")
        assertNotNull(result.resolved)
    }

    // -----------------------------------------------------------------------------------------
    // The spec the test is about, asserted directly
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the plugin's spec declares what the validation rows assume about it`() {
        val spec = StatusBadgeSpec.spec
        val content = assertNotNull(spec.slots.firstOrNull { it.name == StatusBadgeSpec.content })

        // The two slot facts, read back as data. Five of the validation rows above are only
        // meaningful while these hold, and a spec edited to match a broken expectation would
        // otherwise take all of them with it.
        assertEquals(Cardinality.ExactlyOne, content.cardinality)
        assertEquals(ChildFilter.Only(setOf(StatusBadgeSpec.contentType)), content.accepts)
        assertEquals(listOf("label", "accent", "unread"), spec.properties.map { it.key.value })
        assertTrue(StatusBadgeSpec.label.required, "the required row needs it required")
    }

    // -----------------------------------------------------------------------------------------
    // Documents
    // -----------------------------------------------------------------------------------------

    /**
     * One page, one column, one plugin badge with its slot filled — the document every stage runs.
     *
     * [contentChildren] is a list of type-to-properties pairs rather than of text, because the
     * filter row needs a child that takes no properties at all. `core.Spacer` is the only child
     * type in the pack that refuses one, which is what makes it the one that proves the filter.
     */
    private fun badgeDocument(
        badgeProps: List<Pair<String, Value>> = DEFAULT_PROPS,
        badgeModifiers: Boolean = true,
        contentChildren: List<Pair<String, Map<String, Value>>> = listOf(BODY_TEXT),
        writeContentSlot: Boolean = true,
    ): UiDocument = buildDocument("ThirdPartyBadge") {
        page(name = "Home", route = "home", id = HOME) {
            node("core.Column") {
                // The `slot` call is what puts the badge in the tree. `PageScope.node` records a
                // top-level node and `NodeScope.node` returns an id; neither links one, and
                // `StructuralPass.checkOrphans` walks from the page root through slot edges only.
                // A badge no slot names is in the table and in no tree, which every row that
                // analyzes this document then reads as `struct.orphan` and nothing else — pass 1
                // is blocking, so passes 3 to 7 never run and the check each row is about is
                // never reached.
                val badgeNode: NodeId = node(StatusBadgeSpec.TYPE) {
                    for ((key, value) in badgeProps) prop(key, value)
                    if (badgeModifiers) {
                        modifier("layout.padding", mapOf(PADDING to PropertyValue.Const(Value.Dp(8f))))
                    }
                    if (writeContentSlot) {
                        slot(
                            "content",
                            contentChildren.map { (type, properties) ->
                                node(type) { for ((key, value) in properties) prop(key, value) }
                            },
                        )
                    }
                }
                slot(CHILDREN, listOf(badgeNode))
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------------------------

    private fun analyze(document: UiDocument): AnalysisResult =
        Analyzer<ActionSpec, FunctionSpec, TypeSpec>(ThirdPartyEngine.schema()).analyze(document)

    /**
     * Generation over the analyzed document, with the analysis asserted first.
     *
     * `generate` refuses an error-carrying document by echoing the incoming diagnostics back
     * (`KotlinGenerator.generate`), so a validation regression here would read as a codegen
     * failure. This check makes the stage that failed the first one to say so.
     */
    private fun generate(document: UiDocument): CodegenResult {
        val analyzed = analyze(document)
        val resolved = assertNotNull(analyzed.resolved, "the document does not resolve: ${analyzed.diagnostics}")
        return KotlinGenerator(ThirdPartyEngine.schema(), CodegenOptions(BASE_PACKAGE)).generate(resolved)
    }

    private fun content(result: CodegenResult, path: String): String = assertNotNull(
        result.files.files.firstOrNull { it.path == path },
        "no '$path' in ${result.files.files.map { it.path }}",
    ).content

    /** Error-severity codes in order, so a row that gains a second diagnostic fails visibly. */
    private fun errorCodes(result: AnalysisResult): List<DiagnosticCode> =
        result.diagnostics.filter { it.severity == Severity.Error }.map { it.code }

    /** The single error a row above has already established there is exactly one of. */
    private fun onlyError(result: AnalysisResult) = result.diagnostics.single { it.severity == Severity.Error }

    private companion object {
        /** `buildDocument` mints `n_1`, `n_2`, `n_3` in creation order; the badge is the second. */
        const val BADGE: String = "n_2"
        const val UNBOUND: String = "forge.status.Unbound"
        const val BASE_PACKAGE: String = "com.example.app"
        const val SCREEN: String = "screens/HomeScreen.kt"

        /** `registerBuiltinSpecs` registers ten components; §7.5's MVP layout row plus wave 2's first. */
        const val BUILTIN_COUNT: Int = 10

        val HOME: PageId = PageId("p_home")
        val TEXT: ComponentType = ComponentType("m3.Text")
        val CHILDREN: SlotName = SlotName("children")
        val TEXT_NODE: NodeId = NodeId("n_3")
        val PADDING: PropertyKey = PropertyKey("all")
        val LABEL_NAME: ParamName = ParamName("label")

        /**
         * The accent token the happy path carries: `md.color.primary`.
         *
         * Because the value's `kind` and the name's `md.<kind>.` segment agree, which is the
         * invariant the mismatch rows above break on purpose and the reason this one reaches
         * `MaterialTheme.colorScheme.primary` rather than a refusal.
         */
        val ACCENT: Pair<String, Value> = "accent" to Value.Token(TokenKind.Color, "md.color.primary")

        val DEFAULT_PROPS: List<Pair<String, Value>> =
            listOf("label" to Value.Str("Inbox"), ACCENT, "unread" to Value.Int32(3))

        val BODY_TEXT: Pair<String, Map<String, Value>> = "m3.Text" to mapOf("text" to Value.Str("body"))
    }
}