package dev.rotalex.lutter.testsupport

/**
 * The Android reading of [classpathGoldenSource].
 *
 * `Class.getResourceAsStream` rather than `ClassLoader.getResourceAsStream`, and a leading
 * slash on [path]: the two forms differ in exactly that, and `getResource` resolves a
 * relative name against the package directory rather than the classpath root. Goldens are
 * addressed from the root (`goldens/…`) so that one path means the same thing in the test and
 * in the source tree.
 *
 * [Golden] is the anchor rather than this file's own facade: an `expect fun` generates no
 * class of its own, and `Golden` is a real declaration in this module on every target, which
 * makes its classloader the one holding the consuming module's `commonTest` resources.
 *
 * Three lines of mechanism behind an `expect`. The duplication with the `desktopMain` copy is
 * the cost of a `commonMain` that stays free of platform APIs, and it is cheaper than the
 * alternative: a shared JVM source set would have to exist for exactly one function.
 */
public actual fun classpathGoldenSource(): GoldenSource = GoldenSource { path ->
    Golden::class.java.getResourceAsStream("/$path")?.use { it.readBytes().decodeToString() }
}