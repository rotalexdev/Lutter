package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.builtins.functions.CoreFunctions
import dev.rotalex.lutter.builtins.functions.ListFunctions
import dev.rotalex.lutter.builtins.functions.NumberFunctions
import dev.rotalex.lutter.builtins.functions.StringFunctions
import dev.rotalex.lutter.interpreter.eval.FunctionImpl
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.function.FunctionPrecedence
import dev.rotalex.lutter.schema.function.FunctionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §10.3's coverage gate: every registered `FunctionSpec` has a `FunctionImpl`.
 *
 * A test and not a construction-time refusal because the two halves live in two registries that
 * two assemblies build separately — the schema registry has no `FunctionImpls` in sight and the
 * interpreter table has no `FunctionSpec` — so there is nowhere at run time that sees both. That
 * is also why the seed set declares its specs and its implementations as two separate lists: a
 * single list of pairs would make this gate unfailable, which is the opposite of useful.
 *
 * The name list is the other half of the job. §10.2 names fifteen functions, and a set that has
 * silently lost one is a plan and an engine that disagree about what a document may write.
 */
class BuiltinFunctionCoverageTest {

    @Test
    fun `the seed set is the fifteen names the plan names`() {
        val registered = seedSchema().functions.all().map { it.id.value }

        assertEquals(SEED_SET, registered.sorted(), "§10.2's seed set, by name")
    }

    @Test
    fun `every spec has an implementation`() {
        val implemented = declaredImpls().map { it.first }.toSet()

        for (spec in specs()) {
            assertTrue(
                spec.id in implemented,
                "'${spec.id.value}' has a FunctionSpec and no FunctionImpl",
            )
        }
    }

    @Test
    fun `no implementation is registered without a spec`() {
        val declared = specs().map { it.id }.toSet()

        for ((id) in declaredImpls()) {
            assertTrue(id in declared, "'${id.value}' has a FunctionImpl and no FunctionSpec")
        }
    }

    @Test
    fun `every spec reaches the schema and every implementation reaches the table`() {
        val registered = seedSchema().functions.all().map { it.id }.toSet()
        val table = builtinFunctionImpls()

        for (spec in specs()) {
            assertTrue(spec.id in registered, "registerBuiltinFunctions skipped '${spec.id.value}'")
            assertTrue(table[spec.id] != null, "builtinFunctionImpls skipped '${spec.id.value}'")
        }
    }

    @Test
    fun `every template binds exactly the arguments its signature declares`() {
        for (spec in specs()) {
            val used = PLACEHOLDER.findAll(spec.kotlin.pattern).map { it.groupValues[1].toInt() }.toSet()

            assertEquals(spec.params.indices.toSet(), used, "'${spec.id.value}' ${spec.kotlin.pattern}")
        }
    }

    @Test
    fun `every function is pure, which the MVP requires`() {
        for (spec in specs()) {
            assertTrue(spec.pure, "'${spec.id.value}' is declared impure")
        }
    }

    @Test
    fun `a template that needs a helper names it in its imports`() {
        // §10.3 gives a generated call a pattern and its imports and nothing else, so a pattern
        // that names anything outside its placeholders is only expressible if the import is there.
        for (spec in specs().filter { it.kotlin.imports.isNotEmpty() }) {
            for (symbol in spec.kotlin.imports) {
                assertTrue(
                    spec.kotlin.pattern.contains(symbol.name),
                    "'${spec.id.value}' imports ${symbol.name} and never calls it",
                )
            }
        }
    }

    @Test
    fun `a member read binds as an atom and everything else as a call`() {
        // The distinction the precedence class can carry for templates like these: `{0}.size` is
        // a member read and everything else is a call, and the emitter parenthesizes on it.
        // Nothing in the seed set is an operator, which §10.5 keeps for the operator tables.
        val atoms = specs().filter { it.kotlin.precedence == FunctionPrecedence.Atom }

        assertEquals(listOf("list.size", "str.length"), atoms.map { it.id.value }.sorted())
    }

    private fun specs(): List<FunctionSpec> =
        ListFunctions.all + StringFunctions.all + NumberFunctions.all + CoreFunctions.all

    private fun declaredImpls(): List<Pair<FunctionId, FunctionImpl>> =
        ListFunctions.impls + StringFunctions.impls + NumberFunctions.impls + CoreFunctions.impls

    private fun seedSchema(): Schema<String, String, String, FunctionSpec, String> =
        Schema.build<String, String, String, FunctionSpec, String> { registerBuiltinFunctions() }

    private companion object {
        val PLACEHOLDER = Regex("""\{(\d+)}""")

        /** §10.2's seed set, verbatim and sorted. */
        val SEED_SET: List<String> = listOf(
            "core.coalesce", "core.isNull",
            "list.contains", "list.get", "list.isEmpty", "list.isNotEmpty", "list.size",
            "num.format", "num.toDouble",
            "str.contains", "str.isBlank", "str.length", "str.lowercase", "str.trim", "str.uppercase",
        )
    }
}
