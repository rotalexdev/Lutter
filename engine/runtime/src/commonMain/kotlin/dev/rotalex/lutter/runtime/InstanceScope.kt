package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.value.Value

/**
 * One `doc.*` instance's params, bound over the scope the instance was written in.
 *
 * §5.5 makes a declaration's params its whole input surface, so this answers every
 * `RefTarget.Param` itself and delegates the rest: a body that reads state resolves through the
 * screen's stores, which is where pass 5 typed it, because the owner-last scope a component body
 * gets already contains the app's state and the component's own.
 *
 * An unbound name throws rather than falling through to the screen's page params. A component
 * body does not see the params of the page that happens to render it — the same rule
 * [ScreenEvalScope] reads state by, and the one pass 5 refused a document breaking.
 */
internal class InstanceScope(
    private val inner: EvalScope,
    private val params: Map<ParamName, Value>,
) : EvalScope {

    override fun read(target: RefTarget): Value = when (target) {
        is RefTarget.Param -> params[target.name]
            ?: throw IllegalStateException(
                "Unknown param '${target.name}': §5.5 makes a document component's params its whole " +
                    "input surface, and the body cannot read the page's",
            )

        else -> inner.read(target)
    }
}
