package forge.modulegraph

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * The negative test PLAN §32 Phase 0 asks for: "graph check fails on a deliberately bad
 * dependency".
 *
 * A checker that has never rejected anything is indistinguishable from a checker that
 * accepts everything, and Phase 0 has no real dependency edges to test against because
 * every module is an empty shell. So the rules engine is driven directly with synthetic
 * graphs instead of mutating the real build, which keeps the test hermetic: it cannot be
 * satisfied by an accidental dependency and it leaves no state behind.
 *
 * Four cases, and the last two are a pair that only means something together.
 * `:engine:test-support` is reachable from a module's *test* sources and from nowhere else,
 * so the split into [ModuleGraphRules.ALLOWED] and [ModuleGraphRules.ALLOWED_IN_TESTS] is
 * a narrowing only if the main-scope edge is still refused for every module in the graph.
 * A test harness in `commonMain` is a shipped dependency on a module whose types are
 * fixtures, and nothing in a review of the diff would show it: the edge reads like any
 * other one. Accepting it in test scope is what proves the refusal is a decision about
 * scope rather than a checker that refuses the harness everywhere and calls it policy.
 */
abstract class SelfTestModuleGraphTask : DefaultTask() {

    @get:Input
    abstract val badGraphDescription: Property<String>

    @get:Input
    abstract val cleanGraphDescription: Property<String>

    @get:Input
    abstract val harnessGraphDescription: Property<String>

    @get:Input
    abstract val testScopedHarnessDescription: Property<String>

    init {
        group = "verification"
        description = "Proves ModuleGraphRules rejects a known-illegal edge and accepts a legal one."
    }

    @TaskAction
    fun run() {
        val badGraph = mapOf(":engine:model" to setOf(":engine:runtime"))
        val badViolations = ModuleGraphRules.validate(DeclaredGraph(main = badGraph, test = emptyMap()))

        if (badViolations.isEmpty()) {
            throw GradleException(
                "ModuleGraphRules self-test FAILED: ${badGraphDescription.get()} was accepted. " +
                    "The model module depends on the runtime module, which is the single most " +
                    "structural edge in PLAN §23.3; if the rules no longer catch it, they are " +
                    "not protecting anything.",
            )
        }

        // The same edge, in the scope that is allowed to carry it. Without this case the
        // rejection below would also be satisfied by a checker that refuses test-support
        // everywhere, which is a different bug and an easier one to ship.
        val testScopedHarness = ModuleGraphRules.validate(
            DeclaredGraph(
                main = emptyMap(),
                test = mapOf(":engine:serialization" to setOf(":engine:test-support")),
            ),
        )

        if (testScopedHarness.isNotEmpty()) {
            throw GradleException(
                "ModuleGraphRules self-test FAILED: ${testScopedHarnessDescription.get()} was rejected:\n" +
                    testScopedHarness.joinToString("\n") { "  $it" } +
                    "\nThe test-scoped allowance is what makes the golden harness reachable; a " +
                    "checker that refuses it has swapped a missing capability for a silent one.",
            )
        }

        // Swept over every module rather than asserted for one, because the promise is not
        // about `:engine:serialization`: it is that the harness is unreachable from any
        // `commonMain`. A single-module check would pass while `:samples:desktop-preview`
        // still listed the harness in its main allowance, which is exactly the sort of entry
        // that gets added by a well-meaning edit and never noticed.
        val harnessInMain = ModuleGraphRules.ALLOWED.keys
            .filter { module ->
                ModuleGraphRules.validate(
                    DeclaredGraph(
                        main = mapOf(module to setOf(":engine:test-support")),
                        test = emptyMap(),
                    ),
                ).isEmpty()
            }

        if (harnessInMain.isNotEmpty()) {
            throw GradleException(
                "ModuleGraphRules self-test FAILED: ${harnessGraphDescription.get()} was accepted " +
                    "for ${harnessInMain.joinToString(", ")}. ALLOWED_IN_TESTS is supposed to widen " +
                    "what a test source set may reach and nothing else; a main-scope entry widens " +
                    "what every target compiles, and it is a test harness on the shipped compile " +
                    "classpath. Remove it from ALLOWED and let the module declare it in commonTest.",
            )
        }

        val cleanGraph = mapOf(
            ":engine:model" to emptySet(),
            ":engine:schema" to setOf(":engine:model"),
            ":engine:builtins-compose" to setOf(":engine:runtime", ":engine:builtins"),
        )
        val cleanViolations = ModuleGraphRules.validate(DeclaredGraph(main = cleanGraph, test = emptyMap()))

        if (cleanViolations.isNotEmpty()) {
            throw GradleException(
                "ModuleGraphRules self-test FAILED: ${cleanGraphDescription.get()} was rejected:\n" +
                    cleanViolations.joinToString("\n") { "  $it" } +
                    "\nA checker that fails legal edges trains everyone to ignore it.",
            )
        }

        logger.lifecycle(
            "Module graph self-test OK: rejected ${badViolations.size} illegal edge(s), " +
                "accepted ${testScopedHarnessDescription.get()}, refused the test harness from " +
                "every commonMain, and accepted $cleanGraphDescription.",
        )
    }
}