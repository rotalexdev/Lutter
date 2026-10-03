package dev.rotalex.lutter.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * No declaration carries a name the JVM refuses.
 *
 * The compiler accepts a backticked identifier with almost any character in it and then the
 * JVM backend rejects it, which fails the entire module's test source set rather than the one
 * test that carries the name. A dotted function name has cost this repository four CI
 * round-trips; each one was a five-minute build whose entire content was one character.
 */
class NoIllegalJvmNameTest {

    @Test
    fun `no declaration carries a name the jvm refuses`() {
        val violations = RepositoryRoot
            .engineSourceDirectories()
            .flatMap { directory ->
                RepositoryRoot
                    .kotlinFilesIn(directory)
                    .flatMap { file ->
                        SourceRules
                            .illegalJvmNameOccurrences(file.text)
                            .map { occurrence -> "${file.name} in $directory: $occurrence" }
                    }
            }

        assertTrue(violations.isEmpty()) { "unexpected violations:\n" + violations.joinToString("\n") }
    }

    /**
     * The negative test, because a rule that cannot fail is indistinguishable from a rule that
     * does not work. Every other guardrail in this module carries one and two of them exist
     * because theirs caught a false negative during review.
     */
    @Test
    fun `a name the jvm refuses is rejected in every spelling`() {
        assertEquals(1, SourceRules.illegalJvmNameOccurrences("fun `num.format`(v: Int) {}").size)
        assertEquals(1, SourceRules.illegalJvmNameOccurrences("private fun `a/b`() {}").size)
        assertEquals(1, SourceRules.illegalJvmNameOccurrences("internal class `A<B` {}").size)
        assertEquals(1, SourceRules.illegalJvmNameOccurrences("object `x;y` {}").size)

        // The shape this repository actually shipped twice: a test name that spells a dotted
        // function call. It reads as prose and it is the exact bug — a name is a name
        // wherever the author found it natural to write one.
        assertEquals(1, SourceRules.illegalJvmNameOccurrences("fun `reads a num.format call`() {}").size)
    }

    @Test
    fun `an ordinary name and a backticked prose reference are both left alone`() {
        assertEquals(0, SourceRules.illegalJvmNameOccurrences("fun ordinary(v: Int) {}").size)
        assertEquals(0, SourceRules.illegalJvmNameOccurrences("fun <T> generic(t: T) {}").size)

        // A space is legal and every test name in this repository uses one, so a rule that
        // rejected it would report the entire codebase. It is the false positive this rule
        // nearly shipped with.
        assertEquals(0, SourceRules.illegalJvmNameOccurrences("fun `a name with spaces`() {}").size)

        // The two false positives this rule could plausibly grow: a dotted name inside a
        // comment or a KDoc line, which is how this codebase writes about most things.
        assertEquals(0, SourceRules.illegalJvmNameOccurrences("// fun `num.format` is a template").size)
        assertEquals(0, SourceRules.illegalJvmNameOccurrences(" * `num.format` needs an Int").size)
    }
}
