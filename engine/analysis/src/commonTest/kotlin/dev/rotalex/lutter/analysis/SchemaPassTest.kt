package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.model.doc.EnumEntryDecl
import dev.rotalex.lutter.model.doc.EnumTypeDecl
import dev.rotalex.lutter.model.dsl.PageScope
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.types.TypeSpec
import kotlin.test.Test
import kotlin.test.assertTrue

/** Pass 3: every schema violation fails visibly, with its catalogued code. */
class SchemaPassTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())

    /** Bound to `TypeSpec`, so the schema's own `EnumTypeSpec` is the vocabulary under test. */
    private val enumAnalyzer: Analyzer<String, String, TypeSpec> = Analyzer(enumSchema())

    private fun codesOf(block: PageScope.() -> Unit): List<String> =
        analyzer.analyze(homeDocument(block)).diagnostics.map { it.code.value }

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
}
