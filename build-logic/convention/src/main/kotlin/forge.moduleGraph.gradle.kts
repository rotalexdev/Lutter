import forge.modulegraph.ModuleGraphRules
import forge.modulegraph.SelfTestModuleGraphTask
import forge.modulegraph.VerifyModuleGraphTask
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.language.base.plugins.LifecycleBasePlugin

// The policy is ModuleGraphRules.ALLOWED (PLAN §23.2). It is referenced, not restated:
// a second copy in a Gradle script is a second thing to forget to update.
//
// Both module classes have to be covered, or the check is theatre. The `commonMain*` and
// `commonTest*` configurations are where a KMP library declares inter-module dependencies;
// `api`/`implementation`/`testImplementation` are where the two JVM tool modules declare
// theirs. Checking only `commonMain*` would leave `:tools:cli` and
// `:tools:architecture-tests` completely unguarded, which is where a "harmless" dependency
// would actually get added.
val graphConfigurations = listOf(
    "commonMainApi",
    "commonMainImplementation",
    "commonMainRuntimeOnly",
    "commonMainCompileOnly",
    "commonTestImplementation",
    "api",
    "implementation",
    "testImplementation",
    "compileOnly",
    "runtimeOnly",
)

/**
 * Reads the *declared* project dependencies of every subproject. Declarations only, never
 * resolution: resolving configurations just to check a name would download the world.
 */
fun readDeclaredGraph(): Map<String, Set<String>> = subprojects.associate { module ->
    module.path to graphConfigurations.flatMapTo(mutableSetOf()) { configurationName ->
        module.configurations.findByName(configurationName)
            ?.allDependencies
            ?.filterIsInstance<ProjectDependency>()
            ?.map { it.path }
            .orEmpty()
    }
}

val verifyModuleGraph = tasks.register<VerifyModuleGraphTask>("verifyModuleGraph") {
    // Seeded before evaluation so the task is always configured; the real values are
    // replaced in projectsEvaluated below, once every module has been evaluated.
    checkedModules.set(ModuleGraphRules.ALLOWED.keys.sorted())
    violations.set(emptyList<String>())
}

val selfTestModuleGraph = tasks.register<SelfTestModuleGraphTask>("selfTestModuleGraph") {
    badGraphDescription.set(":engine:model -> :engine:runtime")
    cleanGraphDescription.set("a legal graph (model <- schema, builtins-compose -> runtime, builtins)")
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
        checkedModules.set(declared.keys.sorted())
        violations.set(ModuleGraphRules.validate(declared))
    }

    // `check` on the root gates every module's own `check` through the normal Gradle
    // task-dependency propagation, so this is where PLAN §23.4's "runs on every CI build"
    // is actually enforced.
    checkTask.configure {
        dependsOn(verifyModuleGraph)
    }
}
