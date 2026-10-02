package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.resolved.PropOrigin
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedProp
import dev.rotalex.lutter.model.doc.DataModelDecl
import dev.rotalex.lutter.model.doc.FieldDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.PageScope
import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.expr.UnaryOp
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.kind.ValueKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 5: §10.4's five rules, each with the case it accepts and the case it refuses.
 *
 * The refusals are the substance here. §10.4's rows are about what a document may not say, so
 * a suite holding only the accepted cases would prove the checker compiles.
 */
class ExpressionPassTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())
    private val nullableAnalyzer: Analyzer<String, String, String> = Analyzer(nullableSchema())
    private val callAnalyzer: Analyzer<String, FunctionSpec, String> = Analyzer(functionSchema())
    private val pageId: PageId = PageId("p_home")

    private fun codesOf(block: PageScope.() -> Unit): List<String> =
        analyzer.analyze(homeDocument(block)).diagnostics.map { it.code.value }

    private fun nullableCodesOf(block: PageScope.() -> Unit): List<String> =
        nullableAnalyzer.analyze(homeDocument(block)).diagnostics.map { it.code.value }

    private fun callCodesOf(block: PageScope.() -> Unit): List<String> =
        callAnalyzer.analyze(homeDocument(block)).diagnostics.map { it.code.value }

    /** The findings pass 5 emitted, apart from the other passes' codes. */
    private fun expressionFindings(document: UiDocument): List<Diagnostic> =
        analyzer.analyze(document).diagnostics.filter { it.code.value.startsWith(EXPRESSION) }

    /** The resolved property [key] of the page's single node. */
    private fun resolvedProp(resolved: ResolvedDocument?, key: String): ResolvedProp? =
        resolved?.pages?.get(pageId)?.root?.props?.get(PropertyKey(key))

    private val userModel: DataModelDecl = DataModelDecl(
        id = DataModelId(USER),
        name = USER,
        fields = listOf(FieldDecl(PropertyKey("name"), TypeRef.Str)),
    )

    private fun withUser(document: UiDocument): UiDocument =
        document.copy(dataModels = mapOf(DataModelId(USER) to userModel))

    private fun withAppState(document: UiDocument, vararg state: StateDecl): UiDocument =
        document.copy(appState = state.toList())

    private val userState: StateDecl = StateDecl(
        id = StateId(USER_STATE),
        name = "user",
        type = TypeRef.Nullable(TypeRef.Object(TypeId(USER))),
        initial = Value.Null,
    )

    private val maxState: StateDecl = StateDecl(
        id = StateId(MAX_STATE),
        name = "max",
        type = TypeRef.Int32,
        initial = Value.Int32(3),
    )

    private fun i32(value: Int): Expr = Expr.Const(Value.Int32(value))

    private fun i64(value: Long): Expr = Expr.Const(Value.Int64(value))

    private fun str(value: String): Expr = Expr.Const(Value.Str(value))

    private fun bool(value: Boolean): Expr = Expr.Const(Value.Bool(value))

    /** A nullable `User` reached through app state: the receiver `safe` exists for. */
    private fun userRef(): Expr = Expr.Ref(RefTarget.State(StateId(USER_STATE)))

    private fun userValue(): Expr = Expr.Const(Value.Obj(TypeId(USER), emptyMap()))

    // ---------------------------------------------------------------------------------
    // §10.4: bidirectional check against the declared type
    // ---------------------------------------------------------------------------------

    @Test
    fun `an expression filling the declared type is accepted and attached`() {
        val expression = Expr.Binary(BinaryOp.Add, i32(1), i32(2))

        val result = analyzer.analyze(
            homeDocument {
                node(TextType) {
                    prop("text", Value.Str("Hi"))
                    computed("maxLines", expression)
                }
            },
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val typed = assertNotNull(assertNotNull(resolvedProp(result.resolved, "maxLines")).typed)
        assertEquals(ExprType.Of(TypeRef.Int32), typed.type)
        assertEquals(expression, typed.expr, "the resolved tree keeps the expression as written")
        assertEquals(emptySet(), typed.refs, "a literal names nothing")
    }

    @Test
    fun `an expression not filling the declared type reports type mismatch`() {
        val codes = codesOf {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                computed("maxLines", str("not a number"))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ExprTypeMismatch.value), "got $codes")
    }

    // ---------------------------------------------------------------------------------
    // §10.4: no implicit numeric conversion
    // ---------------------------------------------------------------------------------

    @Test
    fun `two operands of one numeric type are accepted`() {
        val result = analyzer.analyze(
            homeDocument {
                node(TextType) {
                    prop("text", Value.Str("Hi"))
                    computed("maxLines", Expr.Binary(BinaryOp.Mul, i32(2), i32(3)))
                }
            },
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val typed = assertNotNull(assertNotNull(resolvedProp(result.resolved, "maxLines")).typed)
        assertEquals(ExprType.Of(TypeRef.Int32), typed.type)
    }

    @Test
    fun `adding a float to an int names the num toDouble conversion as the way out`() {
        val result = analyzer.analyze(
            homeDocument {
                node(TextType) {
                    prop("text", Value.Str("Hi"))
                    computed("maxLines", Expr.Binary(BinaryOp.Add, i32(1), Expr.Const(Value.Float64(1.5))))
                }
            },
        )

        val finding = result.diagnostics.single { it.code == DiagnosticCodes.ExprTypeMismatch }
        val message = finding.message
        assertTrue(message.contains("num.toDouble") == true, message)
        assertTrue(message.contains("i32") && message.contains("f64"), message)
    }

    // ---------------------------------------------------------------------------------
    // §10.4: nullability is tracked, and a nullable receiver requires safe access
    // ---------------------------------------------------------------------------------

    @Test
    fun `a field read on a non-null receiver needs no safe access`() {
        val result = nullableAnalyzer.analyze(
            withUser(
                homeDocument {
                    node(BadgeType) { computed("label", Expr.Member(userValue(), "name")) }
                },
            ),
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val typed = assertNotNull(assertNotNull(resolvedProp(result.resolved, "label")).typed)
        assertEquals(ExprType.Of(TypeRef.Str), typed.type)
    }

    @Test
    fun `a field read on a nullable receiver needs safe access`() {
        val result = nullableAnalyzer.analyze(
            withUser(
                homeDocument {
                    node(BadgeType) {
                        computed("label", Expr.Member(userRef(), "name", safe = false))
                    }
                },
            ),
        )

        val finding = result.diagnostics.single { it.code == DiagnosticCodes.ExprNullableAccess }
        val message = finding.message
        assertTrue(message.contains("needs safe access") == true, message)
    }

    @Test
    fun `a safe read on a nullable receiver yields a nullable field`() {
        val result = nullableAnalyzer.analyze(
            withUser(
                withAppState(
                    homeDocument {
                        node(BadgeType) {
                            prop("label", Value.Str("Hi"))
                            computed("caption", Expr.Member(userRef(), "name", safe = true))
                        }
                    },
                    userState,
                ),
            ),
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val typed = assertNotNull(assertNotNull(resolvedProp(result.resolved, "caption")).typed)
        assertEquals(ExprType.Of(TypeRef.Nullable(TypeRef.Str)), typed.type)
    }

    @Test
    fun `a literal null fills a nullable destination and no other`() {
        val accepted = nullableAnalyzer.analyze(
            homeDocument {
                node(BadgeType) {
                    prop("label", Value.Str("Hi"))
                    computed("caption", Expr.Const(Value.Null))
                }
            },
        )
        val refused = nullableCodesOf {
            node(BadgeType) {
                computed("caption", Expr.Const(Value.Null))
                computed("label", Expr.Const(Value.Null))
            }
        }

        assertTrue(accepted.diagnostics.isEmpty(), "got ${accepted.diagnostics}")
        val typed = assertNotNull(assertNotNull(resolvedProp(accepted.resolved, "caption")).typed)
        assertEquals(ExprType.Null, typed.type, "§5.4: null has no TypeRef of its own")
        assertTrue(refused.contains(DiagnosticCodes.ExprTypeMismatch.value), "got $refused")
    }

    @Test
    fun `a member read of null reports nullable access`() {
        val codes = nullableCodesOf {
            node(BadgeType) {
                computed("label", Expr.Member(Expr.Const(Value.Null), "name", safe = true))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ExprNullableAccess.value), "got $codes")
    }

    // ---------------------------------------------------------------------------------
    // §10.4: template parts are Str, Int32, Int64 and Bool
    // ---------------------------------------------------------------------------------

    @Test
    fun `the four permitted template parts are accepted`() {
        val result = analyzer.analyze(
            homeDocument {
                node(TextType) {
                    computed("text", Expr.Template(listOf(str("Hi "), i32(1), i64(2L), bool(true))))
                }
            },
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val typed = assertNotNull(assertNotNull(resolvedProp(result.resolved, "text")).typed)
        assertEquals(ExprType.Of(TypeRef.Str), typed.type)
    }

    @Test
    fun `a float template part names the num format function`() {
        val result = analyzer.analyze(
            homeDocument {
                node(TextType) {
                    computed("text", Expr.Template(listOf(str("Hi "), Expr.Const(Value.Float64(1.5)))))
                }
            },
        )

        val finding = result.diagnostics.single { it.code == DiagnosticCodes.ExprTypeMismatch }
        val message = finding.message
        assertTrue(message.contains("num.format") == true, message)
    }

    @Test
    fun `a template part outside the four reports the four`() {
        val result = analyzer.analyze(
            homeDocument {
                node(TextType) {
                    computed("text", Expr.Template(listOf(str("Hi "), Expr.Const(Value.Dp(8f)))))
                }
            },
        )

        val finding = result.diagnostics.single { it.code == DiagnosticCodes.ExprTypeMismatch }
        val message = finding.message
        assertTrue(message.contains("str, i32, i64 or bool") == true, message)
    }

    // ---------------------------------------------------------------------------------
    // §10.4: unknown functions and unresolved refs
    // ---------------------------------------------------------------------------------

    @Test
    fun `a call with no spec reports unknown function`() {
        val result = analyzer.analyze(
            homeDocument {
                node(TextType) {
                    prop("text", Value.Str("Hi"))
                    computed("maxLines", Expr.Call(UpperId, listOf(i32(1))))
                }
            },
        )

        val finding = result.diagnostics.single { it.code == DiagnosticCodes.ExprUnknownFunction }
        val message = finding.message
        assertTrue(message.contains("str.upper") == true, message)
        assertEquals("str.upper", finding.args["function"], finding.args.toString())
    }

    @Test
    fun `a call typed by its spec is accepted and attached`() {
        val result = callAnalyzer.analyze(
            homeDocument {
                node(TextType) { computed("text", Expr.Call(UpperId, listOf(str("hi")))) }
            },
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val typed = assertNotNull(assertNotNull(resolvedProp(result.resolved, "text")).typed)
        assertEquals(ExprType.Of(TypeRef.Str), typed.type)
    }

    @Test
    fun `an element variable in a signature is bound from the argument`() {
        val result = callAnalyzer.analyze(
            homeDocument {
                node(TextType) {
                    computed(
                        "text",
                        Expr.Call(FirstId, listOf(Expr.ListLiteral(listOf(str("a"), str("b"))))),
                    )
                }
            },
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val typed = assertNotNull(assertNotNull(resolvedProp(result.resolved, "text")).typed)
        assertEquals(ExprType.Of(TypeRef.Str), typed.type, "§10.3: the result instantiates the element")
    }

    @Test
    fun `a call passing the wrong number of arguments reports type mismatch`() {
        val codes = callCodesOf {
            node(TextType) { computed("text", Expr.Call(UpperId, emptyList())) }
        }

        assertTrue(codes.contains(DiagnosticCodes.ExprTypeMismatch.value), "got $codes")
    }

    @Test
    fun `a call passing the wrong argument type reports type mismatch`() {
        val codes = callCodesOf {
            node(TextType) { computed("text", Expr.Call(UpperId, listOf(i32(1)))) }
        }

        assertTrue(codes.contains(DiagnosticCodes.ExprTypeMismatch.value), "got $codes")
    }

    @Test
    fun `a state in scope resolves and is recorded in refs`() {
        val result = analyzer.analyze(
            withAppState(
                homeDocument {
                    node(TextType) {
                        prop("text", Value.Str("Hi"))
                        computed("maxLines", Expr.Ref(RefTarget.State(StateId(MAX_STATE))))
                    }
                },
                maxState,
            ),
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val typed = assertNotNull(assertNotNull(resolvedProp(result.resolved, "maxLines")).typed)
        assertEquals(ExprType.Of(TypeRef.Int32), typed.type)
        assertEquals(setOf(RefTarget.State(StateId(MAX_STATE))), typed.refs, "§17.1: scope resolution")
    }

    @Test
    fun `a state outside scope reports unresolved ref`() {
        val codes = codesOf {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                computed("maxLines", Expr.Ref(RefTarget.State(StateId("s_ghost"))))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ExprUnresolvedRef.value), "got $codes")
    }

    @Test
    fun `an event argument in a property position reports unresolved ref`() {
        // §11.2 binds an event argument and §10.1 marks an item wave 2, so neither is in scope
        // where a property can see it. Pass 6's own scope is where they become nameable.
        val codes = codesOf {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                computed("maxLines", Expr.Ref(RefTarget.EventArg("value")))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ExprUnresolvedRef.value), "got $codes")
    }

    // ---------------------------------------------------------------------------------
    // §10.4:856 — structured diagnostics carrying the node and the property
    // ---------------------------------------------------------------------------------

    @Test
    fun `every expression diagnostic names the node and the property`() {
        val document = homeDocument {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                computed("maxLines", Expr.Binary(BinaryOp.Add, i32(1), Expr.Const(Value.Float64(1.5))))
                computed("align", Expr.Ref(RefTarget.State(StateId("s_ghost"))))
            }
        }

        val findings = expressionFindings(document)

        assertTrue(findings.size >= 2, "expected one finding per computed property: $findings")
        for (finding in findings) {
            val message = finding.message
            assertEquals(NodeId("n_1"), finding.location.nodeId, message)
            val property = assertNotNull(finding.location.property, message)
            assertEquals(property.value, finding.args["property"], message)
            assertEquals("n_1", finding.args["node"], message)
        }
    }

    // ---------------------------------------------------------------------------------
    // The product: a checked expression on a computed property, and nothing on a constant
    // ---------------------------------------------------------------------------------

    @Test
    fun `a computed property carries the checked expression and a constant carries none`() {
        val result = analyzer.analyze(
            homeDocument {
                node(TextType) {
                    computed("text", Expr.Template(listOf(str("Hi"))))
                    prop("maxLines", Value.Int32(3))
                }
            },
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val text = assertNotNull(resolvedProp(result.resolved, "text"))
        val maxLines = assertNotNull(resolvedProp(result.resolved, "maxLines"))

        assertEquals(ExprType.Of(TypeRef.Str), assertNotNull(text.typed).type)
        assertEquals(PropOrigin.Specified, text.origin)
        assertNull(maxLines.typed, "a constant has no expression to check")
        assertEquals(PropertyValue.Const(Value.Int32(3)), maxLines.value)
        assertEquals(PropOrigin.Specified, maxLines.origin)
    }

    @Test
    fun `a defaulted property is still resolved and still carries no typed expression`() {
        val result = analyzer.analyze(
            homeDocument {
                node(ColumnType) {
                    val title = node(TextType) { prop("text", Value.Str("Hi")) }
                    slot("children", listOf(title))
                }
            },
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val spacing = assertNotNull(resolvedProp(result.resolved, "spacing"))

        assertEquals(PropOrigin.Default, spacing.origin)
        assertNull(spacing.typed, "a default is a constant the checker never saw")
    }

    // ---------------------------------------------------------------------------------
    // The operator rules the interpreter's tables have to agree with
    // ---------------------------------------------------------------------------------

    @Test
    fun `if branches of one type are accepted and branches that disagree are not`() {
        val accepted = analyzer.analyze(
            homeDocument {
                node(TextType) {
                    prop("text", Value.Str("Hi"))
                    computed("maxLines", Expr.If(bool(true), i32(1), i32(2)))
                }
            },
        )
        val refused = codesOf {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                computed("maxLines", Expr.If(bool(true), i32(1), str("x")))
            }
        }

        assertTrue(accepted.diagnostics.isEmpty(), "got ${accepted.diagnostics}")
        val typed = assertNotNull(assertNotNull(resolvedProp(accepted.resolved, "maxLines")).typed)
        assertEquals(ExprType.Of(TypeRef.Int32), typed.type)
        assertTrue(refused.contains(DiagnosticCodes.ExprTypeMismatch.value), "got $refused")
    }

    @Test
    fun `a non-bool condition is refused whatever the branches say`() {
        val codes = codesOf {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                computed("maxLines", Expr.If(i32(1), i32(1), i32(2)))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ExprTypeMismatch.value), "got $codes")
    }

    @Test
    fun `a unary operand of the wrong kind reports type mismatch`() {
        val notRefused = codesOf {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                computed("maxLines", Expr.Unary(UnaryOp.Not, str("x")))
            }
        }
        val negAccepted = analyzer.analyze(
            homeDocument {
                node(TextType) {
                    prop("text", Value.Str("Hi"))
                    computed("maxLines", Expr.Unary(UnaryOp.Neg, i32(1)))
                }
            },
        )
        val negRefused = codesOf {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                computed("maxLines", Expr.Unary(UnaryOp.Neg, str("x")))
            }
        }

        assertTrue(notRefused.contains(DiagnosticCodes.ExprTypeMismatch.value), "got $notRefused")
        assertTrue(negAccepted.diagnostics.isEmpty(), "got ${negAccepted.diagnostics}")
        assertTrue(negRefused.contains(DiagnosticCodes.ExprTypeMismatch.value), "got $negRefused")
    }

    @Test
    fun `a list literal whose items disagree reports type mismatch`() {
        val codes = codesOf {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                computed("maxLines", Expr.ListLiteral(listOf(i32(1), str("x"))))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.ExprTypeMismatch.value), "got $codes")
    }

    // ---------------------------------------------------------------------------------
    // The two halves, checked from this side
    // ---------------------------------------------------------------------------------

    @Test
    fun `the settled type is the kind the value the evaluator produces satisfies`() {
        val result = analyzer.analyze(
            homeDocument {
                node(TextType) {
                    computed("text", Expr.Template(listOf(str("Hi "), i32(1))))
                    computed("maxLines", Expr.Binary(BinaryOp.Add, i32(1), i32(2)))
                }
            },
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        // §23.3 forbids this module reaching the interpreter, so the pairing cannot be one
        // test: `EvaluatorTest` asserts the same pairs from the value side. `ValueKinds` is
        // the one table both halves read, which is what makes `accepts` a statement about
        // the checker and the evaluator at once rather than two lists that agree today.
        val pairs = mapOf(
            "text" to Value.Str("Hi 1"),
            "maxLines" to Value.Int32(3),
        )
        for ((key, value) in pairs) {
            val typed = assertNotNull(assertNotNull(resolvedProp(result.resolved, key)).typed, key)
            val declared = (typed.type as ExprType.Of).type
            assertTrue(
                ValueKinds.kindFor(declared).accepts(value),
                "checker settled '$declared' for '$key', and a $value is not a value of it",
            )
        }
    }
}

/** The prefix every pass 5 code shares, so the other passes' findings can be told apart. */
private const val EXPRESSION: String = "expr."

private const val USER: String = "User"
private const val USER_STATE: String = "s_user"
private const val MAX_STATE: String = "s_max"
