package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.model.doc.EnumEntryDecl
import dev.rotalex.lutter.model.doc.EnumTypeDecl
import dev.rotalex.lutter.model.doc.ResourceDecl
import dev.rotalex.lutter.model.doc.ResourceKind
import dev.rotalex.lutter.model.doc.ResourceSource
import dev.rotalex.lutter.model.doc.ResourceVariant
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.PageScope
import dev.rotalex.lutter.model.dsl.ThemeScope
import dev.rotalex.lutter.model.dsl.buildDocument
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.ResourceId
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.ValueEmit
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop as schemaProp
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.IconSet
import dev.rotalex.lutter.schema.types.TypeSpec
import kotlin.test.Test
import kotlin.test.assertTrue

/** Pass 3: every schema violation fails visibly, with its catalogued code. */
class SchemaPassTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())

    /** Bound to `TypeSpec`, so the schema's own `EnumTypeSpec` is the vocabulary under test. */
    private val enumAnalyzer: Analyzer<String, String, TypeSpec> = Analyzer(enumSchema())

    private fun codesOf(block: PageScope.() -> Unit): List<String> =
        analyzer.analyze(homeDocument(block = block)).diagnostics.map { it.code.value }

    /** A Text carrying [entry] on its enum-typed property, against [declared] if given. */
    private fun enumCodesOf(entry: String, declared: List<String> = emptyList()): List<String> {
        val document = homeDocument {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                prop("align", Value.Enum(entry))
            }
        }
        val withEnum = if (declared.isEmpty()) {
            document
        } else {
            document.copy(
                enums = mapOf(
                    TypeId("Align") to EnumTypeDecl(
                        TypeId("Align"),
                        "Align",
                        declared.map { EnumEntryDecl(it) },
                    ),
                ),
            )
        }
        return enumAnalyzer.analyze(withEnum).diagnostics.map { it.code.value }
    }

    @Test
    fun `unregistered component reports component unknown`() {
        val codes = codesOf { node("core.Ghost") }

        assertTrue(codes.contains(DiagnosticCodes.ComponentUnknown.value), "got $codes")
    }

    @Test
    fun `undeclared slot reports slot unknown`() {
        val codes = codesOf {
            node(ColumnType) {
                val title = node(TextType) { prop("text", Value.Str("Hi")) }
                slot("bogus", listOf(title))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ComponentSlotUnknown.value), "got $codes")
    }

    @Test
    fun `two children in a single slot reports cardinality`() {
        val codes = codesOf {
            node(CardType) {
                val first = node(TextType) { prop("text", Value.Str("a")) }
                val second = node(TextType) { prop("text", Value.Str("b")) }
                slot("content", listOf(first, second))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ComponentSlotCardinality.value), "got $codes")
    }

    @Test
    fun `rejected child type reports child not allowed`() {
        val codes = codesOf {
            node(CardType) {
                val nested = node(ColumnType) {
                    val title = node(TextType) { prop("text", Value.Str("Hi")) }
                    slot("children", listOf(title))
                }
                slot("content", listOf(nested))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ComponentChildNotAllowed.value), "got $codes")
    }

    @Test
    fun `exclusive properties together report conflicting properties`() {
        val codes = codesOf {
            node(ColumnType) {
                prop("spacing", Value.Dp(8f))
                prop("axis", Value.Str("vertical"))
                val title = node(TextType) { prop("text", Value.Str("Hi")) }
                slot("children", listOf(title))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ComponentConflictingProperties.value), "got $codes")
    }

    @Test
    fun `undeclared property reports prop unknown`() {
        val codes = codesOf {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                prop("bogus", Value.Str("x"))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.PropUnknown.value), "got $codes")
    }

    @Test
    fun `absent required property reports required missing`() {
        val codes = codesOf { node(TextType) }

        assertTrue(codes.contains(DiagnosticCodes.PropRequiredMissing.value), "got $codes")
    }

    @Test
    fun `wrong value variant reports type mismatch`() {
        val codes = codesOf {
            node(TextType) { prop("text", Value.Int32(3)) }
        }

        assertTrue(codes.contains(DiagnosticCodes.PropTypeMismatch.value), "got $codes")
    }

    @Test
    fun `unknown enum entry reports enum entry invalid`() {
        val document = homeDocument {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                prop("align", Value.Enum("Sideways"))
            }
        }.copy(enums = mapOf(TypeId("Align") to AlignDecl))

        val codes = analyzer.analyze(document).diagnostics.map { it.code.value }

        assertTrue(codes.contains(DiagnosticCodes.PropEnumEntryInvalid.value), "got $codes")
    }

    @Test
    fun `a schema-registered enum needs no declaration in the document`() {
        val codes = enumCodesOf("Center")

        assertTrue(codes.none { it == DiagnosticCodes.PropEnumEntryInvalid.value }, "got $codes")
    }

    @Test
    fun `the schema vocabulary wins over the document's`() {
        // `Sideways` is the only entry the document declares and the schema declares it as
        // nobody: one owner per `TypeId`, and the schema's is the one that counts.
        val codes = enumCodesOf("Sideways", declared = listOf("Sideways"))

        assertTrue(codes.contains(DiagnosticCodes.PropEnumEntryInvalid.value), "got $codes")
    }

    @Test
    fun `an enum neither side declares reports enum entry invalid`() {
        val codes = enumCodesOf("Sideways")

        assertTrue(codes.contains(DiagnosticCodes.PropEnumEntryInvalid.value), "got $codes")
    }

    @Test
    fun `out of range number reports range`() {
        val codes = codesOf {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                prop("maxLines", Value.Int32(0))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.PropRange.value), "got $codes")
    }

    @Test
    fun `computed value on a refusing property reports not bindable`() {
        val codes = codesOf {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                computed("tag", Expr.Const(Value.Str("x")))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.PropNotBindable.value), "got $codes")
    }

    @Test
    fun `non-finite number reports non-finite`() {
        val codes = codesOf {
            node(ColumnType) {
                prop("spacing", Value.Dp(Float.NaN))
                val title = node(TextType) { prop("text", Value.Str("Hi")) }
                slot("children", listOf(title))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ValueNonFinite.value), "got $codes")
    }

    @Test
    fun `unregistered modifier reports modifier unknown`() {
        val codes = codesOf {
            node(ColumnType) {
                modifier("layout.ghost")
                val title = node(TextType) { prop("text", Value.Str("Hi")) }
                slot("children", listOf(title))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ModifierUnknown.value), "got $codes")
    }

    @Test
    fun `scoped modifier outside its scope reports scope missing`() {
        val codes = codesOf {
            node(ColumnType) {
                modifier(WeightType, mapOf(PropertyKey("weight") to constOf(Value.Float32(1f))))
                val title = node(TextType) { prop("text", Value.Str("Hi")) }
                slot("children", listOf(title))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ModifierScopeMissing.value), "got $codes")
    }

    @Test
    fun `scoped modifier inside its scope reports nothing`() {
        val codes = codesOf {
            node(ColumnType) {
                val title = node(TextType) {
                    prop("text", Value.Str("Hi"))
                    modifier(WeightType, mapOf(PropertyKey("weight") to constOf(Value.Float32(1f))))
                }
                slot("children", listOf(title))
            }
        }

        assertTrue(codes.none { it == DiagnosticCodes.ModifierScopeMissing.value }, "got $codes")
    }

    @Test
    fun `a scope reaches a grandchild, not only a direct child`() {
        val codes = codesOf {
            node(ColumnType) {
                val middle = node(ColumnType) {
                    val deep = node(TextType) {
                        prop("text", Value.Str("Deep"))
                        modifier(WeightType, mapOf(PropertyKey("weight") to constOf(Value.Float32(1f))))
                    }
                    slot("children", listOf(deep))
                }
                slot("children", listOf(middle))
            }
        }

        assertTrue(codes.none { it == DiagnosticCodes.ModifierScopeMissing.value }, "got $codes")
    }

    @Test
    fun `undeclared modifier argument reports arg invalid`() {
        val codes = codesOf {
            node(ColumnType) {
                modifier(PaddingType, mapOf(PropertyKey("bogus") to constOf(Value.Dp(4f))))
                val title = node(TextType) { prop("text", Value.Str("Hi")) }
                slot("children", listOf(title))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ModifierArgInvalid.value), "got $codes")
    }

    @Test
    fun `a required modifier argument the document omits reports arg invalid`() {
        val codes = codesOf {
            node(ColumnType) {
                // `layout.size` needs both axes; one is the case the applier and codegen cannot
                // survive, so the pass has to name it rather than let it reach Compose.
                modifier(SizeType, mapOf(PropertyKey("width") to constOf(Value.Dp(10f))))
                val title = node(TextType) { prop("text", Value.Str("Hi")) }
                slot("children", listOf(title))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ModifierArgInvalid.value), "got $codes")
    }

    @Test
    fun `both axes present leaves nothing to report`() {
        val codes = codesOf {
            node(ColumnType) {
                modifier(
                    SizeType,
                    mapOf(
                        PropertyKey("width") to constOf(Value.Dp(10f)),
                        PropertyKey("height") to constOf(Value.Dp(20f)),
                    ),
                )
                val title = node(TextType) { prop("text", Value.Str("Hi")) }
                slot("children", listOf(title))
            }
        }

        assertTrue(codes.none { it == DiagnosticCodes.ModifierArgInvalid.value }, "got $codes")
    }

    @Test
    fun `clean schema reports nothing`() {
        val codes = codesOf {
            node(ColumnType) {
                modifier(PaddingType, mapOf(PropertyKey("all") to constOf(Value.Dp(4f))))
                val title = node(TextType) {
                    prop("text", Value.Str("Hi"))
                    prop("maxLines", Value.Int32(3))
                }
                slot("children", listOf(title))
            }
        }

        assertTrue(codes.isEmpty(), "got $codes")
    }

    @Test
    fun `the valid schema view emits none of the code family`() {
        val codes = codesOf {
            node(ColumnType) {
                modifier(PaddingType, mapOf(PropertyKey("all") to constOf(Value.Dp(4f))))
                val title = node(TextType) {
                    prop("text", Value.Str("Hi"))
                    prop("maxLines", Value.Int32(3))
                }
                slot("children", listOf(title))
            }
        }

        assertTrue(codes.none { it == DiagnosticCodes.ComponentUnknown.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.ComponentSlotUnknown.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.ComponentSlotCardinality.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.ComponentChildNotAllowed.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.ComponentConflictingProperties.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.PropUnknown.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.PropRequiredMissing.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.PropTypeMismatch.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.PropEnumEntryInvalid.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.PropRange.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.PropNotBindable.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.ValueNonFinite.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.ModifierUnknown.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.ModifierScopeMissing.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.ModifierArgInvalid.value }, "got $codes")
    }

    @Test
    fun `an icon in the registered set is accepted and a stranger is refused`() {
        // §10:796: the (set, name) pair resolves through the schema's icon registry — the
        // case-shaped lookup no text pattern can spell, so the registry is the whole check.
        val setProp = schemaProp<String>("set", TypeRef.Str, required = true)
        val nameProp = schemaProp<String>("name", TypeRef.Str, required = true)
        val iconType = ComponentType("test.Icon")
        val iconSchema =
            Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
                iconSet(
                    IconSet(
                        "material",
                        mapOf("Home" to KotlinSymbol("androidx.compose.material.icons.filled", "Home")),
                    ),
                )
                component(
                    componentSpec(iconType, 1) {
                    metadata("Icon", Category.Basic)
                    property(setProp)
                    property(nameProp)
                    composeCall(KotlinSymbol("test", "Icon")) {
                        param(
                            "imageVector",
                            from = listOf(setProp.key, nameProp.key),
                            emit = ValueEmit.Icon(setProp.key, nameProp.key),
                        )
                    }
                },
            )
        }
        val iconAnalyzer = Analyzer(iconSchema)

        fun iconCodesOf(name: String): List<String> =
            iconAnalyzer.analyze(
                homeDocument {
                    node(iconType) {
                        prop("set", Value.Str("material"))
                        prop("name", Value.Str(name))
                    }
                },
            ).diagnostics.map { it.code.value }

        assertTrue(
            iconCodesOf("Home").none { it == DiagnosticCodes.PropIconUnregistered.value },
            "a registered icon was refused: got ${iconCodesOf("Home")}",
        )
        assertTrue(
            iconCodesOf("Ghost").contains(DiagnosticCodes.PropIconUnregistered.value),
            "got ${iconCodesOf("Ghost")}",
        )
    }

    @Test
    fun `a resource reference whose kind disagrees with its property is refused`() {
        // §10:795's resource row: a resource-typed property takes a resource reference only
        // when the reference's kind is the one the spec declared; a matching one just passes.
        val titleProp = schemaProp<String>("title", TypeRef.Str, required = true, resourceKind = ResourceKind.String)
        val bannerType = ComponentType("test.Banner")
        val bannerSchema =
            Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
                component(
                    componentSpec(bannerType, 1) {
                        metadata("Banner", Category.Basic)
                        property(titleProp)
                        composeCall(KotlinSymbol("test", "Banner")) {
                            param("title", from = titleProp)
                        }
                    },
                )
            }
        val bannerAnalyzer = Analyzer(bannerSchema)

        fun bannerCodesOf(declKind: ResourceKind): List<String> {
            val resources = mapOf(
                ResourceId("r_welcome") to ResourceDecl(
                    ResourceId("r_welcome"),
                    "Welcome",
                    declKind,
                    listOf(ResourceVariant(emptySet(), ResourceSource.Text("Hello"))),
                ),
            )
            return bannerAnalyzer.analyze(
                homeDocument {
                    node(bannerType) {
                        prop("title", Value.Ref(RefKind.Resource, "r_welcome"))
                    }
                }.copy(resources = resources),
            ).diagnostics.map { it.code.value }
        }

        assertTrue(
            bannerCodesOf(ResourceKind.String).none { it == DiagnosticCodes.ResourceKindMismatch.value },
            "a matching resource was refused: got ${bannerCodesOf(ResourceKind.String)}",
        )
        assertTrue(
            bannerCodesOf(ResourceKind.Image).contains(DiagnosticCodes.ResourceKindMismatch.value),
            "got ${bannerCodesOf(ResourceKind.Image)}",
        )
    }

    // ---------------------------------------------------------------------------------
    // theme.component_unknown / theme.default_unknown / theme.default_type_mismatch /
    // theme.default_not_const / prop.default_shadowed — §14.2's defaults against the schema
    // ---------------------------------------------------------------------------------

    /** A document whose single theme carries [themeBlock] and whose page carries [pageBlock]. */
    private fun themedDocument(
        themeBlock: ThemeScope.() -> Unit,
        pageBlock: PageScope.() -> Unit = { node(ColumnType) { slot("children", emptyList()) } },
    ): UiDocument = buildDocument("demo") {
        theme("t", "Light", block = themeBlock)
        page(name = "Home", route = "home", id = PageId("p_home"), block = pageBlock)
    }

    private fun themeCodesOf(
        themeBlock: ThemeScope.() -> Unit,
        pageBlock: PageScope.() -> Unit = { node(ColumnType) { slot("children", emptyList()) } },
    ): List<String> =
        analyzer.analyze(themedDocument(themeBlock, pageBlock)).diagnostics.map { it.code.value }

    @Test
    fun `a default naming an unregistered component reports theme component unknown`() {
        val codes = themeCodesOf(themeBlock = {
            componentDefaults("m3.Ghost") {
                prop("spacing", Value.Dp(4f))
            }
        })

        assertTrue(codes.contains(DiagnosticCodes.ThemeComponentUnknown.value), "got $codes")
    }

    @Test
    fun `a default naming an undeclared property reports theme default unknown`() {
        val codes = themeCodesOf(themeBlock = {
            componentDefaults("core.Column") {
                prop("nope", Value.Dp(4f))
            }
        })

        assertTrue(codes.contains(DiagnosticCodes.ThemeDefaultUnknown.value), "got $codes")
    }

    @Test
    fun `a default of the wrong type reports theme default type mismatch`() {
        val codes = themeCodesOf(themeBlock = {
            componentDefaults("core.Column") {
                prop("spacing", Value.Str("big"))
            }
        })

        assertTrue(codes.contains(DiagnosticCodes.ThemeDefaultTypeMismatch.value), "got $codes")
    }

    @Test
    fun `a default whose value is not a constant reports theme default not const`() {
        val computed = PropertyValue.Computed(Expr.Ref(RefTarget.State(dev.rotalex.lutter.model.ids.StateId("s_x"))))
        val document = themedDocument(themeBlock = {
            componentDefaults("core.Column") {
                prop("spacing", Value.Dp(4f))
            }
        })
        val withComputed = document.copy(
            themes = document.themes.mapValues { (_, theme) ->
                theme.copy(
                    componentDefaults = mapOf(
                        ComponentType("core.Column") to mapOf(
                            PropertyKey("spacing") to computed,
                        ),
                    ),
                )
            },
        )

        val codes = analyzer.analyze(withComputed).diagnostics.map { it.code.value }

        assertTrue(codes.contains(DiagnosticCodes.ThemeDefaultNotConst.value), "got $codes")
    }

    @Test
    fun `a default the document shadows reports prop default shadowed`() {
        val codes = themeCodesOf(
            themeBlock = {
                componentDefaults("core.Column") {
                    prop("spacing", Value.Dp(12f))
                }
            },
            pageBlock = {
                node(ColumnType) {
                    prop("spacing", Value.Dp(4f))
                    slot("children", emptyList())
                }
            },
        )

        assertTrue(codes.contains(DiagnosticCodes.PropDefaultShadowed.value), "got $codes")
    }

    @Test
    fun `a theme default the document accepts reports nothing`() {
        val codes = themeCodesOf(themeBlock = {
            componentDefaults("core.Column") {
                prop("spacing", Value.Dp(12f))
                prop("axis", Value.Str("vertical"))
            }
        })

        assertTrue(codes.none { it == DiagnosticCodes.ThemeComponentUnknown.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.ThemeDefaultUnknown.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.ThemeDefaultTypeMismatch.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.ThemeDefaultNotConst.value }, "got $codes")
        assertTrue(codes.none { it == DiagnosticCodes.PropDefaultShadowed.value }, "got $codes")
    }
}
