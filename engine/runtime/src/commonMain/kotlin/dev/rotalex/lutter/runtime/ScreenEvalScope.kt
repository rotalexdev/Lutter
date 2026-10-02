package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.analysis.resolved.ResolvedState
import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.interpreter.StateStore
import dev.rotalex.lutter.interpreter.eval.Evaluator
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value

/**
 * What a screen's expressions may read: the app's and the page's state, the page's params, and a
 * derived declaration evaluated on the spot.
 *
 * §15.3 puts the binding knowledge in the scope rather than in the evaluator, so this is where
 * `RefTarget` stops being a variant and starts being a lookup. `MapEvalScope` is the
 * interpreter's own and answers from one plain store; this one knows the two stores §12.1 keeps
 * apart and the derived row beside them.
 *
 * ### Derived state is computed, never looked up
 *
 * §12.1 puts derived state at "evaluated on read (Compose tracks dependencies)", so every read
 * runs the [Evaluator] again — which is what lets the state it names subscribe the composition
 * asking for the value.
 *
 * The page store is consulted before the app store because pass 5 resolves an owner's
 * declaration ahead of an app one carrying the same id. §6.2 makes that collision a document a
 * NamingPass has already refused, so the order only has to agree with the checker, not choose.
 */
internal class ScreenEvalScope(
    declarations: List<ResolvedState>,
    private val page: StateStore,
    private val app: StateStore,
    private val params: Map<ParamName, Value>,
    private val evaluator: Evaluator,
) : EvalScope {

    private val derived: Map<StateId, TypedExpr> =
        declarations.mapNotNull { state -> state.derived?.let { body -> state.decl.id to body } }.toMap()

    override fun read(target: RefTarget): Value = when (target) {
        is RefTarget.State -> readState(target.id)
        is RefTarget.Param -> params[target.name]
            ?: throw IllegalStateException("Unknown param '${target.name}': UiScreen seeds the page params")

        // §17.1 puts both in pass 5's scope resolution and neither is bound in a property
        // position; Phase 7's handler scope and a future loop scope bind them there.
        is RefTarget.EventArg -> throw IllegalStateException(
            "Event arg '${target.name}' resolves only inside an action handler (Phase 7)",
        )

        is RefTarget.Item -> throw IllegalStateException(
            "Iteration variable '${target.name}' has no binding: loops are post-MVP",
        )
    }

    private fun readState(id: StateId): Value {
        val body = derived[id]
        if (body != null) return evaluator.eval(body, this)
        return page.get(id) ?: app.get(id)
            ?: throw IllegalStateException(
                "Nothing holds state '$id'; §12.1 says a held declaration carries an initial value",
            )
    }
}
