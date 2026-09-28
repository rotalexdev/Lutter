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
 */
abstract class SelfTestModuleGraphTask : DefaultTask() {

    @get:Input
    abstract val badGraphDescription: Property<String>

    @get:Input
    abstract val cleanGraphDescription: Property<String>

    init {
        group = "verification"
        description = "Proves ModuleGraphRules rejects a known-illegal edge and accepts a legal one."
    }

    @TaskAction
    fun run() {
        val badGraph = mapOf(":engine:model" to setOf(":engine:runtime"))
        val badViolations = ModuleGraphRules.validate(badGraph)

        if (badViolations.isEmpty()) {
            throw GradleException(
                "ModuleGraphRules self-test FAILED: ${badGraphDescription.get()} was accepted. " +
                    "The model module depends on the runtime module, which is the single most " +
                    "structural edge in PLAN §23.3; if the rules no longer catch it, they are " +
                    "not protecting anything.",
            )
        }

        val cleanGraph = mapOf(
            ":engine:model" to emptySet(),
            ":engine:schema" to setOf(":engine:model"),
            ":engine:builtins-compose" to setOf(":engine:runtime", ":engine:builtins"),
        )
        val cleanViolations = ModuleGraphRules.validate(cleanGraph)

        if (cleanViolations.isNotEmpty()) {
            throw GradleException(
                "ModuleGraphRules self-test FAILED: ${cleanGraphDescription.get()} was rejected:\n" +
                    cleanViolations.joinToString("\n") { "  $it" } +
                    "\nA checker that fails legal edges trains everyone to ignore it.",
            )
        }

        logger.lifecycle(
            "Module graph self-test OK: rejected ${badViolations.size} illegal edge(s), " +
                "accepted $cleanGraphDescription.",
        )
    }
}
