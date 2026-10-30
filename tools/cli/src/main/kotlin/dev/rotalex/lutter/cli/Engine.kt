package dev.rotalex.lutter.cli

import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.builtins.registerBuiltinActions
import dev.rotalex.lutter.builtins.registerBuiltinEnums
import dev.rotalex.lutter.builtins.registerBuiltinIconSets
import dev.rotalex.lutter.builtins.registerBuiltinFunctions
import dev.rotalex.lutter.builtins.registerBuiltinModifiers
import dev.rotalex.lutter.builtins.registerBuiltinSpecs
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import java.io.File

/**
 * Every engine symbol this CLI touches, in one file, on purpose.
 *
 * ### Why the whole surface is in one place
 *
 * PLAN §36.3 is the row *"`tools/cli` performs decode → validate → generate … using only public
 * APIs"*, and that row is a claim about a set of symbols rather than about a behaviour: nothing
 * at run time can observe whether a call resolved to a `public` declaration or to an
 * `internal` one, because `internal` compiles. So the claim is only auditable by reading, and a
 * claim that has to be assembled from the command files as well is a claim nobody assembles.
 *
 * Concentrating it here makes the audit a reading of one file, and the command files are left
 * describing only what they *do*. The list, as of this commit:
 *
 * | Symbol | Module | Visibility |
 * |---|---|---|
 * | `Analyzer`, `AnalysisResult` | `:engine:analysis` | `public` |
 * | `registerBuiltin{Specs,Modifiers,Enums,Functions,Actions}` | `:engine:builtins` | `public` |
 * | `Diagnostic`, `DiagnosticLocation`, `DiagnosticCode`, `Severity` | `:engine:model` | `public` |
 * | `UiDocument` | `:engine:model` | `public` |
 * | `Schema`, `ComponentSpec`, `ModifierSpec`, `ActionSpec`, `FunctionSpec`, `TypeSpec` | `:engine:schema` | `public` |
 * | `JsonDocumentCodec` | `:engine:serialization` | `public` |
 *
 * Nothing in that list is `internal`, and nothing carries `@EngineInternalApi` — that annotation
 * is declared in `:engine:model` and used nowhere in the engine, so there is no opt-in to
 * propagate. Nothing here comes from `:engine:test-support` either: the seeded document
 * generator is a test harness, and a command that reached for it would be shipping a fixture on
 * the user's classpath to produce a document a hand-written JSON file already describes.
 *
 * **The two things this file deliberately does *not* hold.** `KotlinGenerator` and `CodegenResult`
 * are engine symbols too, and they are named in `GenerateCommand.kt` rather than here, because
 * a `check` must not be able to reach them at all: keeping generation behind the one command
 * that generates is what makes "decode → validate → generate" three separable steps rather
 * than one function with a flag. And `:engine:serialization`'s configured `Json` instance is
 * `internal` to that module and is therefore unreachable from here — the CLI never needs it,
 * because it speaks the codec rather than encoding a document itself.
 */
internal object Engine {

    /**
     * The engine's schema for a document that uses only the built-in components.
     *
     * ### The slot order is not arbitrary
     *
     * `TypeSpec` in the last slot, `FunctionSpec` in the fourth and `ActionSpec` in the third,
     * or the three registrars cannot apply — each is an extension on `SchemaBuilder` with the
     * other four types fixed, and they agree only on this arrangement. The action slot is the
     * one slot where nothing has to name a spec yet: no pass reads it, so binding it changes
     * no document's fate today.
     *
     * **Built once per invocation and passed to both the analyzer and the generator.**
     * `Analyzer` resolves a document against a registry and `KotlinGenerator` emits against
     * one, and two separately built schemas are two objects that happen to hold the same specs
     * — which is a property nothing checks and nothing can check later. One instance makes
     * "the generator saw the registry the analysis resolved against" true by construction.
     */
    fun builtinSchema(): EngineSchema =
        Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
            registerBuiltinSpecs()
            registerBuiltinModifiers()
            registerBuiltinEnums()
            registerBuiltinIconSets()
            registerBuiltinFunctions()
            registerBuiltinActions()
        }

    /**
     * The document at [file], or a [CliFailure] saying why there is not one.
     *
     * Both refusals are [ExitCode.Unusable] and neither is [ExitCode.Rejected]. **A document
     * that does not exist and a document that is wrong are different facts**, and this is the
     * clearest place to keep them apart: a file the CLI never opened says nothing about the
     * document it was meant to hold, so a CI step that retries on [ExitCode.Unusable] and
     * fails on [ExitCode.Rejected] is doing the right thing in both cases.
     *
     * Strict decoding, deliberately. `DecodeOptions.lenient` is §19.4's escape hatch for a
     * client that must read a payload with unknown *envelope* fields, and nothing here needs
     * it: this CLI is being handed a file someone wrote for it, and a field it does not
     * recognise is exactly what the author needs told.
     *
     * @param command the command's name, for the message prefix, so a refusal reads
     *   `forge check: …` rather than naming whichever command happens to be calling.
     */
    fun readDocument(file: File, command: String): UiDocument {
        if (!file.isFile) {
            throw CliFailure(ExitCode.Unusable, "forge $command: no such file '${file.path}'")
        }
        return try {
            JsonDocumentCodec.decode(file.readBytes()).document
        } catch (failure: Exception) {
            // `UnsupportedFutureVersion` arrives here too, and its own message already names
            // both version numbers; re-wrapping it would replace that with "cannot decode".
            throw CliFailure(
                ExitCode.Unusable,
                "forge $command: cannot decode '${file.path}': ${failure.message}",
            )
        }
    }

    /** [document] analyzed against [schema]. */
    fun analyze(schema: EngineSchema, document: UiDocument): AnalysisResult =
        Analyzer(schema).analyze(document)

    /**
     * One finding, in the shape §17.2's catalog is read in.
     *
     * `Severity` is spelled out first so that a log of findings is greppable by how loud each
     * one was: the whole of what a caller has to branch on is the exit code, and this line is
     * the one a person reads to find out which of the findings caused it.
     */
    fun describe(diagnostic: Diagnostic): String {
        val where = diagnostic.location.nodeId?.toString()
            ?: diagnostic.location.pageId?.toString()
            ?: "document"
        return "${diagnostic.severity} ${diagnostic.code} at $where: ${diagnostic.message}"
    }

    /** Every finding, one per line, on [err]. Findings are never written to [out]. */
    fun report(diagnostics: List<Diagnostic>, err: Appendable) {
        for (diagnostic in diagnostics) {
            err.appendLine(describe(diagnostic))
        }
    }

    /**
     * How many findings there are, by severity.
     *
     * A record rather than three `count` calls on [Severity] at each call site, for the reason
     * this file exists: a command that spells `Severity.Error` is a command naming an engine
     * symbol, and keeping every mention of [Severity] in one place is what makes the audit
     * above a reading of this file rather than of the whole module. It also makes the summary
     * total by construction — three fields for three enum cases — instead of by remembering.
     */
    fun counts(diagnostics: List<Diagnostic>): DiagnosticCounts = DiagnosticCounts(
        errors = diagnostics.count { it.severity == Severity.Error },
        warnings = diagnostics.count { it.severity == Severity.Warning },
        info = diagnostics.count { it.severity == Severity.Info },
    )

    /** Whether any of [diagnostics] is a `Severity.Error`. The whole of the exit-code rule. */
    fun hasErrors(diagnostics: List<Diagnostic>): Boolean =
        diagnostics.any { it.severity == Severity.Error }
}

/**
 * Findings per severity, for a one-line summary.
 *
 * @property info counted and printed even though no pass emits it today, so that a severity
 *   appearing later shows up in the summary instead of vanishing from it.
 */
internal data class DiagnosticCounts(
    val errors: Int,
    val warnings: Int,
    val info: Int,
)

/**
 * The five spec types [EngineSchema] binds, in the order it binds them.
 *
 * Named once because the order is a fact rather than a preference — see
 * [Engine.builtinSchema] — and three signatures each spelling it out is three chances to spell
 * it differently, which the compiler would not catch and the registrars would.
 */
internal typealias EngineSchema =
    Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>
