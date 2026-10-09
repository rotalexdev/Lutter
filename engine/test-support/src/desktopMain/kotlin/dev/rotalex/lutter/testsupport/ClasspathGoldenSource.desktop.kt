package dev.rotalex.lutter.testsupport

/**
 * The Desktop reading of [classpathGoldenSource]. See the `androidMain` copy, which is the
 * same function for the same reason.
 */
public actual fun classpathGoldenSource(): GoldenSource = GoldenSource { path ->
    Golden::class.java.getResourceAsStream("/$path")?.use { it.readBytes().decodeToString() }
}