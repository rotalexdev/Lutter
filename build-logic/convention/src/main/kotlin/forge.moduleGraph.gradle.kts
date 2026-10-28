import forge.modulegraph.DeclaredGraph
import forge.modulegraph.ModuleGraphRules
import forge.modulegraph.SelfTestModuleGraphTask
import forge.modulegraph.VerifyModuleGraphTask
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.language.base.plugins.LifecycleBasePlugin

// The policy is ModuleGraphRules.ALLOWED (PLAN §23.2) plus ModuleGraphRules.ALLOWED_IN_TESTS
// for test-scoped edges. Both are referenced, not restated: a second copy in a Gradle script
// is a second thing to forget to update.
//
// The *configuration names* live in ModuleGraphRules too, and for the same reason with one
// extra edge to the argument: which allow-list an edge is held to is a policy decision, so
// listing a configuration next to the map it selects is what keeps "one map, one scope" true
// by construction instead of by review.
//
// Both module classes have to be covered, or the check is theatre. The `commonMain*` and
// `commonTest*` configurations are where a KMP library declares inter-module dependencies;
// `api`/`implementation`/`testImplementation` are where the two JVM tool modules declare
// theirs. Checking only `commonMain*` would leave `:tools:cli` and
// `:tools:architecture-tests` completely unguarded, which is where a "harmless" dependency
// would actually get added.

/**
 * Every project dependency [module] declares in one of [configurationNames].
 *
 * Declarations only, never resolution: resolving configurations just to check a name would
 * download the world. A configuration this build does not have contributes nothing rather
 * than failing, because the two module classes declare in different ones and neither should
 * have to know about the other's vocabulary.
 */
fun edgesOf(module: Project, configurationNames: Set<String>): Set<String> =
    configurationNames.flatMapTo(mutableSetOf()) { configurationName ->
        module.configurations.findByName(configurationName)
            ?.allDependencies
            ?.filterIsInstance<ProjectDependency>()
            ?.map { it.path }
            .orEmpty()
    }

/**
 * Reads the *declared* project dependencies of every subproject, split by scope.
 *
 * Two maps rather than one because the scanner has to know which scope an edge came from:
 * a `commonTestImplementation` edge is held to `ALLOWED_IN_TESTS` and a `commonMain*` edge
 * is not, and a scanner that flattened them could not tell the two apart.
 *
 * Only projects that have a build file are read. Gradle creates an intermediate project for
 * every directory that has children, so `:engine`, `:tools`, `:integration` and `:samples`
 * exist as projects even though nothing configures them. They are containers, not modules,
 * and treating them as unknown modules would make the check fail on a build that is correct.
 */
fun readDeclaredGraph(): DeclaredGraph =
    DeclaredGraph(
        main = subprojects
            .filter { it.buildFile.exists() }
            .associate { it.path to edgesOf(it, ModuleGraphRules.MAIN_SCOPED_CONFIGURATIONS) },
        test = subprojects
            .filter { it.buildFile.exists() }
            .associate { it.path to edgesOf(it, ModuleGraphRules.TEST_SCOPED_CONFIGURATIONS) },
    )

val verifyModuleGraph = tasks.register<VerifyModuleGraphTask>("verifyModuleGraph") {
    // Seeded before evaluation so the task is always configured; the real values are
    // replaced in projectsEvaluated below, once every module has been evaluated.
    checkedModules.set(ModuleGraphRules.ALLOWED.keys.sorted())
    violations.set(emptyList<String>())
}

val selfTestModuleGraph = tasks.register<SelfTestModuleGraphTask>("selfTestModuleGraph") {
    badGraphDescription.set(":engine:model -> :engine:runtime")
    cleanGraphDescription.set("a legal graph (model <- schema, builtins-compose -> runtime, builtins)")
    harnessGraphDescription.set("a commonMain edge to :engine:test-support")
    testScopedHarnessDescription.set(":engine:serialization -> :engine:test-support in commonTest")
}

// Captured here rather than looked up inside the projectsEvaluated lambda: inside that
// lambda the receiver is Gradle, not Project, so an unqualified `tasks` would not resolve to
// this build script's project.
val checkTask = tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME)

// The root project is evaluated before any of its children, so a graph read at the top of
// this script would see zero dependencies. projectsEvaluated is the first moment at which
// every module has declared its build file.
gradle.projectsEvaluated {
    val declared = readDeclaredGraph()

    verifyModuleGraph.configure {
        checkedModules.set((declared.main.keys + declared.test.keys).sorted())
        violations.set(ModuleGraphRules.validate(declared))
    }

    // `check` on the root gates every module's own `check` through the normal Gradle
    // task-dependency propagation, so this is where PLAN §23.4's "runs on every CI build"
    // is actually enforced.
    checkTask.configure {
        dependsOn(verifyModuleGraph)
    }
}
