package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.analysis.resolved.ResolvedState
import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.interpreter.StateStore
import dev.rotalex.lutter.interpreter.eval.Evaluator
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §12.1's read scopes: the page store answers before the app store, and a derived declaration is
 * computed on every read rather than looked up.
 */
class ScreenEvalScopeTest {

    private val count: StateId = StateId("s_count")
    private val doubled: StateId = StateId("s_doubled")

    @Test
    fun `a held page declaration resolves`() {
        val scope = scopeOf(page = store(count to Value.Int32(3)))

        assertEquals(Value.Int32(3), scope.read(RefTarget.State(count)))
    }

    @Test
    fun `a held app declaration resolves through the environment's store`() {
        val scope = scopeOf(app = store(count to Value.Int32(3)))

        assertEquals(Value.Int32(3), scope.read(RefTarget.State(count)))
    }

    @Test
    fun `the page store answers before the app store for one id`() {
        val scope = scopeOf(store(count to Value.Int32(3)), store(count to Value.Int32(9)))

        assertEquals(Value.Int32(3), scope.read(RefTarget.State(count)))
    }

    @Test
    fun `a derived declaration is computed, not stored`() {
        val page = store(count to Value.Int32(3))
        val scope = scopeOf(page, declarations = listOf(doubling()))

        assertEquals(Value.Int32(4), scope.read(RefTarget.State(doubled)))
        assertNull(page.get(doubled))
    }

    @Test
    fun `a derived read reflects the write it depends on`() {
        val page = store(count to Value.Int32(3))
        val scope = scopeOf(page, declarations = listOf(doubling()))
        page.set(count, Value.Int32(10))

        assertEquals(Value.Int32(11), scope.read(RefTarget.State(doubled)))
    }

    @Test
    fun `a derived declaration in the app scope is computed too`() {
        val scope = scopeOf(
            app = store(count to Value.Int32(5)),
            declarations = listOf(doubling()),
        )

        assertEquals(Value.Int32(6), scope.read(RefTarget.State(doubled)))
    }

    @Test
    fun `a seeded param resolves`() {
        val scope = scopeOf(params = mapOf(ParamName("title") to Value.Str("Hi")))

        assertEquals(Value.Str("Hi"), scope.read(RefTarget.Param(ParamName("title"))))
    }

    @Test
    fun `an unknown param refuses naming it`() {
        val scope = scopeOf()

        val failure = assertFailsWith<IllegalStateException> {
            scope.read(RefTarget.Param(ParamName("title")))
        }
        assertTrue(failure.message?.contains("title") == true, failure.message)
    }

    @Test
    fun `an event arg refuses outside a handler`() {
        val failure = assertFailsWith<IllegalStateException> {
            scopeOf().read(RefTarget.EventArg("value"))
        }
        assertTrue(failure.message?.contains("Phase 7") == true, failure.message)
    }

    @Test
    fun `an iteration variable refuses as post-MVP`() {
        val failure = assertFailsWith<IllegalStateException> {
            scopeOf().read(RefTarget.Item("item"))
        }
        assertTrue(failure.message?.contains("post-MVP") == true, failure.message)
    }

    @Test
    fun `a declaration nothing holds refuses rather than answering null`() {
        val failure = assertFailsWith<IllegalStateException> {
            scopeOf().read(RefTarget.State(count))
        }
        assertTrue(failure.message?.contains("s_count") == true, failure.message)
    }

    private fun scopeOf(
        page: StateStore = SnapshotStateStore.Empty,
        app: StateStore = SnapshotStateStore.Empty,
        params: Map<ParamName, Value> = emptyMap(),
        declarations: List<ResolvedState> = emptyList(),
    ): EvalScope = ScreenEvalScope(declarations, page, app, params, Evaluator())

    private fun store(vararg slots: Pair<StateId, Value>): SnapshotStateStore =
        SnapshotStateStore.seeded(
            slots.map { (id, value) ->
                ResolvedState(StateDecl(id = id, name = id.value, type = TypeRef.Int32, initial = value), null)
            },
        )

    /** A derived declaration whose body is `s_count + 1`. */
    private fun doubling(): ResolvedState {
        val target = RefTarget.State(count)
        val body = Expr.Binary(BinaryOp.Add, Expr.Ref(target), Expr.Const(Value.Int32(1)))
        val typed = TypedExpr(body, ExprType.Of(TypeRef.Int32), setOf(target))
        return ResolvedState(
            StateDecl(id = doubled, name = "doubled", type = TypeRef.Int32, derived = body),
            typed,
        )
    }
}
