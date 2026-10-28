package forge.modulegraph

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when the declared module graph contains a forbidden edge.
 *
 * Inputs are plain strings, computed at configuration time by
 * `forge.moduleGraph.gradle.kts`. That is deliberate: the graph must be reported as a
 * whole, aligned and sorted, rather than streamed as each module is read.
 */
abstract class VerifyModuleGraphTask : DefaultTask() {

    @get:Input
    abstract val violations: ListProperty<String>

    @get:Input
    abstract val checkedModules: ListProperty<String>

    init {
        group = "verification"
        description = "Compares every module's project dependencies against the PLAN §23.2 allow-list."

        // The root reaches into other projects' configurations to build this task's
        // inputs. The configuration cache cannot serialise that cross-project
        // configuration-time read, so runs that include this task opt out of caching
        // rather than the guardrail being quietly degraded or the build failing with an
        // opaque cache error.
        notCompatibleWithConfigurationCache(
            "Reads the declared configurations of other projects at configuration time.",
        )
    }

    @TaskAction
    fun verify() {
        val found = violations.get()
        val modules = checkedModules.get()

        if (found.isNotEmpty()) {
            val report = buildString {
                appendLine("Module graph violations (PLAN §23.2 / §23.3):")
                // Padded to the widest dependency column in the report, so every "->" in
                // the block lines up and the eye can scan the dependency alone.
                val width = found.maxOf { arrowColumn(it) }
                found.forEach { appendLine("  $it".padEnd(width + 2)) }
                appendLine()
                appendLine("${found.size} violation(s) across ${modules.size} module(s).")
                append("Move the dependency, or change ModuleGraphRules.ALLOWED in a reviewed commit.")
            }
            throw GradleException(report)
        }

        logger.lifecycle("Module graph OK: ${modules.size} module(s), no forbidden dependency edges.")
    }

    /** Index of the dependency column, i.e. just past the `" -> "` separator. */
    private fun arrowColumn(violation: String): Int = violation.indexOf(" -> ").takeIf { it >= 0 } ?: 0
}
