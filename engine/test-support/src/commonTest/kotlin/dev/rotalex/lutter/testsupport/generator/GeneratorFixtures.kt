package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.serialization.JsonDocumentCodec

/**
 * What the four generator tests share, and why it is this little.
 *
 * ### One schema, built once
 *
 * `generatorSchema()` rebuilds every registry on every call, which is right for a library and
 * wasteful for a test that runs it two hundred times inside a loop. One `val` here is the whole
 * saving, and it is a file-level `val` rather than an `object` member for the reason
 * `NoMutableObjectStateTest` exists: a shared schema is immutable, and a `var` beside it would
 * not be.
 *
 * ### The seeds
 *
 * A fixed, contiguous, zero-based range rather than a draw. A property test's seed has two jobs —
 * reproducibility and coverage — and a contiguous range gives both without a generator of its
 * own: seed 17 is the same document on every machine and on every run, and a hundred consecutive
 * integers are a hundred *different* documents rather than a hundred draws that might collide.
 *
 * The range is a parameter of each test rather than one shared constant because these four tests
 * are also the coverage measurement: determinism needs a handful of seeds, validity needs enough
 * to be worth believing, and coverage needs enough to have seen every registered component.
 * Keeping them apart is what lets a later work unit widen one without widening the others.
 *
 * ### [renderGenerated]
 *
 * Phase 9 learned this the hard way: `NodeTable.toString()` is `NodeTable(size=13)`, so an
 * `assertEquals` over two `UiDocument`s that differ reports two renderings which are byte
 * identical. Every failure in this package therefore quotes the **seed** first — that is the
 * replay handle — and then the document in full, with every field of every row delegated to the
 * record's own `toString()` rather than to a hand-listed subset that goes stale silently when a
 * field is added.
 */
internal val generatorTestSchema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> =
    generatorSchema()

/** One document for [seed], built through a fresh generator exactly as a caller would. */
internal fun generatedDocument(seed: Long): GeneratedDocument =
    DocumentGenerator(generatorTestSchema, seed).generate()

/** [count] consecutive seeds from zero: `0L` up to but not including [count]. */
internal fun seeds(count: Int): List<Long> = (0L until count.toLong()).toList()

/** The canonical bytes a document round-trips through, which is what determinism is about. */
internal fun encodedBytes(document: UiDocument): ByteArray = JsonDocumentCodec.encode(document)

/**
 * The whole document and its plan as text, for a failure message.
 *
 * Rows in ascending id order so two renderings line up for reading, and every field of every row
 * included so nothing can differ without being visible. `document.app.navigation` is the one
 * field left out: `NavigationSpec` compares equal by class and keeps the identity `toString`, so
 * printing it would manufacture a difference that is not there — the same omission
 * `renderDocument` in `:engine:editing` documents at greater length.
 */
internal fun renderGenerated(generated: GeneratedDocument): String = buildString {
    val document = generated.document
    append("seed          ").append(generated.seed).append('\n')
    append("plan          ").append(generated.plan.describe()).append('\n')
    append("meta          ").append(document.meta).append('\n')
    append("app           ").append(document.app.packageName)
    append(" startPage=").append(document.app.startPage).append('\n')
    append("pages         ")
    append(document.pages.values.sortedBy { it.id.value }.joinToString(" | ")).append('\n')
    append("components    ")
    append(document.components.values.sortedBy { it.id.value }.joinToString(" | ")).append('\n')
    append("appState      ").append(document.appState).append('\n')
    append("dataModels    ").append(document.dataModels).append('\n')
    append("enums         ").append(document.enums).append('\n')
    append("hostFunctions ").append(document.hostFunctions).append('\n')
    append("themes        ").append(document.themes).append('\n')
    append("theme         ").append(document.theme).append('\n')
    append("nodes\n")
    for (id in document.nodes.ids()) {
        append("  ").append(document.nodes.require(id)).append('\n')
    }
}
