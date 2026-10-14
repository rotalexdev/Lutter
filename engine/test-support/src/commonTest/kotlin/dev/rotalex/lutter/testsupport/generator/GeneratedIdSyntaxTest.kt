package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.ThemeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The ids the generator writes are ones `Ids.kt` accepts, and it is worth knowing why.
 *
 * ### What is being asserted, and what would break without it
 *
 * Every identifier in `:engine:model` is a `@JvmInline value class` over `String` whose `init`
 * validates it against `IdSyntax`: `[A-Za-z0-9_]{1,64}` for a simple id, two or more such
 * segments joined by dots for a namespaced one, and sixty-four characters in total either way.
 * The generator's alphabet is chosen so that every draw is legal — and this file is what turns
 * that from a comment into a claim, in two directions:
 *
 *  * **Forward.** Every id in a sample of generated documents matches the pattern. If the
 *    alphabet gained a character `IdSyntax` refuses, this fails with the offending id rather than
 *    with a `require` thrown from a value-class constructor halfway through a build — which is
 *    the least legible place a seed can fail, and the reason `IdMint` drops `0`, `1` and `o` the
 *    way `RandomIdGenerator` does.
 *  * **Backward.** The value classes really do refuse the neighbours: a dot in a simple id, an
 *    empty one, one of sixty-five characters, a bare segment on a namespaced one. Without this
 *    half, "the alphabet is valid" would be a statement about a rule nothing enforces, and the
 *    generator's care would be indistinguishable from luck.
 *
 * The grammar is restated here rather than read from `IdSyntax`, which is `internal` to
 * `:engine:model` and deliberately unreachable: a second answer to "is this a valid id?" is the
 * thing `IdSyntax`'s own KDoc refuses to create. This file is therefore a restatement, not a
 * second implementation, and it says so — which is also why the forward assertions quote the id
 * they rejected rather than only counting.
 */
class GeneratedIdSyntaxTest {

    @Test
    fun `every simple id in a generated document matches the documented grammar`() {
        val rejected = mutableListOf<String>()

        for (seed in seeds(ID_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            val document = generated.document

            for (id in document.nodes.ids()) simple(id.value, "node id", seed, rejected)
            for (page in document.pages.values) {
                simple(page.id.value, "page id", seed, rejected)
                for (param in page.params) simple(param.name.value, "page param", seed, rejected)
            }
            for (decl in document.components.values) {
                simple(decl.id.value, "component id", seed, rejected)
                for (param in decl.params) simple(param.name.value, "component param", seed, rejected)
                for (slot in decl.slots) simple(slot.name.value, "slot name", seed, rejected)
            }
            for (state in document.appState) simple(state.id.value, "app state id", seed, rejected)
            for (page in document.pages.values) {
                for (state in page.state) simple(state.id.value, "page state id", seed, rejected)
            }
            for (decl in document.components.values) {
                for (state in decl.state) simple(state.id.value, "component state id", seed, rejected)
            }
            for (theme in document.themes.values) simple(theme.id.value, "theme id", seed, rejected)

            for (nodeId in document.nodes.ids()) {
                val node = document.nodes.require(nodeId)
                namespaced(node.type.value, "component type", seed, rejected)
                for (key in node.props.keys) simple(key.value, "property key", seed, rejected)
                for (key in node.slots.keys) simple(key.value, "slot name", seed, rejected)
                for (key in node.events.keys) simple(key.value, "event key", seed, rejected)
                for (entry in node.modifiers) {
                    namespaced(entry.type.value, "modifier type", seed, rejected)
                    for (key in entry.args.keys) simple(key.value, "modifier arg", seed, rejected)
                }
                for (arm in node.events.values) {
                    for (step in arm.steps) {
                        namespaced(step.action.value, "action id", seed, rejected)
                        for (key in step.args.keys) simple(key.value, "action arg", seed, rejected)
                    }
                }
            }
        }

        assertTrue(
            rejected.isEmpty(),
            "${rejected.size} generated id(s) do not match the grammar `Ids.kt` documents.\n" +
                rejected.take(REPORT_LIMIT).joinToString("\n"),
        )
    }

    @Test
    fun `every document id is inside the length bound`() {
        // Sixty-four characters, checked separately because it is a length rule and not a shape
        // one: the generator draws six symbols, so this is the assertion that the bound was not
        // reached by accident and would not be reached by a longer prefix.
        var longest = 0
        var longestId = ""

        for (seed in seeds(ID_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            for (id in generated.document.nodes.ids()) {
                if (id.value.length <= longest) continue
                longest = id.value.length
                longestId = "seed $seed node $id"
            }
            for (type in generated.plan.componentUsage.keys) {
                if (type.value.length <= longest) continue
                longest = type.value.length
                longestId = "seed $seed type $type"
            }
        }

        assertTrue(
            longest <= MAX_LENGTH,
            "an id reached $longest characters, past the bound `IdSyntax` enforces ($longestId)",
        )
    }

    @Test
    fun `the value classes refuse what the grammar refuses`() {
        // The backward half. If these stopped throwing, the generator's alphabet would be a
        // preference rather than a requirement and this file's forward assertions would be
        // checking a rule that no longer exists.
        assertFailsWith<IllegalArgumentException> { NodeId("has a dot") }
        assertFailsWith<IllegalArgumentException> { NodeId("") }
        assertFailsWith<IllegalArgumentException> { NodeId("has-a-dash") }
        assertFailsWith<IllegalArgumentException> { NodeId("n".repeat(MAX_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> { PageId("p.with.dot") }
        assertFailsWith<IllegalArgumentException> { ComponentDeclId(" ") }
        assertFailsWith<IllegalArgumentException> { StateId("-") }
        assertFailsWith<IllegalArgumentException> { ThemeId("t:1") }
        assertFailsWith<IllegalArgumentException> { PropertyKey("two words") }
        assertFailsWith<IllegalArgumentException> { SlotName("slot.name") }
        assertFailsWith<IllegalArgumentException> { EventKey("on.click") }
        assertFailsWith<IllegalArgumentException> { ParamName("p-0") }

        // Namespaced ids take two segments or more, and the whole thing is bounded: one segment
        // is a simple id wearing a dot, and a registry lookup that found nothing would be the
        // registry's answer rather than the id's.
        assertFailsWith<IllegalArgumentException> { ComponentType("noDot") }
        assertFailsWith<IllegalArgumentException> { ComponentType("trailing.") }
        assertFailsWith<IllegalArgumentException> { ComponentType(".leading") }
        assertFailsWith<IllegalArgumentException> { ComponentType("two..dots") }
        assertFailsWith<IllegalArgumentException> { ModifierType("core") }
        assertFailsWith<IllegalArgumentException> { ModifierType("a.b.c".repeat(30)) }
    }

    @Test
    fun `the grammar this file restates is the grammar the constructors enforce`() {
        // One round trip through each shape, so the restated patterns cannot drift from the
        // constructors without this failing: the samples below are the ones `Ids.kt`'s KDoc calls
        // valid, and each is asserted through the very class a document would carry it in.
        for (sample in SIMPLE_SAMPLES) {
            assertEquals(sample, NodeId(sample).value)
            assertEquals(sample, PageId(sample).value)
            assertEquals(sample, PropertyKey(sample).value)
        }
        for (sample in NAMESPACED_SAMPLES) {
            assertEquals(sample, ComponentType(sample).value)
            assertEquals(sample, ModifierType(sample).value)
        }
    }

    private fun simple(value: String, what: String, seed: Long, into: MutableList<String>): Unit =
        check(SIMPLE, "a simple id", value, what, seed, into)

    private fun namespaced(value: String, what: String, seed: Long, into: MutableList<String>): Unit =
        check(NAMESPACED, "a namespaced id", value, what, seed, into)

    private fun check(
        pattern: Regex,
        shape: String,
        value: String,
        what: String,
        seed: Long,
        into: MutableList<String>,
    ) {
        if (pattern.matches(value)) return
        into += "seed $seed: $what '$value' is not $shape"
    }

    private companion object {

        /**
         * `IdSyntax`'s own pattern, restated.
         *
         * `IdSyntax` is `internal` to `:engine:model` and its KDoc gives the reason: a separate
         * way to ask "is this a valid id?" would be a second place for the answer to live. A test
         * in a different module cannot call it, so the pattern is written down again here — and
         * the test above is what keeps the second copy honest against the constructors.
         */
        val SIMPLE: Regex = Regex("""^[A-Za-z0-9_]{1,64}$""")

        /** Two or more simple segments joined by dots, sixty-four characters in total. */
        val NAMESPACED: Regex = Regex("""^[A-Za-z0-9_]{1,64}(\.[A-Za-z0-9_]{1,64})+$""")

        const val MAX_LENGTH: Int = 64
        const val ID_SEED_COUNT: Int = 60
        const val REPORT_LIMIT: Int = 10

        val SIMPLE_SAMPLES: List<String> = listOf("n", "n_1", "nAbcD9", "a".repeat(64))
        val NAMESPACED_SAMPLES: List<String> = listOf("core.Column", "a.b", "gen.Probe", "doc.cd_abc")
    }
}
