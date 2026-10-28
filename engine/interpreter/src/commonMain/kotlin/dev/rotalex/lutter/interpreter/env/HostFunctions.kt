package dev.rotalex.lutter.interpreter.env

import dev.rotalex.lutter.model.value.Value

/**
 * One host function as the interpreter calls it.
 *
 * Suspending because a declared function may suspend, and positional because the declared
 * parameter list is the only ordering anyone has: a handler maps names onto positions and
 * this type sees the result.
 *
 * A null result is the function returning nothing, not a failure. Only the embedding
 * application can tell a unit function from one that returns a value, so the interpreter
 * passes it on rather than inventing a value for it.
 */
public typealias HostFunction = suspend (List<Value>) -> Value?

/**
 * The functions the embedding application registered, by name.
 *
 * Absence is a null rather than a throw, because the only failure path a step has is a
 * diagnostic the caller routes and a sequence stops on. A throw would leave that path, and
 * nothing static refuses the name first: a host call declares no parameter, so no pass has
 * anything to check the name it carries against.
 */
public class HostFunctions(functions: Map<String, HostFunction>) {

    // Copied so a caller that mutates its own map afterwards cannot change what is resolved.
    private val entries: Map<String, HostFunction> = functions.toMap()

    /** The function [name] names, or null when the application registered none. */
    public fun find(name: String): HostFunction? = entries[name]

    public companion object {
        /** No host functions. The default for an environment that registers none. */
        public val None: HostFunctions = HostFunctions(emptyMap())
    }
}
