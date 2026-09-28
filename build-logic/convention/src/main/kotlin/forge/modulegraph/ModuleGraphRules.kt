package forge.modulegraph

/**
 * The dependency graph, expressed as data.
 *
 * This is a pure object with no Gradle imports on purpose. Two reasons:
 *
 *  1. The rules can be exercised by a plain unit test and by the `selfTestModuleGraph`
 *     task without a build, so a regression in the checker is detectable.
 *  2. A file that imports Gradle cannot be read by a reviewer as a *policy* document.
 *     This map is the readable, arguable statement of what the architecture is; the
 *     Gradle task only wires the build's declarations into it.
 *
 * Encodes PLAN §23.2 (the allowed matrix) and PLAN §23.3 (the explicit prohibitions).
 * Both engines, samples, tools and integration modules are listed even though some have
 * no allowed dependencies, because a module missing from the map must be a *violation*
 * rather than silently unconstrained.
 */
object ModuleGraphRules {

    /** Every module that may appear as a key or as a dependency. */
    val KNOWN_MODULES: Set<String> = setOf(
        ":engine:model",
        ":engine:schema",
        ":engine:serialization",
        ":engine:interpreter",
        ":engine:analysis",
        ":engine:editing",
        ":engine:codegen",
        ":engine:runtime",
        ":engine:builtins",
        ":engine:builtins-compose",
        ":engine:test-support",
        ":tools:cli",
        ":tools:architecture-tests",
        ":integration:generated-compile",
        ":samples:desktop-preview",
    )

    /**
     * Module path -> the only module paths it may depend on.
     *
     * Read the absences, they carry the architecture:
     *  * `:engine:model` has no entries. It is the root of the graph; PLAN §23.3 allows it
     *    to reach nothing at all.
     *  * `:engine:serialization` may not see `:engine:schema`. Migrations have to outlive
     *    schema changes, so the codec cannot be typed against the specs it reads.
     *  * `:engine:codegen` and `:engine:runtime` cannot see each other, in either
     *    direction. Generation and rendering are two answers to one document, not two
     *    layers.
     *  * `:engine:analysis` may not see `:engine:interpreter`, so type checking never
     *    depends on values that only exist at runtime.
     *  * `:engine:editing` may not see `:engine:runtime` or `:engine:codegen`. The editor
     *    composes those layers; the layers do not compose the editor.
     *  * No `engine:*` module may reach `tools:*`, `samples:*` or `integration:*`.
     */
    val ALLOWED: Map<String, Set<String>> = mapOf(
        ":engine:model" to emptySet(),
        ":engine:schema" to setOf(":engine:model"),
        ":engine:serialization" to setOf(":engine:model"),
        ":engine:interpreter" to setOf(":engine:model", ":engine:schema"),
        ":engine:analysis" to setOf(":engine:model", ":engine:schema"),
        ":engine:editing" to setOf(":engine:model", ":engine:schema"),
        ":engine:codegen" to setOf(":engine:model", ":engine:schema", ":engine:analysis"),
        ":engine:runtime" to setOf(
            ":engine:model",
            ":engine:schema",
            ":engine:interpreter",
            ":engine:analysis",
        ),
        ":engine:builtins" to setOf(":engine:model", ":engine:schema", ":engine:interpreter"),
        ":engine:builtins-compose" to setOf(
            ":engine:model",
            ":engine:schema",
            ":engine:interpreter",
            ":engine:analysis",
            ":engine:runtime",
            ":engine:builtins",
        ),
        ":engine:test-support" to setOf(
            ":engine:model",
            ":engine:schema",
            ":engine:serialization",
            ":engine:interpreter",
            ":engine:analysis",
            ":engine:editing",
            ":engine:codegen",
            ":engine:runtime",
            ":engine:builtins",
            ":engine:builtins-compose",
        ),
        ":tools:cli" to setOf(
            ":engine:model",
            ":engine:serialization",
            ":engine:analysis",
            ":engine:codegen",
            ":engine:builtins",
        ),
        ":tools:architecture-tests" to emptySet(),
        ":integration:generated-compile" to setOf(
            ":engine:model",
            ":engine:schema",
            ":engine:serialization",
            ":engine:interpreter",
            ":engine:analysis",
            ":engine:editing",
            ":engine:codegen",
            ":engine:runtime",
            ":engine:builtins",
            ":engine:builtins-compose",
        ),
        ":samples:desktop-preview" to setOf(
            ":engine:model",
            ":engine:serialization",
            ":engine:analysis",
            ":engine:runtime",
            ":engine:builtins",
            ":engine:builtins-compose",
            ":engine:test-support",
        ),
    )

    /**
     * Compares a declared graph against the allow-list.
     *
     * One violation per forbidden edge, plus one per unknown module on either side, so a
     * report line always points at a single fix.
     *
     * Ordering is fully deterministic (module path, then dependency path) so that a
     * failing build produces the same report every time and two reports can be diffed.
     *
     * @param declared the graph the build actually declares.
     * @param allowed the policy to check against; overridable so the self-test can run a
     *   synthetic policy without touching [ALLOWED].
     */
    fun validate(
        declared: Map<String, Set<String>>,
        allowed: Map<String, Set<String>> = ALLOWED,
    ): List<String> {
        val violations = mutableListOf<String>()

        for (module in (declared.keys + allowed.keys).sorted()) {
            if (module !in KNOWN_MODULES) {
                violations += "$module is not a known module of this build"
            }

            val permitted = allowed[module]
            if (permitted == null) {
                violations += "$module has no allow-list entry, so no dependency of it can be verified"
                continue
            }

            for (dependency in declared[module].orEmpty().sorted()) {
                when {
                    dependency == module ->
                        violations += "$module -> $dependency : a module cannot depend on itself"

                    dependency !in KNOWN_MODULES ->
                        violations += "$module -> $dependency : $dependency is not a known module of this build"

                    dependency !in permitted ->
                        violations += "$module -> $dependency : forbidden; $module may depend on " +
                            (permitted.sorted().joinToString(", ").ifEmpty { "no other module" })
                }
            }
        }

        // Sorting the rendered strings keeps the report identical to the traversal order
        // above, and stays stable if the traversal is ever changed.
        return violations.sorted()
    }
}
