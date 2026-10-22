package dev.rotalex.lutter.architecture

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §36.3's bullets 3 and 4: the module dependency graph matches §23 exactly, with no
 * cycles (`ModuleGraphTest`), and `:engine:codegen` and `:engine:runtime` do not depend on
 * each other.
 *
 * The build already holds the same policy. `forge.moduleGraph.gradle.kts` walks the
 * *Gradle-resolved* declarations against `ModuleGraphRules`, and `selfTestModuleGraph`
 * proves the checker behaves. So this test is not a translated copy of that gate; it is the
 * same question answered by a second reader, and the second reader is what pays for itself:
 *
 *  * **The Gradle scanner is silent about vocabulary it does not know.** `edgesOf` reads
 *    `ModuleGraphRules.MAIN_SCOPED_CONFIGURATIONS` and `TEST_SCOPED_CONFIGURATIONS`, and a
 *    configuration outside those two lists "contributes nothing rather than failing" — its
 *    exact words. A dependency declared under a renamed or freshly invented configuration
 *    would slip past every Gradle check while sitting in plain sight in a build file. This
 *    test reads every `project(":…")` line in every build file as raw text, so a declaration
 *    the policy has no name for fails *here*, where the fix is one line in
 *    `ModuleGraphRules` plus one decision.
 *  * **The policy is read from the policy file, not from a third restatement.** Both maps,
 *    both configuration lists and `KNOWN_MODULES` are parsed out of
 *    `ModuleGraphRules.kt`'s own text. A deliberate change to the matrix updates one file
 *    and this test follows it; if the file stops being the machine-readable data object it
 *    documents itself as, the parse turns up empty and the guards say so.
 *  * **The declared graph is read from the build files, not from Gradle**, so the two
 *    readers cannot share a blind spot of implementation.
 *
 * The one shape neither of those lists can name: `integration/generated-compile` wires task
 * classpaths through derived configurations it creates itself
 * (`add(cliRuntime.name, …)`, `add(fuzzRuntime.name, …)`). Those are runtime paths of named
 * tasks rather than compiles of shipped code, so the Gradle gate has nothing to hold them
 * to — and the modules they name are exactly `forge diff`'s CLI and the readiness harness
 * both plan sections already bless. The exhaustive pair list is kept here, because the
 * point of the enumeration is that a *third* derived edge cannot appear unnoticed: adding
 * one is an edit to this set, which is a decision in review rather than a build-file line.
 *
 * ### The guard, as ever
 *
 * A scanner that found nothing would pass every row. So the parse asserts it read all five
 * pieces of the policy (modules, two configuration lists, two maps) and that the declared
 * graph holds edges on all three arms it knows about — main, test and derived — before any
 * comparison runs.
 */
class ModuleGraphTest {

    @Test
    fun `the build includes exactly the modules the policy knows`() {
        val policy = POLICY
        val includes = GRAPH.includes

        assertTrue(
            policy.known.isNotEmpty(),
            "parsed no entry out of ModuleGraphRules.KNOWN_MODULES; the policy file is no longer " +
                "the readable data object this test reads it as",
        )
        assertEquals(
            policy.known.sorted(),
            includes.sorted(),
            "the settings file and the policy file disagree on which modules this build is",
        )
    }

    @Test
    fun `every project edge names a configuration the policy classifies`() {
        val policy = POLICY

        for (edge in GRAPH.edges) {
            when (edge) {
                is ProjectEdge.Declared -> assertTrue(
                    edge.configuration in policy.mainConfigurations ||
                        edge.configuration in policy.testConfigurations,
                    ":${edge.from} declares an edge to ${edge.to} under '${edge.configuration}', a " +
                        "configuration the policy has no name for. The Gradle scanner would ignore " +
                        "it in silence; classify it in ModuleGraphRules and let this file see it.",
                )

                is ProjectEdge.Derived -> {
                    val line = edge.from to edge.to
                    assertTrue(
                        line in GRAPH.blessedDerivedEdges,
                        ":${edge.from} wires a task classpath to ${edge.to} through '${edge.name}'. " +
                            "That shape is invisible to the module-graph gate by design, so it needs " +
                            "an explicit line in this file's blessed set — which is a decision, not a filter.",
                    )
                }
            }
        }

        assertTrue(
            GRAPH.edges.any { it is ProjectEdge.Declared },
            "no project edge was found in any build file; the scan itself is broken",
        )
        assertTrue(
            GRAPH.edges.any { it is ProjectEdge.Declared && it.configuration in policy.mainConfigurations },
            "main-scoped declarations were never seen; the scanner's configuration read is incomplete",
        )
        assertTrue(
            GRAPH.edges.any { it is ProjectEdge.Declared && it.configuration in policy.testConfigurations },
            "test-scoped declarations were never seen; the scanner's configuration read is incomplete",
        )
        assertTrue(
            GRAPH.edges.any { it is ProjectEdge.Derived },
            "derived classpath wiring is part of this build what it is; a scan that reads none of it sees half",
        )
    }

    @Test
    fun `every main-scope edge is one the matrix allows`() {
        val policy = POLICY
        val violations = mutableListOf<String>()

        for (edge in GRAPH.edges) {
            if (edge !is ProjectEdge.Declared) continue
            if (edge.configuration !in policy.mainConfigurations) continue

            val permitted = policy.allowed[edge.from]
                ?: error(":${edge.from} has no allow-list entry in ModuleGraphRules, which its own " +
                    "documentation calls a violation rather than an absence")

            if (edge.to !in permitted) {
                violations += ":${edge.from} -> ${edge.to} (main scope, '${edge.configuration}') is not " +
                    "a row §23 grants"
            }
        }

        assertTrue(
            policy.allowed.isNotEmpty(),
            "parsed no entry out of ModuleGraphRules.ALLOWED; the policy file is no longer readable",
        )
        assertEquals(
            emptyList<String>(),
            violations.sorted(),
            "every main-scope edge must be an allowed cell of §23.2's matrix",
        )
    }

    @Test
    fun `every test-scope edge is one the matrix plus the test allowance allows`() {
        val policy = POLICY
        val violations = mutableListOf<String>()

        for (edge in GRAPH.edges) {
            if (edge !is ProjectEdge.Declared) continue
            if (edge.configuration !in policy.testConfigurations) continue

            val permitted = (policy.allowed[edge.from].orEmpty() + policy.allowedInTests[edge.from].orEmpty())

            if (permitted.isEmpty()) {
                violations += ":${edge.from} declares nothing in main scope and nothing in the test " +
                    "allowance, yet names ${edge.to} in '${edge.configuration}'"
            } else if (edge.to !in permitted) {
                violations += ":${edge.from} -> ${edge.to} (test scope, '${edge.configuration}') is not " +
                    "a row §23 grants, in the matrix or in ALLOWED_IN_TESTS"
            }
        }

        assertTrue(
            policy.allowedInTests.isNotEmpty(),
            "parsed no entry out of ModuleGraphRules.ALLOWED_IN_TESTS; the policy file is no longer readable",
        )
        assertEquals(
            emptyList<String>(),
            violations.sorted(),
            "every test-scope edge must be an allowed cell of §23.2 or one of the named consumers",
        )
    }

    @Test
    fun `codegen and runtime cannot see each other`() {
        val declared = GRAPH.declaredMainDependencies

        assertEquals(
            emptyList<String>(),
            listOf(
                if (":engine:runtime" in declared[":engine:codegen"].orEmpty()) {
                    ":engine:codegen -> :engine:runtime"
                } else null,
                if (":engine:codegen" in declared[":engine:runtime"].orEmpty()) {
                    ":engine:runtime -> :engine:codegen"
                } else null,
            ).filterNotNull(),
            "§23.3: generation and rendering are two answers to one document, not two layers " +
                "in each other's compile classpath",
        )
    }

    @Test
    fun `the declared graph has no cycles`() {
        val cycle = findCycle(
            GRAPH.includes.sorted(),
            GRAPH.declaredMainDependencies,
        )

        assertTrue(
            cycle.isEmpty(),
            "the declared main-scope graph closes in on itself: ${cycle.joinToString(" -> ")}",
        )
    }
}

/**
 * A colored, stack-machine walk over [dependencies]: a node still on the walk reached again is
 * the closed walk to report, whole.
 *
 * Recursive local functions are not Kotlin, so the walk carries its own stack of
 * (node, its children) pairs; every child list is sorted before it is walked, so a cycle's
 * spelling is the same on every run and every machine — the same determinism the Gradle
 * checker's own ordered report promises.
 */
private fun findCycle(
    starts: List<String>,
    dependencies: Map<String, Set<String>>,
): List<String> {
    val state = HashMap<String, Int>()

    for (start in starts) {
        if (state.getOrDefault(start, 0) != 0) continue

        state[start] = 1
        val path = ArrayDeque(listOf(start))
        val stack = ArrayDeque(
            listOf(start to dependencies[start].orEmpty().sorted().iterator()),
        )

        while (stack.isNotEmpty()) {
            val (node, children) = stack.last()

            if (children.hasNext()) {
                val next = children.next()
                when (state.getOrDefault(next, 0)) {
                    0 -> {
                        state[next] = 1
                        path.addLast(next)
                        stack.addLast(next to dependencies[next].orEmpty().sorted().iterator())
                    }

                    1 -> return path.drop(path.indexOf(next)) + next
                }
            } else {
                state[node] = 2
                path.removeLast()
                stack.removeLast()
            }
        }
    }

    return emptyList()
}

// -------------------------------------------------------------------------------------------
// What the policy file says, read rather than restated
// -------------------------------------------------------------------------------------------

/**
 * The five pieces of `ModuleGraphRules`, parsed from the file's own text.
 *
 * Everything here is derived lazily, on first use, from README-root-relative
 * `build-logic/convention/src/main/kotlin/forge/modulegraph/ModuleGraphRules.kt`. The
 * extraction is deliberately crude — two quoted lists and two map segments — because the
 * file documents itself as a pure data object and the crudest correct reading is the check
 * on that claim: make the object no longer parseable as data and this test says so, before
 * the Gradle gate gets to decide whether its own scanner saw anything.
 */
internal class GraphPolicy internal constructor(
    val known: List<String>,
    val mainConfigurations: Set<String>,
    val testConfigurations: Set<String>,
    val allowed: Map<String, Set<String>>,
    val allowedInTests: Map<String, Set<String>>,
)

private val LOADED_POLICY: GraphPolicy by lazy { readPolicy() }

private val POLICY: GraphPolicy
    get() = LOADED_POLICY

/** Both configuration lists and both maps, one quoted-string find per piece. */
private fun readPolicy(): GraphPolicy {
    val root = RepositoryRoot.path
    val rules = root.resolve(
        "build-logic/convention/src/main/kotlin/forge/modulegraph/ModuleGraphRules.kt",
    )
    val text = Files.readString(rules)

    fun segment(beginsWith: String, endsBefore: String): String {
        val start = text.indexOf(beginsWith)
        val end = text.indexOf(endsBefore, start + beginsWith.length)
        check(start >= 0 && end > start) {
            "ModuleGraphRules.kt does not read as expected: '$beginsWith' is not followed by '$endsBefore'"
        }
        return text.substring(start, end)
    }

    fun quotedModulePaths(slice: String): List<String> =
        Regex("\"(:[\\w:-]+)\"").findAll(slice).map { it.groupValues[1] }.toList()

    fun quotedConfigurations(slice: String): List<String> =
        Regex("\"([A-Za-z][\\w]*)\"").findAll(slice).map { it.groupValues[1] }.toList()

    /**
     * The map entries are one line each in the simple case — `":x" to setOf(":y", …)` — and
     * grow a spine of their own when the set is long. So read each entry as: its header
     * (module path plus `to` plus the set constructor), then every further module path
     * quoted inside it until the next entry begins. The first quoted path of the slice is
     * the entry's own module; everything after is its allowance.
     */
    fun parsedMap(segmentText: String): Map<String, Set<String>> {
        val head = Regex("\"(:[\\w:-]+)\"\\s+to\\s+(emptySet\\(\\)|setOf\\s*\\()")
        val bounds = head.findAll(segmentText).map { it.range.first to it.groupValues[1] }.toList()
        check(bounds.isNotEmpty()) {
            "ModuleGraphRules.kt carries a map whose entries cannot be read as data"
        }
        val map = linkedMapOf<String, Set<String>>()
        bounds.forEachIndexed { index, (start, module) ->
            val end = if (index + 1 < bounds.size) bounds[index + 1].first else segmentText.length
            val quoted = quotedModulePaths(segmentText.substring(start, end))
            map[module] = quoted.drop(1).toSet()
        }
        return map
    }

    return GraphPolicy(
        known = quotedModulePaths(
            segment("val KNOWN_MODULES", "val MAIN_SCOPED_CONFIGURATIONS"),
        ),
        mainConfigurations = quotedConfigurations(
            segment("val MAIN_SCOPED_CONFIGURATIONS", "val TEST_SCOPED_CONFIGURATIONS"),
        ).toSet(),
        testConfigurations = quotedConfigurations(
            segment("val TEST_SCOPED_CONFIGURATIONS", "val SCANNED_CONFIGURATIONS"),
        ).toSet(),
        allowed = parsedMap(
            segment("val ALLOWED: Map", "val ALLOWED_IN_TESTS"),
        ),
        allowedInTests = parsedMap(
            segment("val ALLOWED_IN_TESTS", "fun validate"),
        ),
    )
}

// -------------------------------------------------------------------------------------------
// What the build files declare, read as text rather than through Gradle
// -------------------------------------------------------------------------------------------

/** One `project(":…")` line, by whichever shape the build file spells it. */
internal sealed class ProjectEdge {
    abstract val from: String
    abstract val to: String

    /** Named through a configuration the policy has a word for, or a violation on its own. */
    internal data class Declared(
        override val from: String,
        override val to: String,
        val configuration: String,
    ) : ProjectEdge()

    /** Wired through a derived configuration — `add(cliRuntime.name, project(":tools:cli"))`. */
    internal data class Derived(
        override val from: String,
        override val to: String,
        val name: String,
    ) : ProjectEdge()
}

internal class DeclaredGraph internal constructor(
    val includes: List<String>,
    val edges: List<ProjectEdge>,
    val blessedDerivedEdges: Set<Pair<String, String>>,
) {

    /** Module path to the set of paths its main-scoped declarations name, for the cycle row. */
    val declaredMainDependencies: Map<String, Set<String>> =
        includes.associateWith { mainDependencies(it) }

    private fun mainDependencies(module: String): Set<String> =
        (edges.filterIsInstance<ProjectEdge.Declared>())
            .filter { it.from == module && it.configuration in POLICY.mainConfigurations }
            .map { it.to }
            .toSet()
}

private val LOADED_GRAPH: DeclaredGraph by lazy { readGraph() }

private val GRAPH: DeclaredGraph
    get() = LOADED_GRAPH

private fun readGraph(): DeclaredGraph {
    val root = RepositoryRoot.path
    val settings = Files.readString(root.resolve("settings.gradle.kts"))

    val includes = Regex("include\\(\":([\\w:]+)\"\\)")
        .findAll(settings)
        .map { ":" + it.groupValues[1] }
        .toList()

    val edges = mutableListOf<ProjectEdge>()
    for (include in includes) {
        val file = root.resolve(include.trimStart(':').replace(':', '/')).resolve("build.gradle.kts")
        if (!Files.isRegularFile(file)) {
            edges += ProjectEdge.Declared(include, "<missing build.gradle.kts>", "(none)")
            continue
        }

        for ((index, rawLine) in Files.readAllLines(file).withIndex()) {
            val line = rawLine.trimStart()
            if (line.startsWith("//")) continue

            val derived = DERIVED_ADD.matchEntire(line)
            if (derived != null) {
                edges += ProjectEdge.Derived(
                    include,
                    ":" + derived.groupValues[2],
                    derived.groupValues[1].substringBeforeLast('.'),
                )
                continue
            }

            val inline = INLINE_CALL.matchEntire(line)
            if (inline != null) {
                edges += ProjectEdge.Declared(
                    include,
                    ":" + inline.groupValues[2],
                    inline.groupValues[1],
                )
                continue
            }

            if ("project(\":" in line) {
                edges += ProjectEdge.Declared(
                    include,
                    ":" + Regex("project\\(\":([\\w:]+)\"\\)").find(line)!!.groupValues[1],
                    "<unclassified line ${index + 1}>",
                )
            }
        }
    }

    return DeclaredGraph(
        includes,
        edges,
        blessedDerivedEdges(),
    )
}

private val DERIVED_ADD = Regex(
    "^add\\s*\\(\\s*([\\w.]+)\\.name\\s*,\\s*project\\(\":([\\w:]+)\"\\)\\s*\\)\\s*$",
)

private val INLINE_CALL = Regex(
    "^([A-Za-z]\\w*)\\s*\\(\\s*project\\(\":([\\w:]+)\"\\)\\s*\\)\\s*$",
)

private fun blessedDerivedEdges(): Set<Pair<String, String>> = setOf(
    ":integration:generated-compile" to ":tools:cli",
    ":integration:generated-compile" to ":engine:test-support",
)
