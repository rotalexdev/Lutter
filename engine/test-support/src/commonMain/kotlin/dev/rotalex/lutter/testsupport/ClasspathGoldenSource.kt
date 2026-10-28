package dev.rotalex.lutter.testsupport

/**
 * A [GoldenSource] that reads a golden from the classpath, for every target.
 *
 * The fixtures live in a consuming module's `src/commonTest/resources`, which KMP puts on
 * the test runtime classpath of every target (decision A6), so this is the one reader that
 * needs no per-target source set and no platform file access: a classpath resource is the
 * same lookup on Android and on Desktop.
 *
 * ### Why this is `expect` and not a body
 *
 * Phase 0 keeps this module's `commonMain` free of every platform API, and
 * `NoPlatformApisInCommonMainTest` is what holds that line. A classpath is a JVM concept, so
 * the *lookup* cannot be written here; what can be written here is the decision, and the two
 * `actual` files carry one line each of mechanism. That is the same split [Golden]'s own
 * KDoc predicted, and it is why `Golden` takes a [GoldenSource] rather than resolving one
 * from a global: the seam is what lets the seam's implementation be platform code without
 * the policy being platform code.
 *
 * ### Which classpath
 *
 * The lookup is resolved from this module's own class rather than from the caller's, because
 * the caller is a test class this function cannot name from `commonMain`. On both current
 * targets the test runtime has one classloader holding the consuming module's resources, so
 * the two agree; the day a target separates them, the fix is a parameter here and nothing
 * else moves.
 *
 * ### Why a resource and not a `File`
 *
 * A `File` read at execution time is an untracked input, and
 * `org.gradle.configuration-cache.problems=fail` is set in both property files, so a golden
 * read through the filesystem is a build that fails on a runner whose working directory
 * differs from a developer's. A classpath resource travels with the compiled test.
 *
 * @see Golden, which takes the source this returns rather than owning one.
 */
public expect fun classpathGoldenSource(): GoldenSource