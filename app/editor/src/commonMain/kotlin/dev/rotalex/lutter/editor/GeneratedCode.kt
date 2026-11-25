package dev.rotalex.lutter.editor

import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.GeneratedFile
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec

/**
 * What the code screen shows for one document: files, or the diagnostics that stopped them.
 *
 * ### Why this is a value and not a composable
 *
 * The interesting half of "see the generated code" is that the generator is a pure function of the
 * document — the same one `:engine:codegen` already ships and the acceptance test already asserts.
 * Keeping the call here, as a value, means the claim "the editor's Code tab shows what the
 * generator emits" is a unit test with no window in it, and the composable is left with layout.
 *
 * ### Files, not one concatenated string
 *
 * The generator already returns the files sorted by path, and a document of any size emits several
 * (`App.kt`, one per screen, one per component). Concatenating them here would throw away the
 * paths — the only thing that tells a reader where the code they are looking at would live.
 */
internal data class CodeView(

    /** The emitted files, ordered by path: the generator's order, kept rather than re-sorted. */
    val files: List<GeneratedFile>,

    /** What the generator reported alongside. Empty on a clean document. */
    val diagnostics: List<Diagnostic>,

    /**
     * The generator threw, and this is what it said.
     *
     * A throw is not a refusal: refusals arrive as [diagnostics] with a location, and this is the
     * generator's own bug. It is carried rather than propagated because the caller is a screen —
     * a document that trips a bug in a pure function must cost the author a message, not the
     * composition they were in the middle of.
     */
    val failure: String? = null,
) {

    /**
     * Whether there is nothing to read.
     *
     * Not the same as a failure: the generator returns no files *with* diagnostics when it
     * refuses, so the screen shows the reason instead of an empty list, and the two facts travel
     * together rather than as a sealed pair with an arm the caller cannot construct.
     */
    val isEmpty: Boolean get() = files.isEmpty()

    /** Whether there is nothing to read *and* no explanation to read instead. */
    val isSilent: Boolean get() = files.isEmpty() && diagnostics.isEmpty() && failure == null
}

/**
 * [resolved] as Kotlin, under [packageName].
 *
 * The generator is built per call on purpose: it holds per-run scratch (the emitted state classes,
 * the theme, the resource declarations) and one instance reused across documents would be one
 * document's leftovers in another's output.
 */
internal fun generatedCode(
    schema: SchemaView<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
    resolved: ResolvedDocument,
    packageName: String,
): CodeView {
    return try {
        val result = KotlinGenerator(schema, CodegenOptions(packageName)).generate(resolved)
        CodeView(result.files.files, result.diagnostics)
    } catch (failure: IllegalStateException) {
        // `CodegenBug` and `CodegenCoverage.check` both throw `IllegalStateException`, and both are
        // the generator's defect rather than the document's. Catching it here is the difference
        // between a screen that says "the generator failed" and an app that closes on a tap.
        CodeView(failure = failure.message ?: "the generator threw ${failure::class.simpleName}")
    }
}
