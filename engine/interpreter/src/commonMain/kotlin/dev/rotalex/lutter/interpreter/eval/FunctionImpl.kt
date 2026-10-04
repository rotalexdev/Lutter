package dev.rotalex.lutter.interpreter.eval

import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.registry.Registry
import dev.rotalex.lutter.schema.registry.RegistryBuilder

/**
 * The interpreter half of a §10.3 function: pure Kotlin, filed under the same
 * [FunctionId] as the `FunctionSpec` that describes it.
 *
 * A `List<Value>` rather than typed parameters because §10.3 declares exactly this shape:
 * the `ParamSig` list lives on the spec, and the coverage test §10.3 asks for is what holds
 * the two halves together.
 */
public fun interface FunctionImpl {

    /** The arguments in written order. Total: §10.6 asks for a value for any input. */
    public fun invoke(args: List<Value>): Value
}

/**
 * Every [FunctionImpl] an [Evaluator] dispatches to, keyed by its [FunctionId].
 *
 * A [Registry] rather than a bare map so a duplicate id is refused where two assemblers could
 * collide instead of one silently winning by ordering. §33.4 files this beside [FunctionImpl]
 * because the function set is the pair.
 */
public class FunctionImpls(
    private val registry: Registry<FunctionId, FunctionImpl>,
) {

    /** The implementation for [id], or null when none is registered. Never throws. */
    public operator fun get(id: FunctionId): FunctionImpl? = registry[id]

    public companion object {

        /**
         * The table of an evaluator with nothing registered, so every call refuses.
         *
         * §10.2's seed set arrives with `:engine:builtins`, and until then this is the honest
         * answer for a call rather than a failure at construction.
         */
        public val None: FunctionImpls = FunctionImpls(RegistryBuilder<FunctionId, FunctionImpl>().build())

        /**
         * The table over [entries], each keyed by its own id.
         *
         * A [RegistryBuilder] rather than a map so a duplicate is refused where it is written:
         * two assemblers claiming one id should not be settled by ordering, and the failure is
         * §10.3's coverage test's business at run time, not a silent last-one-wins here.
         */
        public fun of(entries: List<Pair<FunctionId, FunctionImpl>>): FunctionImpls =
            FunctionImpls(RegistryBuilder<FunctionId, FunctionImpl>().apply { registerAll(entries) }.build())
    }
}
