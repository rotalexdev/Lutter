package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.function.FunctionPrecedence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The printer over hand-built IR: no document, no schema, no fixtures, no filesystem.
 *
 * The expected text is written out in full rather than probed for substrings, because the
 * thing under test is the layout — blank lines, indentation, `{}` — and a substring assertion
 * cannot see a missing blank line.
 */
class KtPrinterTest {

    @Test
    fun `a class prints its members with a blank line between them`() {
        val printed = KtPrinter().print(
            ktFile(
                KtDeclaration.Class(
                    "HomeState",
                    emptyList(),
                    listOf(
                        property(
                            "count",
                            KtExpr.Name("Int"),
                            mutable = true,
                            initializer = KtExpr.Literal("0"),
                        ),
                        property(
                            "doubled",
                            KtExpr.Name("Int"),
                            getter = KtExpr.Call(
                                KtExpr.Member(KtExpr.Name("count"), "toString"),
                                emptyList(),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            """
            package com.example.app

            public class HomeState {
                public var count: Int = 0

                public val doubled: Int
                    get() = count.toString()
            }

            """.trimIndent(),
            printed,
        )
    }

    @Test
    fun `a class with no members still emits braces`() {
        val printed = KtPrinter().print(
            ktFile(
                KtDeclaration.Function("Alpha", emptyList(), emptyList(), emptyList()),
                KtDeclaration.Class("Beta", emptyList(), emptyList()),
            ),
        )

        // The blank line is the point: §16.3 asks for it between any two top-level
        // declarations, and a class is one.
        assertEquals(
            """
            package com.example.app

            public fun Alpha() {}

            public class Beta {}

            """.trimIndent(),
            printed,
        )
    }

    @Test
    fun `a nested class indents its own members`() {
        val printed = KtPrinter().print(
            ktFile(
                KtDeclaration.Class(
                    "Models",
                    emptyList(),
                    listOf(
                        KtDeclaration.Class(
                            "User",
                            emptyList(),
                            listOf(property("name", KtExpr.Name("String"), initializer = KtExpr.Literal("\"a\""))),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            """
            package com.example.app

            public class Models {
                public class User {
                    public val name: String = "a"
                }
            }

            """.trimIndent(),
            printed,
        )
    }

    @Test
    fun `a local property and an assignment print without a visibility modifier`() {
        val printed = KtPrinter().print(
            ktFile(
                KtDeclaration.Function(
                    "draw",
                    emptyList(),
                    emptyList(),
                    listOf(
                        KtStmt.LocalProperty("count", KtExpr.Name("Int"), true, KtExpr.Literal("0"), null),
                        KtStmt.Assign(KtExpr.Name("count"), KtExpr.Literal("1")),
                    ),
                ),
            ),
        )

        assertEquals(
            """
            package com.example.app

            public fun draw() {
                var count: Int = 0
                count = 1
            }

            """.trimIndent(),
            printed,
        )
    }

    @Test
    fun `a delegate prints after by and a type may be omitted`() {
        val delegate = KtExpr.Call(KtExpr.Name("lazy"), listOf(KtArg(null, KtExpr.Literal("\"0\""))))
        val printed = KtPrinter().print(ktFile(property("lazyCount", delegate = delegate)))

        assertEquals(
            """
            package com.example.app

            public val lazyCount by lazy("0")

            """.trimIndent(),
            printed,
        )
    }

    @Test
    fun `a declared class name beats a conflicting import`() {
        val drawn = KtDeclaration.Function("Draw", emptyList(), emptyList(), listOf(KtStmt.Expr(badgeCall())))
        val printed = KtPrinter().print(
            ktFile(KtDeclaration.Class("Badge", emptyList(), emptyList()), drawn),
        )

        // The class contributes the declared name. Before the split only a function did, so
        // the import would have stayed plain and the call would have read `Badge()`.
        assertEquals(
            """
            package com.example.app

            ${"import"} androidx.compose.material.Badge as MaterialBadge

            public class Badge {}

            public fun Draw() {
                MaterialBadge()
            }

            """.trimIndent(),
            printed,
        )
    }

    @Test
    fun `a declared property name beats a conflicting import`() {
        val drawn = KtDeclaration.Function("Draw", emptyList(), emptyList(), listOf(KtStmt.Expr(badgeCall())))
        val printed = KtPrinter().print(ktFile(property("Badge", KtExpr.Name("String")), drawn))

        assertEquals(
            """
            package com.example.app

            ${"import"} androidx.compose.material.Badge as MaterialBadge

            public val Badge: String

            public fun Draw() {
                MaterialBadge()
            }

            """.trimIndent(),
            printed,
        )
    }

    @Test
    fun `a property's type, initializer and getter all record their imports`() {
        val printed = KtPrinter().print(
            ktFile(
                KtDeclaration.Class(
                    "HomeState",
                    emptyList(),
                    listOf(
                        property(
                            "tint",
                            reference("com.example.ui", "Tint"),
                            initializer = reference("com.example.ui", "DefaultTint"),
                            getter = KtExpr.Call(
                                KtExpr.Member(reference("com.example.ui", "Tokens"), "brand"),
                                emptyList(),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // Three symbols in three fields of one member property, through the class walk: a
        // path collectSymbols does not reach is an import the emitted file needs and misses.
        assertEquals(
            """
            package com.example.app

            import com.example.ui.DefaultTint
            import com.example.ui.Tint
            import com.example.ui.Tokens

            public class HomeState {
                public val tint: Tint = DefaultTint
                    get() = Tokens.brand()
            }

            """.trimIndent(),
            printed,
        )
    }

    @Test
    fun `a compose type on a property records its import`() {
        val tint = reference("androidx.compose.ui.graphics", "Color")
        val printed = KtPrinter().print(ktFile(property("tint", tint)))

        assertTrue(printed.contains("${"import"} androidx.compose.ui.graphics.Color"), printed)
        assertTrue(printed.contains("public val tint: Color"), printed)
    }

    @Test
    fun `an annotation prints above the declaration it belongs to`() {
        val annotated = KtDeclaration.Property(
            "count",
            listOf(KotlinSymbol("kotlin.jvm", "JvmField")),
            KtExpr.Name("Int"),
            true,
            KtExpr.Literal("0"),
            null,
            null,
        )
        val printed = KtPrinter().print(
            ktFile(KtDeclaration.Class("HomeState", emptyList(), listOf(annotated))),
        )

        assertEquals(
            """
            package com.example.app

            import kotlin.jvm.JvmField

            public class HomeState {
                @JvmField
                public var count: Int = 0
            }

            """.trimIndent(),
            printed,
        )
    }

    @Test
    fun `a property with an initializer and a delegate is refused`() {
        val broken = property(
            "count",
            KtExpr.Name("Int"),
            mutable = true,
            initializer = KtExpr.Literal("0"),
            delegate = KtExpr.Name("lazy"),
        )

        val failure = assertFailsWith<CodegenBug> { KtPrinter().print(ktFile(broken)) }

        assertEquals(
            "Property 'count' has an initializer and a delegate; Kotlin allows one",
            failure.message,
        )
    }

    @Test
    fun `a local with an initializer and a delegate is refused`() {
        val broken = KtStmt.LocalProperty(
            "count",
            KtExpr.Name("Int"),
            true,
            KtExpr.Literal("0"),
            KtExpr.Name("lazy"),
        )
        val body = KtDeclaration.Function("draw", emptyList(), emptyList(), listOf(broken))

        val failure = assertFailsWith<CodegenBug> { KtPrinter().print(ktFile(body)) }

        assertEquals(
            "Local 'count' has an initializer and a delegate; Kotlin allows one",
            failure.message,
        )
    }

    @Test
    fun `a binary operator parenthesises a right operand that binds as loosely as itself`() {
        // `a - (b - c)` and `(a - b) - c` are different trees, so the right operand of a
        // left-associative operator asks for one level more than the left one does.
        val right = KtExpr.Binary(
            KtOp.Sub,
            KtExpr.Name("a"),
            KtExpr.Binary(KtOp.Sub, KtExpr.Name("b"), KtExpr.Name("c")),
        )
        val left = KtExpr.Binary(
            KtOp.Sub,
            KtExpr.Binary(KtOp.Sub, KtExpr.Name("a"), KtExpr.Name("b")),
            KtExpr.Name("c"),
        )
        val tighter = KtExpr.Binary(
            KtOp.Add,
            KtExpr.Name("a"),
            KtExpr.Binary(KtOp.Mul, KtExpr.Name("b"), KtExpr.Name("c")),
        )

        assertTrue(KtPrinter().print(ktFile(drawn(right))).contains("a - (b - c)"))
        assertTrue(KtPrinter().print(ktFile(drawn(left))).contains("a - b - c"))
        assertTrue(KtPrinter().print(ktFile(drawn(tighter))).contains("a + b * c"))
    }

    @Test
    fun `a comparison template parenthesises only where its level binds tighter`() {
        val negated = KtPrinter().print(
            ktFile(drawn(KtExpr.Unary(KtOp.Not, isNullOf("a")))),
        )
        val conjunction = KtPrinter().print(
            ktFile(drawn(KtExpr.Binary(KtOp.And, isNullOf("a"), isNullOf("b")))),
        )
        val argument = KtPrinter().print(
            ktFile(drawn(KtExpr.Call(KtExpr.Name("check"), listOf(KtArg(null, isNullOf("a")))))),
        )

        assertTrue(negated.contains("!(a == null)"), negated)
        assertTrue(conjunction.contains("a == null && b == null"), conjunction)
        assertTrue(argument.contains("check(a == null)"), argument)
    }

    @Test
    fun `a pattern binds exactly the arguments it names`() {
        val failure = assertFailsWith<CodegenBug> {
            KtPrinter().print(ktFile(drawn(KtExpr.PatternCall("{0} == {1}", listOf(KtExpr.Name("a"))))))
        }

        assertEquals(
            "Pattern '{0} == {1}' binds arguments [0, 1] and the call carries [0]",
            failure.message,
        )
    }

    @Test
    fun `a string template refuses an interpolation that spans lines`() {
        // A literal cannot hold a newline, and §16.3 forbids reflowing one to make room.
        val expanded = KtExpr.Call(
            KtExpr.Name("row"),
            listOf(KtArg(null, KtExpr.Name("a")), KtArg(null, KtExpr.Name("b"))),
        )
        val over = KtExpr.StringTemplate(listOf(KtTemplatePart.Interpolation(expanded)))

        val failure = assertFailsWith<CodegenBug> { KtPrinter().print(ktFile(drawn(over))) }

        assertTrue(
            checkNotNull(failure.message).startsWith("A string template cannot hold an expression over lines:"),
            failure.message,
        )
    }

    /** `name == null` as the `core.isNull` template spells it: a comparison, not a call. */
    private fun isNullOf(name: String): KtExpr = KtExpr.PatternCall(
        pattern = "{0} == null",
        args = listOf(KtExpr.Name(name)),
        precedence = FunctionPrecedence.Comparison,
    )

    /** A function whose body is one expression, which is where precedence shows up. */
    private fun drawn(value: KtExpr): KtDeclaration.Function =
        KtDeclaration.Function("value", emptyList(), emptyList(), listOf(KtStmt.Expr(value)))

    private fun ktFile(vararg declarations: KtDeclaration): KtFile =
        KtFile("com.example.app", null, declarations.toList())

    private fun property(
        name: String,
        type: KtExpr? = null,
        mutable: Boolean = false,
        initializer: KtExpr? = null,
        delegate: KtExpr? = null,
        getter: KtExpr? = null,
    ): KtDeclaration.Property =
        KtDeclaration.Property(name, emptyList(), type, mutable, initializer, delegate, getter)

    private fun reference(packageName: String, name: String): KtExpr =
        KtExpr.Ref(KtSymbolRef(KotlinSymbol(packageName, name)))

    /** A call to the library's `Badge`, so a declared `Badge` collides with it. */
    private fun badgeCall(): KtExpr = KtExpr.Call(reference("androidx.compose.material", "Badge"), emptyList())
}
