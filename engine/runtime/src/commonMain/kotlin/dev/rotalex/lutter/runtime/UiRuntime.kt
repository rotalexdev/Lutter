package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.interpreter.action.ActionEnv
import dev.rotalex.lutter.interpreter.action.ActionExecutor
import dev.rotalex.lutter.interpreter.action.ActionOutcome
import dev.rotalex.lutter.interpreter.eval.Evaluator
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec

/**
 * The live renderer: registries plus the interpreter seam, coverage-checked.
 *
 * PLAN §15.2 field for field. Construction fails naming every spec type without
 * a renderer, so a gap surfaces here and not as a blank screen.
 *
 * [evaluator] is built from [implementations] rather than injected, because §15.3 says the
 * `FunctionImpl`s come from there: one place owns the dispatch table and every screen below this
 * reads its expressions through the same evaluator.
 */
public class UiRuntime(
    public val renderers: RendererRegistry,
    public val modifiers: ModifierApplierRegistry,
    public val implementations: Implementations,
    schema: SchemaView<ComponentSpec, ModifierSpec, *, *, *>,
) {
    /** §15.3's expression interpreter over the functions [implementations] carries. */
    public val evaluator: Evaluator = Evaluator(implementations.functions)

    // Built once because the executor copies the table, so a host changing what it registered
    // afterwards cannot change what a sequence that is already running will do.
    private val executor: ActionExecutor = ActionExecutor(implementations.actions)

    init {
        RuntimeCoverage.check(schema, this)
    }

    /**
     * Runs [sequence] against [env] and routes a failure to [environment]'s diagnostics.
     *
     * The routing is here because `ActionEnv` has no slot for a diagnostic and the executor's
     * other answer is a return value a caller has to remember to read.
     */
    public suspend fun runActions(
        sequence: ActionSequence,
        env: ActionEnv,
        environment: RuntimeEnvironment,
    ): ActionOutcome {
        val outcome = executor.run(sequence, env)
        if (outcome is ActionOutcome.Failed) environment.diagnostics(outcome.diagnostic)
        return outcome
    }
}

/**
 * The §4.5 fail-fast: every spec in the schema renders, or construction refuses.
 *
 * Both halves of the plugin pair are checked, because an unwired modifier fails the same way
 * an unwired component does — silently, as a node drawn without what the document asked for.
 *
 * Document-overlay types (`doc.*`) are not schema members, so they are not checked
 * here; their renderer is resolved per node at render time instead.
 */
public object RuntimeCoverage {
    /** Every component and modifier type in [schema] must read back out of [runtime]. */
    public fun check(schema: SchemaView<ComponentSpec, ModifierSpec, *, *, *>, runtime: UiRuntime): Unit {
        val unwiredComponents = schema.components.all()
            .map { it.type }
            .filter { it !in runtime.renderers }
            .sortedBy { it.value }
        if (unwiredComponents.isNotEmpty()) {
            throw IllegalStateException(
                "UiRuntime has no renderer for ${unwiredComponents.joinToString { "'$it'" }}",
            )
        }
        val unwiredModifiers = schema.modifiers.all()
            .map { it.type }
            .filter { it !in runtime.modifiers }
            .sortedBy { it.value }
        if (unwiredModifiers.isNotEmpty()) {
            throw IllegalStateException(
                "UiRuntime has no modifier applier for ${unwiredModifiers.joinToString { "'$it'" }}",
            )
        }
    }
}
