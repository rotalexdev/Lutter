package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.analysis.resolved.ResolvedState
import dev.rotalex.lutter.interpreter.StateWriter
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.ids.PageId
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
 * §15.3's store: one `mutableStateOf` per slot, a write the next read sees, and no slot at all
 * for a declaration §12.1 computes on read.
 */
class SnapshotStateStoreTest {

    private val count: StateId = StateId("s_count")
    private val doubled: StateId = StateId("s_doubled")

    @Test
    fun `a seeded declaration holds its initial value`() {
        val store = storeOf(held(count, "count", TypeRef.Int32, Value.Int32(3)))

        assertEquals(Value.Int32(3), store.get(count))
    }

    @Test
    fun `a write is what the next read answers`() {
        val store: SnapshotStateStore = storeOf(held(count, "count", TypeRef.Int32, Value.Int32(3)))
        store.set(count, Value.Int32(4))

        assertEquals(Value.Int32(4), store.get(count))
    }

    @Test
    fun `a second write replaces the first`() {
        val store: SnapshotStateStore = storeOf(held(count, "count", TypeRef.Int32, Value.Int32(3)))
        store.set(count, Value.Int32(4))
        store.set(count, Value.Int32(5))

        assertEquals(Value.Int32(5), store.get(count))
    }

    @Test
    fun `a held declaration with no initial holds nothing, so a read is null`() {
        val store = storeOf(held(count, "count", TypeRef.Int32))

        assertNull(store.get(count))
    }

    @Test
    fun `a derived declaration is given no slot`() {
        val store = storeOf(doublingOf(doubled, "doubled", count))

        assertNull(store.get(doubled))
    }

    @Test
    fun `writing a derived declaration refuses naming the rule`() {
        val store: StateWriter = storeOf(doublingOf(doubled, "doubled", count))

        val failure = assertFailsWith<IllegalStateException> { store.set(doubled, Value.Int32(2)) }
        assertTrue(failure.message?.contains("derived") == true, failure.message)
    }

    @Test
    fun `writing an undeclared id refuses rather than minting a slot`() {
        val store: StateWriter = storeOf(held(count, "count", TypeRef.Int32, Value.Int32(3)))

        val failure = assertFailsWith<IllegalStateException> { store.set(doubled, Value.Int32(2)) }
        assertTrue(failure.message?.contains("No state declaration") == true, failure.message)
    }

    @Test
    fun `the empty store holds nothing and refuses every write`() {
        assertNull(SnapshotStateStore.Empty.get(count))
        assertFailsWith<IllegalStateException> { SnapshotStateStore.Empty.set(count, Value.Int32(1)) }
    }

    @Test
    fun `the environment's app state takes this store, the empty marker being gone`() {
        val store = storeOf(held(count, "count", TypeRef.Int32, Value.Int32(3)))

        val environment = RuntimeEnvironment(StubNavigator, appState = store)

        assertEquals(Value.Int32(3), environment.appState.get(count))
    }

    private fun storeOf(vararg declarations: ResolvedState): SnapshotStateStore =
        SnapshotStateStore.seeded(declarations.toList())

    private fun held(
        id: StateId,
        name: String,
        type: TypeRef,
        initial: Value? = null,
    ): ResolvedState = ResolvedState(StateDecl(id = id, name = name, type = type, initial = initial), null)

    /** A derived declaration whose body is `reads + 1`, which is what a read has to compute. */
    private fun doublingOf(id: StateId, name: String, reads: StateId): ResolvedState {
        val target = RefTarget.State(reads)
        val body = Expr.Binary(BinaryOp.Add, Expr.Ref(target), Expr.Const(Value.Int32(1)))
        val typed = TypedExpr(body, ExprType.Of(TypeRef.Int32), setOf(target))
        return ResolvedState(StateDecl(id = id, name = name, type = TypeRef.Int32, derived = body), typed)
    }

    private object StubNavigator : Navigator {
        override fun navigate(page: PageId, args: Map<ParamName, Value>): Unit = Unit
        override fun back(): Boolean = false
    }
}
