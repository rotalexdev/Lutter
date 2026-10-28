package dev.rotalex.lutter.testsupport.benchmark

import dev.rotalex.lutter.testsupport.Golden
import dev.rotalex.lutter.testsupport.classpathGoldenSource
import java.util.Locale
import kotlin.test.Test
import kotlin.test.fail

// PLAN §29's five rows, measured, on the JVM.
//
// ### Why this file is `desktopTest` and the scenarios are `commonTest`
//
// A clock is the one thing in a benchmark that cannot be written in `commonMain`. §21.3's
// `expect`/`actual` allow-list does name "benchmark clocks", so declaring one was available
// and was declined: the clock is four lines and it belongs on the JVM side, while the *work*
// being clocked is engine code that is identical on every target and has a definition all of
// them share (`BenchmarkScenarios.kt`). The split is what lets `LargeDocumentTest` execute all
// five bodies on the Android host test as well as on Desktop — not the timing, but the work
// the timing is a claim about, which is the part that is the same on both.
//
// §29's own header scopes the table to "JVM Desktop on a typical developer laptop", so a
// Desktop harness measures the target the plan named. **It says nothing about Android**:
// `androidUnitTest` runs on the host JVM, so its timings would be Desktop timings wearing an
// Android label, and this repository runs no device tests at all. What can honestly be said
// about the other target is the narrow one `LargeDocumentTest` makes — the same
// ten-thousand-node document analyses, round-trips and generates on both.
//
// ### Why nothing here runs unless it is asked for
//
// A benchmark that runs in `check` is a benchmark that eventually blocks a build, gets a
// threshold raised to unblock it, and then asserts nothing. `-Dforge.benchmark=<mode>` makes
// the cost opt-in:
//
//   * unset          — print one line saying nothing was measured, and return. The default.
//   * `measure`      — warm up, time each row, print the table. Nothing is asserted.
//   * `record`       — the same, and print the baseline file text to paste into
//     `src/commonTest/resources/benchmarks/baseline.txt`.
//   * `assert`       — the same, and fail if any row is more than [REGRESSION_RATIO] slower
//     than the committed baseline **for the same workload**.
//
// ### What makes an assertion here less flaky than a millisecond ceiling, and what it does not fix
//
// Four things, and they are worth separating from the things they do not fix.
//
//   * **The warm-up.** Three untimed runs per row before the first measurement, which is what
//     lets the JIT compile the decode path, the six analysis passes and the Kotlin printer
//     before anything is recorded. Timing the first call measures the compiler.
//   * **The median, not the mean.** Each row is repeated until a time budget is spent, and the
//     middle sample is what is reported. A single GC pause moves a mean and leaves a median
//     where it was; a five-sample benchmark's mean is not a measurement, it is a lottery.
//   * **The comparison is a ratio against the same machine's own baseline**, never an absolute
//     threshold. A ratio to a number taken on *this* host cancels out the host. §29's absolute
//     targets are printed for reference and deliberately not asserted — see
//     [BenchmarkBaseline] for why a committed millisecond is not a portable fact.
//   * **Enough samples for the row.** A fixed repeat count would be wrong in both directions —
//     twenty samples of a one-millisecond row is noise, two samples of a three-hundred-
//     millisecond row is a long pause. The loop runs until both a minimum count and a time
//     budget are met, which is the same reason [Measurement] bounds the upper end.
//
// What none of that fixes: **a machine that is busy for the whole run still produces a busy
// run.** A ratio against a baseline recorded on the same host under the same load is the only
// comparison that survives that, and a developer laptop at the time they ran `record` is not
// the load a CI runner will have tomorrow. That is why the regression alert is W7's nightly
// job on a dedicated runner, and why this harness does not pretend to be one.

/** §29.3's ">20% regression", as a multiplier. The ceiling for [Mode.Assert]. */
private const val REGRESSION_RATIO: Double = 1.20

/** Untimed runs per row before the first sample is taken. See the file KDoc. */
private const val WARMUP_REPEATS: Int = 3

/** How long each row is measured for, in nanoseconds. */
private const val MEASURE_BUDGET_NANOS: Long = 1_000_000_000L

/** Samples taken per row before the budget is allowed to end the loop. */
private const val MIN_REPEATS: Int = 20

/** Samples taken per row whatever the budget says. The bound on the one-millisecond row. */
private const val MAX_REPEATS: Int = 200

/** The system property naming the mode. */
private const val MODE_PROPERTY: String = "forge.benchmark"  // a system property, not a PropertyKey

/** What `-Dforge.benchmark=` asked for. */
private enum class Mode { Off, Measure, Record, Assert }

/** One row's samples, and what this run concluded from them. */
private class Measurement(
    val scenario: Scenario,
    val samples: List<Long>,
    val medianNanos: Long,
    val fastestNanos: Long,
) {

    /** The middle sample in milliseconds, which is what a table shows. */
    val medianMillis: Double get() = medianNanos / NANOS_PER_MILLI

    /** The fastest sample in milliseconds. Not a result — the noise floor. */
    val fastestMillis: Double get() = fastestNanos / NANOS_PER_MILLI

    /** How many samples the loop took. A row that hit [MAX_REPEATS] was too cheap to fill the budget. */
    val count: Int get() = samples.size

    /** One line: §29's row, §29's ceiling, and what this machine did. */
    fun report(recordedMillis: Double?): String {
        val ratio = recordedMillis?.let { medianMillis / it }
        return buildString {
            append(scenario.name).append('\n')
            append("    §29 target     ").append(scenario.targetMillis).append(" ms\n")
            append("    median         ").append(format(medianMillis)).append(" ms  (")
            append(count).append(" samples)\n")
            append("    fastest        ").append(format(fastestMillis)).append(" ms\n")
            append("    recorded       ").append(recordedMillis?.let { format(it) + " ms" } ?: "nothing recorded")
            append(ratio?.let { "  (ratio " + format(it) + ")" } ?: "")
            append('\n')
        }
    }
}

/**
 * The suite, and the one test in it.
 *
 * A single test rather than one per row because the rows share a ten-thousand-node document
 * and building it is the expensive part; five tests would build it five times to report five
 * lines that belong together anyway.
 */
class PerformanceHarnessTest {

    @Test
    fun `the five performance rows, measured on a ten thousand node document`() {
        val mode = readMode()
        if (mode == Mode.Off) {
            println(
                "[forge.benchmark] $MODE_PROPERTY is not set, so §29's five rows were not measured. " +
                    "Set -D$MODE_PROPERTY=measure, =record or =assert to run them.",
            )
            return
        }

        val composed = composeLargeDocument()
        val workload = BenchmarkWorkload(composed)
        val bodies = scenarioBodies(workload)
        val entries = BenchmarkBaseline.parse(Golden.read(BenchmarkBaseline.RESOURCE, classpathGoldenSource()))

        println("[forge.benchmark] ${machine()} (JVM Desktop; §29 scopes the table to this target)")
        println(
            "[forge.benchmark] workload: ${composed.describe()}, " +
                "${workload.bytes.size} canonical bytes; baseline machine '${entries["machine"]}'",
        )

        val measurements: Map<Scenario, Measurement> = bodies.associate { body ->
            body.scenario to measure(body)
        }

        println()
        for (body in bodies) {
            print(measurements.getValue(body.scenario).report(BenchmarkBaseline.recordedMillis(entries, body.scenario)))
        }

        if (mode == Mode.Record) {
            println("[forge.benchmark] baseline to commit:\n")
            print(BenchmarkBaseline.render(machine(), entries, measurements.mapValues { it.value.medianNanos }))
            return
        }

        if (mode == Mode.Assert) {
            assertAgainstBaseline(entries, measurements)
        }
    }

    /**
     * Every recorded row against this run, as a ratio.
     *
     * A row the baseline has never recorded is a failure and not a skip: asking to assert
     * against a baseline that records nothing is a misconfiguration, and quietly passing it
     * is how a suite stops checking anything while still being green.
     */
    private fun assertAgainstBaseline(
        entries: Map<String, String>,
        measurements: Map<Scenario, Measurement>,
    ) {
        val regressions = mutableListOf<String>()
        for (scenario in Scenario.values()) {
            val measurement = measurements.getValue(scenario)
            val recorded: Double? = BenchmarkBaseline.recordedMillis(entries, scenario)
            if (recorded == null) {
                regressions += "${scenario.name}: the baseline records no time for it (${BenchmarkBaseline.nanosKey(scenario)})"
                continue
            }
            val ratio = measurement.medianMillis / recorded
            if (ratio > REGRESSION_RATIO) {
                regressions += "${scenario.name}: " +
                    "${format(measurement.medianMillis)} ms against a recorded " +
                    "${format(recorded)} ms is ${format(ratio)}x, over the ${format(REGRESSION_RATIO)}x §29.3 allows"
            }
        }
        if (regressions.isEmpty()) return
        fail(
            "benchmark regressions against '$MODE_PROPERTY=assert' (baseline machine: " +
                "'${entries["machine"]}'):\n  " + regressions.joinToString("\n  ") +
                "\nRe-record with -D$MODE_PROPERTY=record if the change was intended.",
        )
    }
}

/**
 * [body]'s median and fastest sample, over a warm-up and a time budget.
 *
 * The loop's exit condition is the protocol, and it is worth reading twice: it ends when the
 * row has been measured for [MEASURE_BUDGET_NANOS] **and** [MIN_REPEATS] samples are in hand,
 * or when [MAX_REPEATS] is reached for a row too cheap to spend the budget on. A row that hit
 * the cap says so through [Measurement.count], and a five-hundred-millisecond row taking four
 * samples is a different kind of number from a one-millisecond row taking two hundred.
 */
private fun measure(body: ScenarioBody): Measurement {
    repeat(WARMUP_REPEATS) { body.run() }

    val samples = mutableListOf<Long>()
    val started = System.nanoTime()
    while (samples.size < MAX_REPEATS) {
        val before = System.nanoTime()
        body.run()
        samples += System.nanoTime() - before
        if (samples.size >= MIN_REPEATS && System.nanoTime() - started >= MEASURE_BUDGET_NANOS) break
    }

    val ordered = samples.sorted()
    return Measurement(
        scenario = body.scenario,
        samples = ordered,
        medianNanos = ordered[ordered.size / 2],
        fastestNanos = ordered.first(),
    )
}

/** The mode, and a refusal for a mode nobody wrote rather than a silent fallback. */
private fun readMode(): Mode = when (val asked = System.getProperty(MODE_PROPERTY)?.trim()) {
    null, "" -> Mode.Off
    "measure" -> Mode.Measure
    "record" -> Mode.Record
    "assert" -> Mode.Assert
    else -> fail("unknown -D$MODE_PROPERTY='$asked'; use measure, record or assert")
}

/**
 * A description of the host, for the baseline's `machine` line.
 *
 * `os.name` alone would say "Linux" and identify nothing: the point of the line is that two
 * baselines are only comparable when they were taken on the same machine, and "Linux" is every
 * machine this repository will ever run on.
 */
private fun machine(): String {
    val processors = Runtime.getRuntime().availableProcessors()
    return System.getProperty("os.name") + " " + System.getProperty("os.arch") +
        ", " + processors + " cpu thread(s), jvm " + System.getProperty("java.version")
}

/** One decimal, and a fixed decimal point, so two tables line up when printed beside each other. */
private fun format(millis: Double): String = String.format(Locale.ROOT, "%.1f", millis)

private const val NANOS_PER_MILLI: Double = 1_000_000.0