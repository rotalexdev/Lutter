package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic

/** One emitted file: its path under the generated root, and its full text. */
public data class GeneratedFile(public val path: String, public val content: String)

/**
 * The emission result: files sorted by path, so two runs over one document agree byte
 * for byte and reviews diff cleanly.
 */
public class GeneratedFiles(files: List<GeneratedFile>) {
    /** Every file, ordered by path. The constructor sorts; callers pass document order. */
    public val files: List<GeneratedFile> = files.sortedBy { it.path }

    override fun equals(other: Any?): Boolean =
        other is GeneratedFiles && other.files == files

    override fun hashCode(): Int = files.hashCode()

    override fun toString(): String = "GeneratedFiles(" + files.map { it.path }.toString() + ")"
}

/**
 * What generation produced: files when clean, diagnostics always.
 *
 * Empty files with errors is the refusal: the generator never emits half a screen.
 */
public data class CodegenResult(
    public val files: GeneratedFiles,
    public val diagnostics: List<Diagnostic>,
)

/** An internal invariant breach: a bug, never a document fault. Domain errors stay values. */
public class CodegenBug(message: String) : IllegalStateException(message)
