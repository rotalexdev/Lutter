package dev.rotalex.lutter.builtins.compose

import dev.rotalex.lutter.builtins.AlignmentSpec
import dev.rotalex.lutter.builtins.ShapeSpec
import dev.rotalex.lutter.builtins.enums.HorizontalArrangementSpec
import dev.rotalex.lutter.builtins.enums.VerticalArrangementSpec
import dev.rotalex.lutter.interpreter.action.IntrinsicHandlers
import dev.rotalex.lutter.interpreter.eval.Evaluator
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.schema.types.EnumTypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PLAN §36.2's coverage gate, read back through the registries rather than through any one
 * assembly: every builtin `ActionSpec` has a handler, and every enum entry has a Kotlin symbol
 * plus an explicit runtime mapping on every axis the runtime consumes it on.
 *
 * The action rows compare `BuiltinEngine.schema()`'s registered set against the handlers
 * `IntrinsicHandlers.handlers` builds, because a registered spec without its handler is the
 * failure `ui.showSnackbar` once slipped through: registration alone never failed, and the
 * executor answered the unhandled action with its own diagnostic.
 *
 * The renderer and emitter halves of §36.2's coverage claim stay where their halves live:
 * `BuiltinCoverageTest` walks `CodegenCoverage.check` and `ConformanceHarness.runtime`, and
 * `CodegenCoverage.actionProblems` holds the emitter's gate.
 */
class CoverageTests {

    /** The builtin engine's own registry walk, the same set renderers and emitters read. */
    private val schema = BuiltinEngine.schema()

    @Test
    fun `every registered action has a handler`() {
        val handlers = IntrinsicHandlers.handlers(Evaluator()).keys

        for (spec in schema.actions.all()) {
            assertTrue(
                spec.id in handlers,
                "'${spec.id.value}' is registered with no `IntrinsicHandler`, so its steps answer " +
                    "the executor's unhandled-action diagnostic",
            )
        }
    }

    @Test
    fun `no handler is registered without a spec`() {
        val registered = schema.actions.all().map { it.id }.toSet()
        val handlers = IntrinsicHandlers.handlers(Evaluator()).keys

        assertEquals(
            registered,
            handlers,
            "the handler set and the registered action set must be the same ids, or a handler " +
                "answers an action no document can name",
        )
    }

    @Test
    fun `every enum entry has a Kotlin symbol`() {
        for (spec in schema.types.all()) {
            val enums = spec as? EnumTypeSpec ?: continue

            for (entry in enums.entries) {
                val symbol = entry.kotlin
                assertTrue(
                    symbol.packageName.isNotBlank() && symbol.name.isNotBlank(),
                    "enum entry '${enums.id.value}.${entry.name}' carries a blank Kotlin symbol",
                )
            }
        }
    }

    @Test
    fun `the registered enum types are the four specs the runtimes map`() {
        val registered = schema.types.all()
            .filterIsInstance<EnumTypeSpec>()
            .map { it.id }
            .toSet()

        assertEquals(
            setOf(
                AlignmentSpec.Id,
                ShapeSpec.Id,
                VerticalArrangementSpec.Id,
                HorizontalArrangementSpec.Id,
            ),
            registered,
            "a fifth enum registered without a runtime mapping, or a spec declared without registration",
        )
    }

    @Test
    fun `every alignment entry is mapped explicitly on every axis`() {
        val entries = AlignmentSpec.entries.map { it.name }.toSet()

        assertEquals(entries, AlignmentMaps.verticalEntries, "the vertical axis is missing an entry")
        assertEquals(entries, AlignmentMaps.horizontalEntries, "the horizontal axis is missing an entry")
        assertEquals(entries, AlignmentMaps.boxEntries, "the box form is missing an entry")
    }

    @Test
    fun `every shape entry is mapped explicitly`() {
        assertEquals(
            ShapeSpec.entries.map { it.name }.toSet(),
            ShapeMaps.entries,
            "`ShapeMaps` is missing an entry `ShapeSpec` declares",
        )
    }

    @Test
    fun `every arrangement entry is mapped explicitly on its own axis`() {
        assertEquals(
            VerticalArrangementSpec.entries.map { it.name }.toSet(),
            ArrangementMaps.verticalEntries,
            "the vertical arrangement axis is missing an entry",
        )
        assertEquals(
            HorizontalArrangementSpec.entries.map { it.name }.toSet(),
            ArrangementMaps.horizontalEntries,
            "the horizontal arrangement axis is missing an entry",
        )
    }
}
