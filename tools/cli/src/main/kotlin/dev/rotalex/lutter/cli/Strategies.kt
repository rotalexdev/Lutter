package dev.rotalex.lutter.cli

import dev.rotalex.lutter.codegen.ComposeSnapshotState
import dev.rotalex.lutter.codegen.InjectionStrategy
import dev.rotalex.lutter.codegen.Hilt
import dev.rotalex.lutter.codegen.Koin
import dev.rotalex.lutter.codegen.Metro
import dev.rotalex.lutter.codegen.NoInjection
import dev.rotalex.lutter.codegen.Navigation3
import dev.rotalex.lutter.codegen.NavigationCompose
import dev.rotalex.lutter.codegen.NavigationStrategy
import dev.rotalex.lutter.codegen.SimpleBackStack
import dev.rotalex.lutter.codegen.StateStrategy
import dev.rotalex.lutter.codegen.ViewModelState

/**
 * `--navigation`, `--state` and `--injection` as the strategies §15's seams are selected through.
 *
 * One object for both commands, because `generate` and `scaffold --document` are two spellings
 * of one pipeline: a flag name that meant `Navigation3` in one and something else in the other
 * would be the disagreement the seam exists to remove. Both commands default to the
 * dependency-free pair, which is why a caller who never names a strategy is unaffected by the
 * flags existing.
 *
 * ### Names, not class names
 *
 * The accepted values are the strategy's identity as a *choice* — `simple`, `navigation3`,
 * `viewmodel` — not its Kotlin type. A caller types a flag, and the refusal lists what the flag
 * accepts; naming a class in a CLI flag would make a rename in `:engine:codegen` a breaking
 * change to something a shell script depends on. The mapping is one `when` per flag, so a
 * strategy added to codegen and left unreachable here is a gap in a list a reader can see.
 */
internal object Strategies {

    /** The strategy names `--navigation` accepts, in the order the refusal lists them. */
    private val NAVIGATIONS: Map<String, (String) -> NavigationStrategy> = mapOf(
        "simple" to { basePackage -> SimpleBackStack(basePackage) },
        "navigation-compose" to { basePackage -> NavigationCompose(basePackage) },
        "navigation3" to { basePackage -> Navigation3(basePackage) },
    )

    /** The strategy names `--state` accepts, in the order the refusal lists them. */
    private val STATES: Map<String, StateStrategy> = mapOf(
        "snapshot" to ComposeSnapshotState,
        "viewmodel" to ViewModelState,
    )

    /** The strategy names `--injection` accepts, in the order the refusal lists them. */
    private val INJECTIONS: Map<String, InjectionStrategy> = mapOf(
        "none" to NoInjection,
        "koin" to Koin,
        "metro" to Metro,
        "hilt" to Hilt,
    )

    /**
     * `--navigation`'s value, or [SimpleBackStack] when the flag is absent.
     *
     * [basePackage] is the generated app's package, which the two strategies that declare their
     * `Route` hierarchy name from: the strategy carries it because a `nav.navigate` handler is
     * written into a screen's file with no context to read the package from.
     */
    fun navigation(value: String?, basePackage: String, command: String): NavigationStrategy {
        val name: String = value ?: DEFAULT_NAVIGATION
        val build = NAVIGATIONS[name.lowercase()]
            ?: throw CliFailure(
                ExitCode.Unusable,
                "forge $command: unknown navigation '$name'; accepted: ${NAVIGATIONS.keys.joinToString(", ")}",
            )
        return build(basePackage)
    }

    /** `--state`'s value, or [ComposeSnapshotState] when the flag is absent. */
    fun state(value: String?, command: String): StateStrategy {
        val name: String = value ?: DEFAULT_STATE
        return STATES[name.lowercase()]
            ?: throw CliFailure(
                ExitCode.Unusable,
                "forge $command: unknown state '$name'; accepted: ${STATES.keys.joinToString(", ")}",
            )
    }

    /**
     * `--injection`'s value, or [NoInjection] when the flag is absent.
     *
     * No container is the default for the same reason no navigation and no ViewModel are: the
     * generated app that selects nothing resolves nothing beyond Compose.
     */
    fun injection(value: String?, command: String): InjectionStrategy {
        val name: String = value ?: DEFAULT_INJECTION
        return INJECTIONS[name.lowercase()]
            ?: throw CliFailure(
                ExitCode.Unusable,
                "forge $command: unknown injection '$name'; accepted: ${INJECTIONS.keys.joinToString(", ")}",
            )
    }

    private const val DEFAULT_NAVIGATION: String = "simple"

    private const val DEFAULT_STATE: String = "snapshot"

    private const val DEFAULT_INJECTION: String = "none"
}
