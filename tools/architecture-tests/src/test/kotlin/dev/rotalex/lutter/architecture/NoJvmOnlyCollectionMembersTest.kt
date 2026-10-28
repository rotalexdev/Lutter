package dev.rotalex.lutter.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * No `commonMain` calls a collection member that only exists on the JVM.
 *
 * PLAN §21.1 asked the pure modules for an `iosSimulatorArm64` and a `wasmJs` target, and the
 * Wasm compiler was what held the line: `owners.putIfAbsent(name, page.name)` in
 * `ReservedCodegenNames.kt` is `java.util.Map`'s Java 8 default method reached through interop,
 * so it compiled for desktop and failed on `:engine:codegen:compileKotlinWasmJs` alone.
 *
 * That call carries no `java.` import, which is the part that matters here.
 * `NoPlatformApisInCommonMainTest` reads `imports`, so it never saw this and would not have.
 * With the Wasm target gone there is no compiler left to see it either, so the check is here:
 * it reads `KotlinFile.text`, because a member reached through interop is written in the
 * expression and nowhere else.
 */
class NoJvmOnlyCollectionMembersTest {

    @Test
    fun `common code does not call a jvm-only collection member`() {
        val violations = RepositoryRoot
            .commonMainDirectories()
            .flatMap { directory ->
                RepositoryRoot
                    .kotlinFilesIn(directory)
                    .flatMap { file ->
                        SourceRules
                            .jvmOnlyCollectionMemberOccurrences(file.text)
                            .map { occurrence -> "${file.name} in $directory: $occurrence" }
                    }
            }

        assertTrue(violations.isEmpty()) { "unexpected violations:\n" + violations.joinToString("\n") }
    }

    /**
     * The negative test, because a rule that cannot fail is indistinguishable from a rule that
     * does not work. Every other guardrail in this module carries one.
     */
    @Test
    fun `the shape this repository actually shipped is rejected`() {
        // The line from `ReservedCodegenNames.kt`, verbatim. `owners` is a `MutableMap`, and
        // `putIfAbsent` is on `java.util.Map` and on nothing in the common stdlib.
        assertEquals(1, SourceRules.jvmOnlyCollectionMemberOccurrences("owners.putIfAbsent(name, page.name)").size)
        assertEquals(1, SourceRules.jvmOnlyCollectionMemberOccurrences("seen.merge(id, page) { a, b -> a }").size)
        assertEquals(1, SourceRules.jvmOnlyCollectionMemberOccurrences("table.computeIfAbsent(k) { newNode() }").size)
        // Two of the nine take their functional interface as a trailing lambda, so they have no
        // parentheses. A rule that required `(` covers the other seven and misses these two.
        assertEquals(1, SourceRules.jvmOnlyCollectionMemberOccurrences("owners.removeIf { it.isStale() }").size)
        assertEquals(1, SourceRules.jvmOnlyCollectionMemberOccurrences("entries.replaceAll { k, v -> v }").size)
        assertEquals(1, SourceRules.jvmOnlyCollectionMemberOccurrences("rows.getOrDefault(i, fallback)").size)

        // `forEach` is the one member whose shape has to be read rather than matched, because
        // Kotlin's common `forEach` and Java's `forEach` differ only in arity.
        assertEquals(1, SourceRules.jvmOnlyCollectionMemberOccurrences("entries.forEach { key, value -> sink(key) }").size)

        // And the report carries the line, so a failure names the line rather than the file.
        assertEquals(
            "putIfAbsent is a JVM-only collection member with no common equivalent (line 2)",
            SourceRules
                .jvmOnlyCollectionMemberOccurrences("val owners = mutableMapOf<String, String>()\nowners.putIfAbsent(k, v)")
                .single(),
        )
    }

    @Test
    fun `a declaration and the common spellings are both left alone`() {
        // The dot is what keeps a declaration out of the rule: this is an ordinary domain
        // method and it resolves on every target.
        assertEquals(0, SourceRules.jvmOnlyCollectionMemberOccurrences("fun merge(other: Overlay): Overlay = other").size)
        assertEquals(0, SourceRules.jvmOnlyCollectionMemberOccurrences("override fun compute(): Int = 0").size)

        // `getOrPut` is what `putIfAbsent` was replaced with, and it is in three files today.
        assertEquals(0, SourceRules.jvmOnlyCollectionMemberOccurrences("owners.getOrPut(name) { page.name }").size)
        assertEquals(0, SourceRules.jvmOnlyCollectionMemberOccurrences("owners.getOrElse(name) { page.name }").size)

        // `forEach` over a Kotlin collection. Every one of these is in `commonMain` right now,
        // and the destructured pair is the common `Map.forEach` — it takes one `Map.Entry`,
        // which is exactly why the parenthesised spelling must not match.
        assertEquals(0, SourceRules.jvmOnlyCollectionMemberOccurrences("items.forEach { scan(it) }").size)
        assertEquals(0, SourceRules.jvmOnlyCollectionMemberOccurrences("entries.forEach { (key, value) -> register(key, value) }").size)
        assertEquals(0, SourceRules.jvmOnlyCollectionMemberOccurrences("items.forEachIndexed { index, item -> scan(index, item) }").size)
        assertEquals(0, SourceRules.jvmOnlyCollectionMemberOccurrences("items.forEach { emit(it) } // and a forEach later").size)
    }

    /**
     * The reason the scan strips comments and literals before it looks at anything, and the
     * reason this rule is not a nuisance.
     *
     * This codebase explains itself in KDoc, so a paragraph about `putIfAbsent` is
     * character-for-character the call the rule has to report. Every line below is prose that a
     * rule reading raw text would fail the build on, and each of them is the kind of line this
     * repository writes dozens of.
     */
    @Test
    fun `a member named in prose is prose and not a call`() {
        assertEquals(0, SourceRules.jvmOnlyCollectionMemberOccurrences("// owners.putIfAbsent is a JVM default method").size)
        assertEquals(0, SourceRules.jvmOnlyCollectionMemberOccurrences(" * `owners.putIfAbsent` is not a common member").size)

        // A block comment, and one that runs across lines, because a KDoc is exactly that.
        assertEquals(
            0,
            SourceRules
                .jvmOnlyCollectionMemberOccurrences(
                    """
                    /*
                     * Replaced putIfAbsent with getOrPut.
                     * entries.forEach { key, value -> register(key, value) }
                     */
                    """.trimIndent(),
                ).size,
        )

        // A quoted string. `//` and `/*` inside it are string content, and the scanner has to
        // agree: a per-line regex chain sees the `//` in a URL and takes the rest of the line
        // for a comment, which is why this is one left-to-right pass and not three replaces.
        assertEquals(0, SourceRules.jvmOnlyCollectionMemberOccurrences("""val url = "https://example.com/putIfAbsent" """).size)
        assertEquals(0, SourceRules.jvmOnlyCollectionMemberOccurrences("""val doc = "call getOrDefault(k, v) here" """).size)

        // And the raw-string case, which is also the rule's one stated limitation: the whole
        // literal is opaque, so a member inside an interpolation is not seen. The triple quote
        // is assembled rather than written, because a raw string cannot spell one.
        val tripleQuote = "\"\"\""
        val rawString = "val code = " + tripleQuote + "\${owners.putIfAbsent(k, v)}" + tripleQuote
        assertEquals(0, SourceRules.jvmOnlyCollectionMemberOccurrences(rawString).size)

        // Stripping must not eat code. A member before and after a comment on separate lines is
        // two findings, not zero and not one.
        assertEquals(2, SourceRules.jvmOnlyCollectionMemberOccurrences("a.putIfAbsent(1, 2)\n// nothing\nb.putIfAbsent(3, 4)").size)
    }

    /**
     * Stripping must not move a line either.
     *
     * This is the bug the first draft of the stripper shipped with, and no unit test caught it:
     * skipping a block comment without counting its newlines leaves every reported line short by
     * however long the comment was. On `ReservedCodegenNames.kt` — which opens with a KDoc, as
     * every file here does — the real call on line 80 was reported as line 47. The rule still
     * failed the build, which is what hid it: a wrong line number is only wrong to the person
     * who goes and looks.
     */
    @Test
    fun `a member after a long comment is still reported on its own line`() {
        val file = buildString {
            appendLine("package dev.rotalex.lutter.codegen")
            appendLine()
            appendLine("/**")
            appendLine(" * A KDoc long enough that losing its newlines moves every line below it.")
            appendLine(" */")
            appendLine("internal class ReservedCodegenNames {")
            appendLine("    fun reserve(name: String) = owners.putIfAbsent(name, name)")
            appendLine("}")
        }

        assertEquals(
            "putIfAbsent is a JVM-only collection member with no common equivalent (line 7)",
            SourceRules.jvmOnlyCollectionMemberOccurrences(file).single(),
        )

        // The same holds across a raw string, which is the other construct that spans lines.
        val generated = "val code = \"\"\"\nval a = 1\n\"\"\"\nowners.removeIf { it.isEmpty() }\n"
        assertEquals(
            "removeIf is a JVM-only collection member with no common equivalent (line 4)",
            SourceRules.jvmOnlyCollectionMemberOccurrences(generated).single(),
        )
    }
}