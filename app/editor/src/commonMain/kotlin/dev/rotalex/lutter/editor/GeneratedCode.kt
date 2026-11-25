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
) {

    /**
     * Whether there is nothing to read.
     *
     * Not the same as a failure: the generator returns no files *with* diagnostics when it
     * refuses, so the screen shows the reason instead of an empty list, and the two facts travel
     * together rather than as a sealed pair with an arm the caller cannot construct.
     */
    val isEmpty: Boolean get() = files.isEmpty()
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
    val result = KotlinGenerator(schema, CodegenOptions(packageName)).generate(resolved)
    return CodeView(result.files.files, result.diagnostics)
}
