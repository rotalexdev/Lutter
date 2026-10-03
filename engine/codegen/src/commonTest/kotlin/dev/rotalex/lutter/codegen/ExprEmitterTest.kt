package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.expr.UnaryOp
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.function.FunctionEmit
import dev.rotalex.lutter.schema.function.FunctionPrecedence
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.function.ParamSig
import dev.rotalex.lutter.schema.function.TypeSig
import dev.rotalex.lutter.schema.registry.Registry
import dev.rotalex.lutter.schema.registry.RegistryBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * §10.5's codegen column, end to end: a document's expression in, the printed Kotlin out.
 *
 * Every case is a hand-built tree over a hand-built registry — no schema, no analyzer, no
 * builtins — because the two facts under test are the emitter's spelling and the printer's
 * parentheses, and both are decided from the IR alone. `body` is the printed expression with the
 * surrounding file stripped; two cases assert the whole file, because an import is only proof of
 * anything when it is on the page.
 */
class ExprEmitterTest {

    private val isNull: FunctionSpec = FunctionSpec(
        id = FunctionId("core.isNull"),
        params = listOf(ParamSig("value", TypeSig.Nullable(TypeSig.Element("T")))),
        returns = TypeSig.Exact(TypeRef.Bool),
        // §12.2's own words, not a call: `==` binds looser than any call does.
        kotlin = FunctionEmit("{0} == null", precedence = FunctionPrecedence.Comparison),
    )

    private val coalesce: FunctionSpec = FunctionSpec(
        id = FunctionId("core.coalesce"),
        params = listOf(
            ParamSig("value", TypeSig.Nullable(TypeSig.Element("T"))),
            ParamSig("fallback", TypeSig.Element("T")),
        ),
        returns = TypeSig.Element("T"),
        kotlin = FunctionEmit("kotlin.coalesce({0}, {1})"),
    )

    private val trim: FunctionSpec = FunctionSpec(
        id = FunctionId("str.trim"),
        params = listOf(ParamSig("text", TypeSig.Exact(TypeRef.Str))),
        returns = TypeSig.Exact(TypeRef.Str),
        kotlin = FunctionEmit(
            pattern = "TextUtils.trim({0})",
            imports = listOf(KotlinSymbol("com.example.text", "TextUtils")),
        ),
    )

    private val size: FunctionSpec = FunctionSpec(
        id = FunctionId("list.size"),
        params = listOf(ParamSig("list", TypeSig.ListOf(TypeSig.Element("T")))),
        returns = TypeSig.Exact(TypeRef.Int32),
        kotlin = FunctionEmit("{0}.size", precedence = FunctionPrecedence.Atom),
    )

    private val functions: Registry<FunctionId, FunctionSpec> =
        RegistryBuilder<FunctionId, FunctionSpec>()
            .apply {
                register(isNull.id, isNull)
                register(coalesce.id, coalesce)
                register(trim.id, trim)
                register(size.id, size)
            }
            .build()

    private val emitter: ExprEmitter = ExprEmitter(functions, StateRead { id -> KtExpr.Name(id.value) })

    @Test
    fun `a constant is an escaped literal`() {
        assertEquals("\"a\\\"b\"", body(emit(Expr.Const(Value.Str("a\"b")))))
    }

    @Test
    fun `a compose constant reaches the file with the import it needs`() {
        val printed = printed(emit(Expr.Const(Value.Dp(8f))))

        assertEquals(
            """
            package com.example.app

            ${"import"} androidx.compose.ui.unit.dp

            public fun value() {
                8.0.dp
            }

            """.trimIndent(),
            printed,
        )
    }

    @Test
    fun `a parameter reference is its own name`() {
        assertEquals("title", body(emit(Expr.Ref(RefTarget.Param(ParamName("title"))))))
    }

    @Test
    fun `an event argument is its own name`() {
        assertEquals("amount", body(emit(Expr.Ref(RefTarget.EventArg("amount")))))
    }

    @Test
    fun `a member read is safe when the document said so`() {
        val unsafe = Expr.Member(Expr.Ref(RefTarget.Param(ParamName("user"))), "name", safe = false)
        val safe = Expr.Member(Expr.Ref(RefTarget.Param(ParamName("user"))), "name", safe = true)

        assertEquals("user.name", body(emit(unsafe)))
        assertEquals("user?.name", body(emit(safe)))
    }

    @Test
    fun `a binary operator keeps a tighter operand bare and a looser one parenthesised`() {
        val tighter = Expr.Binary(
            BinaryOp.Add,
            Expr.Ref(RefTarget.Param(ParamName("a"))),
            Expr.Binary(
                BinaryOp.Mul,
                Expr.Ref(RefTarget.Param(ParamName("b"))),
                Expr.Ref(RefTarget.Param(ParamName("c"))),
            ),
        )
        val looser = Expr.Binary(
            BinaryOp.Sub,
            Expr.Ref(RefTarget.Param(ParamName("a"))),
            Expr.Binary(
                BinaryOp.Sub,
                Expr.Ref(RefTarget.Param(ParamName("b"))),
                Expr.Ref(RefTarget.Param(ParamName("c"))),
            ),
        )

        assertEquals("a + b * c", body(emit(tighter)))
        assertEquals("a - (b - c)", body(emit(looser)))
    }

    @Test
    fun `a unary operator binds tighter than any binary one`() {
        val negated = Expr.Unary(
            UnaryOp.Neg,
            Expr.Binary(
                BinaryOp.Sub,
                Expr.Ref(RefTarget.Param(ParamName("a"))),
                Expr.Ref(RefTarget.Param(ParamName("b"))),
            ),
        )

        assertEquals("-(a - b)", body(emit(negated)))
    }

    @Test
    fun `an if emits both branches and parenthesises as an operand`() {
        val conditional = Expr.If(
            Expr.Binary(
                BinaryOp.Ge,
                Expr.Ref(RefTarget.Param(ParamName("a"))),
                Expr.Ref(RefTarget.Param(ParamName("b"))),
            ),
            Expr.Ref(RefTarget.Param(ParamName("c"))),
            Expr.Ref(RefTarget.Param(ParamName("d"))),
        )

        assertEquals("if (a >= b) c else d", body(emit(conditional)))
        assertEquals(
            "(if (a >= b) c else d) + e",
            body(emit(Expr.Binary(BinaryOp.Add, conditional, param("e")))),
        )
    }

    @Test
    fun `a string template joins text and interpolations`() {
        val template = Expr.Template(
            listOf(Expr.Const(Value.Str("costs \$5 ")), Expr.Ref(RefTarget.Param(ParamName("total")))),
        )

        // The text part is escaped here and nowhere else: inside a template a bare `$` would
        // start an expression, so the emitter's escape is what keeps the document's own text.
        assertEquals("\"costs \\\$5 \${total}\"", body(emit(template)))
    }

    @Test
    fun `a template part that is not text is interpolated`() {
        val template = Expr.Template(
            listOf(Expr.Const(Value.Str("n=")), Expr.Const(Value.Int32(7))),
        )

        assertEquals("\"n=\${7}\"", body(emit(template)))
    }

    @Test
    fun `a call fills its template positionally and records the template's imports`() {
        val printed = printed(emit(Expr.Call(FunctionId("str.trim"), listOf(param("raw")))))

        assertEquals(
            """
            package com.example.app

            ${"import"} com.example.text.TextUtils

            public fun value() {
                TextUtils.trim(raw)
            }

            """.trimIndent(),
            printed,
        )
    }

    @Test
    fun `every placeholder takes the argument in its own position`() {
        val call = Expr.Call(
            FunctionId("core.coalesce"),
            listOf(param("a"), Expr.Binary(BinaryOp.Add, param("x"), param("y"))),
        )

        // The parentheses around `x + y` are the price of not reading the pattern for a binding
        // level: a `{0}` may be a receiver inside one, and only the pattern knows that.
        assertEquals("kotlin.coalesce(a, (x + y))", body(emit(call)))
    }

    @Test
    fun `a comparison template parenthesises inside a tighter context`() {
        val negated = Expr.Unary(UnaryOp.Not, isNullOf(param("x")))

        assertEquals("!(x == null)", body(emit(negated)))
    }

    @Test
    fun `a comparison template needs no parentheses where its level already holds`() {
        val conjunction = Expr.Binary(BinaryOp.And, isNullOf(param("a")), isNullOf(param("b")))

        assertEquals("a == null && b == null", body(emit(conjunction)))
    }

    @Test
    fun `a member-read template parenthesises an argument that binds looser than postfix`() {
        val call = Expr.Call(
            FunctionId("list.size"),
            listOf(Expr.Binary(BinaryOp.Add, param("x"), param("y"))),
        )

        assertEquals("(x + y).size", body(emit(call)))
    }

    @Test
    fun `a list literal is a call over a default-imported symbol`() {
        val printed = printed(emit(Expr.ListLiteral(listOf(param("a"), param("b")))))

        assertEquals(
            """
            package com.example.app

            public fun value() {
                listOf(
                    a,
                    b,
                )
            }

            """.trimIndent(),
            printed,
        )
    }

    @Test
    fun `the resolved targets beside a tree are never read as expressions`() {
        // `refs` is a set of `RefTarget`, not of `Expr.Ref`. A tree that names nothing still
        // emits, and a tree that names a state emits nothing else for having been checked.
        val checked = TypedExpr(
            Expr.Const(Value.Int32(1)),
            ExprType.Of(TypeRef.Int32),
            setOf(RefTarget.State(StateId("s_count")), RefTarget.Param(ParamName("title"))),
        )

        assertEquals("1", body(emitter.emit(checked)))
    }

    @Test
    fun `a state reference reads through whatever the caller bound`() {
        // §12.1 makes the identifier the document's and §12.2 makes the receiver the strategy's,
        // so the emitter holds neither: the read arrives whole, and this only asserts it is used.
        val screen = ExprEmitter(functions, StateRead { id -> KtExpr.Member(KtExpr.Name("state"), id.value) })

        assertEquals("state.s_count", body(screen.emit(checked(Expr.Ref(RefTarget.State(StateId("s_count")))))))
    }

    @Test
    fun `a state reference no declaration carries is a bug rather than a document fault`() {
        // Pass 5 refuses an unresolved `RefTarget.State` upstream as `expr.unresolved_ref`, so a
        // null read means the binding table lost a declaration — never something a user wrote.
        val blind = ExprEmitter(functions, StateRead { null })

        val failure = assertFailsWith<CodegenBug> {
            blind.emit(checked(Expr.Ref(RefTarget.State(StateId("s_count")))))
        }

        assertEquals(
            "State 's_count' left the binding table; pass 5 refused an unresolved one",
            failure.message,
        )
    }

    @Test
    fun `an item reference is refused while no iteration can bind it`() {
        val failure = assertFailsWith<CodegenBug> { emit(Expr.Ref(RefTarget.Item("row"))) }

        assertEquals(
            "No Kotlin spelling for item 'row': §10.2 has no iteration to bind it",
            failure.message,
        )
    }

    @Test
    fun `an unregistered function is refused by name`() {
        val failure = assertFailsWith<CodegenBug> { emit(Expr.Call(FunctionId("str.nope"), emptyList())) }

        assertEquals("No FunctionSpec for 'str.nope'", failure.message)
    }

    @Test
    fun `a value the type table settles as a symbol is refused by name`() {
        val failure = assertFailsWith<CodegenBug> {
            emit(Expr.Const(Value.Icon("material", "Home")))
        }

        val message: String = checkNotNull(failure.message)
        assertTrue(message.startsWith("No Kotlin literal for "), message)
        assertTrue(message.endsWith("§9.2 settles it as a symbol, not a literal"), message)
    }

    /**
     * [expr] as the emitter receives it.
     *
     * [TypedExpr.type] and [TypedExpr.refs] are placeholders on purpose: §10.4 settled both
     * before emission and nothing here reads them, so a per-case type would assert nothing.
     */
    private fun emit(expr: Expr): KtExpr = emitter.emit(TypedExpr(expr, ExprType.Of(TypeRef.Int32), emptySet()))

    private fun checked(expr: Expr): TypedExpr = TypedExpr(expr, ExprType.Of(TypeRef.Int32), emptySet())

    private fun param(name: String): Expr = Expr.Ref(RefTarget.Param(ParamName(name)))

    private fun isNullOf(argument: Expr): Expr = Expr.Call(FunctionId("core.isNull"), listOf(argument))

    /** [expr] as the whole file the printer writes, imports included. */
    private fun printed(expr: KtExpr): String = KtPrinter().print(
        KtFile(
            "com.example.app",
            null,
            listOf(
                KtDeclaration.Function(
                    "value",
                    emptyList(),
                    null,
                    emptyList(),
                    listOf(KtStmt.Expr(expr)),
                ),
            ),
        ),
    )

    /** The one line [printed] puts inside the body, with the file around it stripped. */
    private fun body(expr: KtExpr): String =
        printed(expr).substringAfter("public fun value() {\n").removeSuffix("}\n").trim()
}
