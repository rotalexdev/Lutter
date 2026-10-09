package forge.modulegraph

/**
 * The graph a build declares, split by the source set that declares each edge.
 *
 * Two maps rather than one because a `commonTestImplementation` edge and a
 * `commonMainImplementation` edge are different claims about a module: the first says "my
 * tests may reach this", the second says "my code may reach this, and everything that code
 * is compiled into ships with it". [ModuleGraphRules.validate] holds them to different
 * allow-lists for exactly that reason, and merging them here is what would let a test-only
 * dependency smuggle itself into `commonMain` unremarked.
 */
class DeclaredGraph(

    /** Edges declared by a main-scoped configuration. See [ModuleGraphRules.MAIN_SCOPED_CONFIGURATIONS]. */
    val main: Map<String, Set<String>>,

    /** Edges declared by a test-scoped configuration. See [ModuleGraphRules.TEST_SCOPED_CONFIGURATIONS]. */
    val test: Map<String, Set<String>>,
)

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

    // ---------------------------------------------------------------------------------------
    // Which configurations say what
    // ---------------------------------------------------------------------------------------

    /**
     * Configurations whose declared project dependencies reach a module's shipped code.
     *
     * These are the ones checked against [ALLOWED] and only against [ALLOWED]. An edge found
     * here is a promise that some target compiles the dependency into the artifact, so it is
     * held to the same standard on every target of the module.
     */
    val MAIN_SCOPED_CONFIGURATIONS: Set<String> = setOf(
        "commonMainApi",
        "commonMainImplementation",
        "commonMainRuntimeOnly",
        "commonMainCompileOnly",
        "api",
        "implementation",
        "compileOnly",
        "runtimeOnly",
    )

    /**
     * Configurations whose declared project dependencies reach test sources and nothing else.
     *
     * These are checked against [ALLOWED] *plus* [ALLOWED_IN_TESTS], so a test may reach
     * everything its own module may reach and the harness on top of it, and nothing else.
     *
     * `commonTestImplementation` and `testImplementation` are the two that exist in this build.
     * A configuration added to one of these two sets is a policy change, not a rename: it
     * decides which allow-list a new kind of edge is held to, so it belongs here beside the
     * map it selects rather than in the Gradle script that reads it.
     */
    val TEST_SCOPED_CONFIGURATIONS: Set<String> = setOf(
        "commonTestImplementation",
        "testImplementation",
    )

    /** Every configuration the scanner reads. The union, so the scanner cannot drift from the policy. */
    val SCANNED_CONFIGURATIONS: Set<String> = MAIN_SCOPED_CONFIGURATIONS + TEST_SCOPED_CONFIGURATIONS

    // ---------------------------------------------------------------------------------------
    // What is allowed
    // ---------------------------------------------------------------------------------------

    /**
     * Module path -> the only module paths it may depend on from `commonMain`.
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
     *  * **No module may reach `:engine:test-support`.** Not one, and the absence is the
     *    point rather than an oversight — see [ALLOWED_IN_TESTS] for the whole argument.
     *    `:engine:test-support` is a test harness: its classes exist to be read by another
     *    module's test sources and they are never part of a document, a build of the engine,
     *    or anything a plugin ships. A `commonMain` edge to it would put a test-only type on
     *    the shipped compile classpath, where it becomes reachable from production code by
     *    nothing more than an import. That is the same defect PLAN §23.3's purity rules
     *    exist to catch, reached through the dependency graph instead of through an import.
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
            ":engine:schema",
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
        ),
    )

    /**
     * Module path -> the module paths it may additionally depend on from a *test* source set.
     *
     * A test may reach everything its module may reach, because its tests compile against the
     * module's own code, plus whatever is listed here. So this is a delta on [ALLOWED] and not
     * a second full matrix: a module absent from it gains nothing, which is the safe direction
     * for a map whose default has to be right for fourteen modules nobody has thought about.
     *
     * Read the absences here too:
     *  * **`:engine:test-support` is not in this map either.** It is the one module a test
     *    harness could be tempted to hang on, and it is a dependency like any other: it will
     *    not be main-scope *because* it is the harness, it is test-scope because a test
     *    source set is the only place a harness belongs. A `commonMainImplementation` edge to
     *    it is rejected, and `selfTestModuleGraph` proves it on every build.
     *  * **Nothing else is here.** One entry, for one consumer. Every module that later
     *    needs the harness — the conformance fixtures of PLAN §36.2, the codegen goldens, the
     *    render tests — adds its own line in the commit that needs it, and the map stays a
     *    list of consumers rather than a second blanket permission.
     */
    val ALLOWED_IN_TESTS: Map<String, Set<String>> = mapOf(
        // Phase 3's acceptance, "golden JSON files identical on every target", needs the
        // canonical writer's output compared against a committed file, and the comparison
        // lives in this module. PLAN §32 lists it as a serialization test.
        ":engine:serialization" to setOf(":engine:test-support"),
    )

    // ---------------------------------------------------------------------------------------
    // The check
    // ---------------------------------------------------------------------------------------

    /**
     * Compares a declared graph against the allow-lists, one scope at a time.
     *
     * One violation per forbidden edge, plus one per unknown module on either side, so a
     * report line always points at a single fix.
     *
     * **The two scopes are not merged.** A main-scope edge is checked against [ALLOWED] and
     * against nothing else, so adding an entry to [ALLOWED_IN_TESTS] can never widen what a
     * module may compile into its `commonMain`. That is the whole reason the second map
     * exists rather than the obvious alternative of listing `:engine:test-support` in
     * [ALLOWED]: a test harness in `commonMain` is a shipped dependency on a module whose
     * types are test fixtures, and it would be invisible in review because the edge looks
     * like any other one.
     *
     * Ordering is fully deterministic (module path, then dependency path) so that a failing
     * build produces the same report every time and two reports can be diffed.
     *
     * @param declared the graph the build actually declares, split by scope.
     * @param allowed the main-scope policy; overridable so the self-test can run a
     *   synthetic policy without touching [ALLOWED].
     * @param allowedInTests the test-scope additions, consulted only for test-scoped edges.
     */
    fun validate(
        declared: DeclaredGraph,
        allowed: Map<String, Set<String>> = ALLOWED,
        allowedInTests: Map<String, Set<String>> = ALLOWED_IN_TESTS,
    ): List<String> {
        val violations = mutableListOf<String>()

        val modules = declared.main.keys + declared.test.keys + allowed.keys + allowedInTests.keys
        for (module in modules.sorted()) {
            if (module !in KNOWN_MODULES) {
                violations += "$module is not a known module of this build"
            }

            val permitted = allowed[module]
            if (permitted == null) {
                violations += "$module has no allow-list entry, so no dependency of it can be verified"
                continue
            }

            for (dependency in declared.main[module].orEmpty().sorted()) {
                edgeViolation(module, dependency, permitted, "main scope")?.let(violations::add)
            }

            // A test source set compiles against the module's own code as well as the
            // harness, so the test allowance is the main one plus the delta. Adding a
            // harness therefore cannot cost a module a dependency it already had.
            val permittedInTest = permitted + allowedInTests[module].orEmpty()
            for (dependency in declared.test[module].orEmpty().sorted()) {
                edgeViolation(module, dependency, permittedInTest, "test scope")?.let(violations::add)
            }
        }

        // Sorting the rendered strings keeps the report identical to the traversal order
        // above, and stays stable if the traversal is ever changed.
        return violations.sorted()
    }

    /** The violation one edge carries, or `null` when it is allowed. */
    private fun edgeViolation(
        module: String,
        dependency: String,
        permitted: Set<String>,
        scope: String,
    ): String? = when {
        dependency == module ->
            "$module -> $dependency : a module cannot depend on itself ($scope)"

        dependency !in KNOWN_MODULES ->
            "$module -> $dependency : $dependency is not a known module of this build"

        dependency !in permitted ->
            "$module -> $dependency : forbidden in $scope; $module may depend on " +
                (permitted.sorted().joinToString(", ").ifEmpty { "no other module" })

        else -> null
    }
}