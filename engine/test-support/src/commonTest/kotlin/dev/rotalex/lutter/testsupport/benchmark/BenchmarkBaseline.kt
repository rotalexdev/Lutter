package dev.rotalex.lutter.testsupport.benchmark

/**
 * The committed baseline's format, and the judgement about what a baseline is for.
 *
 * ### The claim this file is willing to make
 *
 * PLAN §36.5 asks for "recorded baselines committed", and §29.3 asks for nightly alerts on a
 * >20% regression. Both rest on an assumption worth testing: **that a wall-clock number means
 * the same thing on two machines.** It does not, and the size of the gap is not a rounding
 * error — a CI runner on `ubuntu-latest` is a shared, throttled, virtualized host whose
 * single-threaded performance can move by a factor between one build and the next.
 *
 * So a committed millisecond is, strictly, a fact about *the machine that recorded it* and
 * about nothing else. Committing one and asserting against it is a red build on a busy runner
 * and a green build on an idle laptop, which is the worst kind of test there is: it fails when
 * the engineer did nothing and passes when they did.
 *
 * **This file therefore reads two different kinds of fact out of the baseline, and only one of
 * them is a time.**
 *
 *   * **The workload.** Seed, node target, id prefixes, base package. Every one of those is a
 *     property of the *code*, not of the machine, and `LargeDocumentTest` asserts the baseline
 *     agrees with what the composer actually builds. This is the portable half, and it is the
 *     half that makes a comparison meaningful at all: a regression report that cannot say what
 *     was measured is a number, and a number without a workload is folklore.
 *   * **The timings**, in nanoseconds, next to a `machine` line whoever ran the harness had to
 *     fill in. They are only ever compared **as a ratio against another measurement of the
 *     same workload on the same machine**, never as an absolute threshold.
 *
 * The copy in this repository records no timings at all: every `baselineNs` is `0`, meaning
 * unrecorded. That is the honest state of this work unit and it is not an oversight. §36.5
 * says "recorded"; recording needs a run, and this work unit could not run anything — there is
 * no JDK and no Gradle in the authoring environment. **Inventing plausible milliseconds would
 * be the worst thing this file could do**, because a fabricated baseline is not a missing
 * check, it is a check that passes: the regression alert W7 builds on top of this would
 * compare against numbers that were never measured. `0` says "nobody has measured this yet",
 * which is the truth, and the harness prints the text to paste when someone with a machine
 * runs it.
 *
 * ### Why integers and nanoseconds
 *
 * `System.nanoTime()` is what the harness reads and it counts nanoseconds, so the file stores
 * nanoseconds and does no arithmetic that could lose a digit. Milliseconds are what §29 states
 * and what a reader wants; they are a rendering, not the record. Integers also mean the file has
 * no locale, no exponent and no float spelling, so a diff of two baselines is a diff of
 * integers — the only kind of diff that survives a reviewer skimming it.
 *
 * ### Why a line-per-fact format and not JSON
 *
 * `kotlinx.serialization` is on this module's classpath through `:engine:serialization` and
 * could have written this as a `data class`. It is hand-parsed instead because **the parser is
 * thirty lines and the format is five keys plus two per scenario**, and because a performance
 * baseline is read far more often by a person skimming a `git diff` than by a program.
 * `key value` on each line diffs as one changed line; a JSON object re-serialised by a tool
 * that indents differently is a whole-file diff over nothing. The same argument `CodegenCorpus`
 * gives for restating §16.3's rules rather than reading them back out of `KtPrinter`:
 * a check that shares the implementation's data shares its bugs.
 *
 * @see Scenario for the rows the two `*.targetMs` and `*.baselineNs` keys are named after.
 */
internal object BenchmarkBaseline {

    /** Where the committed baseline lives, addressed from the test classpath root. */
    const val RESOURCE: String = "benchmarks/baseline.txt"

    /** The header line a baseline opens with. A format change bumps the number rather than guessing. */
    const val VERSION: String = "forge-benchmark-baseline 1"

    /** What a `baselineNs` key holds when nothing has ever been recorded. Any `0` or less means this. */
    const val UNRECORDED_NS: Long = 0L

    /**
     * [text] as a key-to-value map, with the version header, blank lines and comments dropped.
     *
     * Comments are skipped rather than being a parse error so the file can carry the prose
     * above without needing a schema for comments. A **malformed** line — one that is not
     * `key value` — is an error, because a key silently dropped is a scenario silently
     * unmeasured, which is the exact failure a baseline exists to prevent.
     *
     * @throws IllegalStateException naming the line's number and text, so a broken baseline
     *   says which line rather than surfacing as "scenario Decode has no recorded time".
     */
    fun parse(text: String): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        text.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line == VERSION) return@forEachIndexed
            val split = line.indexOf(' ')
            if (split <= 0 || split == line.lastIndex) {
                error("benchmark baseline line ${index + 1} is not 'key value': '$raw'")
            }
            entries[line.substring(0, split)] = line.substring(split + 1).trim()
        }
        return entries
    }

    /** The key that carries [scenario]'s recorded time. */
    fun nanosKey(scenario: Scenario): String = scenario.name.lowercase() + ".baselineNs"

    /** The key that carries [scenario]'s §29 ceiling. */
    fun targetKey(scenario: Scenario): String = scenario.name.lowercase() + ".targetMs"

    /** [scenario]'s recorded time in nanoseconds, or [UNRECORDED_NS] when there is none. */
    fun recordedNanos(entries: Map<String, String>, scenario: Scenario): Long =
        entries[nanosKey(scenario)]?.toLongOrNull() ?: UNRECORDED_NS

    /** [scenario]'s recorded time in milliseconds, or `null` when nothing has been recorded. */
    fun recordedMillis(entries: Map<String, String>, scenario: Scenario): Double? {
        val nanos = recordedNanos(entries, scenario)
        return if (nanos <= UNRECORDED_NS) null else nanos / NANOS_PER_MILLI
    }

    /** The workload keys, and what they have to agree with. Kept beside the reader so a rename is one edit. */
    val WORKLOAD_KEYS: List<String> = listOf("baseSeed", "targetNodes", "nodeIdPrefix", "pageIdPrefix", "basePackage")

    /**
     * The text to write into the baseline file, from one run's [recordings].
     *
     * Takes the measurements rather than reading them back out of [entries], so `record` mode
     * prints a file that is *about this run* and not a re-render of the committed one. The
     * workload keys are copied from [entries] for the same reason they are in the file at all:
     * the file is what records the workload, so a record run must not quietly restate it.
     *
     * @param machine a description of the host, which the caller has to supply. There is no
     *   honest default — `os.name` names an operating system and not a machine, and a baseline
     *   whose `machine` line says "Linux" is not comparable with anything.
     */
    fun render(machine: String, entries: Map<String, String>, recordings: Map<Scenario, Long>): String {
        val header = buildString {
            append("# Forge benchmark baseline — PLAN §29's five rows, on a document of at least ")
            append(BENCHMARK_TARGET_NODES).append(" nodes.\n")
            append("#\n")
            append("# Recorded by the harness; BenchmarkBaseline.kt is the reader and the argument.\n")
            append("# The baselineNs values are nanoseconds and are only comparable with another\n")
            append("# run of this same workload on the machine named below.\n")
            append("#\n")
            append(VERSION).append('\n')
            for (key in WORKLOAD_KEYS) {
                append(key).append(' ').append(entries[key] ?: error("the committed baseline has no '$key'")).append('\n')
            }
            append("machine ").append(machine).append('\n')
        }
        val rows = Scenario.values().joinToString(separator = "") { scenario ->
            targetKey(scenario) + " " + scenario.targetMillis + "\n" +
                nanosKey(scenario) + " " + (recordings[scenario] ?: UNRECORDED_NS) + "\n"
        }
        return header + rows
    }

    private const val NANOS_PER_MILLI: Double = 1_000_000.0
}