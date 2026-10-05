package dev.rotalex.lutter.generated.compile

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.CodegenResult
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.runtime.UiScreen
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PLAN §32's corpus: the seed set's fifteen names and §10.4's thirteen operators through the
 * interpreter and through the generated code, in one document, asserted as text and as Kotlin.
 *
 * ### Why the interesting rows are the ones here
 *
 * `1 + 1` cannot disagree with itself, so a corpus of arithmetic proves nothing about the seam.
 * Every row below is a place where the two backends *could* land on different text or a
 * different value, and the expected string is what catches it:
 *
 *  * **`num.format`** — §10.6's rule is integer math on both sides. The template names
 *    `fixedDecimalText` rather than Kotlin's `format`, which is locale-sensitive, and
 *    `decimals` is a width: `1.250`, not the `1.25` a trimming formatter would answer.
 *  * **`core.coalesce` over a `list.get` miss** — the interpreter answers `Null`, and Kotlin's
 *    `List.get` throws, so the template has to be `getOrNull`. A `[0]`-shaped template does not
 *    render differently; it throws at composition.
 *  * **`core.isNull` under `!`** — D15's `Comparison` level. Without it the emitted text is
 *    `!x == null`, which Kotlin reads as `(!x) == null`: always false, and a warning rather
 *    than an error, so the corpus is what notices.
 *  * **`core.isNull` against `core.isNull`** — the same level, one operator tighter. The
 *    template is an equality, so beside another `==` its right-hand side has to be
 *    parenthesised: `a == null == b == null` is four operands and two of them are `null`.
 *  * **`num.toDouble` inside `num.format`** — §10.4 allows no implicit conversion, so this is
 *    the only route from an `i32` to text. `2.0`, not the `2` a half that stayed an integer gives.
 *  * **a derived read** — the interpreter re-evaluates the body on every read and the generated
 *    half is a `val` with a getter, so `n_shout` is the one row that is not a function call at all.
 *
 * The operator rows carry the same burden and the same discipline. Each one names the mistake a
 * backend can plausibly make that the other three rows already made impossible:
 *
 *  * **`i64 + i64`** — §10.4 admits `i64` as a template part directly, so an `Int64` reaches text
 *    without `num.format` and only the width decides what that text is. The seed is
 *    `9007199254740993`, one past `2^53`, because a `Double`-widening half prints
 *    `9.007199254740992E15` and a narrowing half prints `9007199254740994` as an `Int`.
 *  * **`-i64`** — the same width through the one arithmetic row that is a prefix rather than a
 *    binary, so it also pins that `-state.wide` needs no parentheses: a `0 - state.wide` spelling
 *    renders identically and a `Double` one does not.
 *  * **`10 - 3 - 2`** — every operand is the same `i32`, so no type rule can break the tie and
 *    associativity is the only thing left to settle it. The generated half answers `5` or `9`
 *    depending on which side it parenthesised, and the interpreter answers whichever order its
 *    own rows recursed in.
 *  * **`s_count + s_count * 3`** — `*` binds tighter than `+` in Kotlin and both halves have to
 *    agree about it: `2 + 6` against `(2 + 2) * 3`. The generated text carries the parentheses
 *    either way, so the golden and the rendered text fail separately.
 *  * **`&&` and `||` over two calls** — the shape where the printer's right-operand minimum is
 *    decided, and `&&`'s answer depends on whether the right side was read. Both rows are `bool`
 *    over `bool`, which §10.4's own rule admits, so they are the cheapest rows that still cross
 *    the template's type boundary back to text.
 *  * **`str.uppercase(x) == str.uppercase(x)`** — two separately evaluated `String`s that hold
 *    the same text. The interpreter compares `Value`s by value; a generated half that reached for
 *    `===` compares two fresh `String` objects by reference and answers `false` here, which is the
 *    only row in this corpus where the wrong operator type-checks.
 *  * **`s_count < str.length(s_label)`** — `<` answers a `bool` and `bool` is one of §10.4's four
 *    template parts, so the comparison's answer re-enters the document as text. The two operands
 *    settle independently, one from a state read and one from a call, so a half that compared
 *    against the declared property type rather than the settled one would refuse or widen.
 *
 * The four binding rows answer one question: which level each operator sits at. `?:` binds tighter
 * than `==` and `<` in Kotlin, so a `core.coalesce` beside either needs no parentheses, and the
 * three rows that place it on each side are what keeps the ladder saying so:
 *
 *  * **`core.isNull` on the right of `&&`** — `&&` binds looser than `==`, so the comparison is the
 *    conjunction's own right operand and `a && b == null` already means `a && (b == null)`. A
 *    ladder with `&&` above `==` would parenthesise it, and the parentheses would be the only sign.
 *  * **`core.coalesce` on each side of `==`, and on the left of `<`** — the three shapes where the
 *    elvis level is load-bearing. Parenthesised and bare are the same tree, so a wrong level here
 *    emits text that compiles and disagrees with the IR it came from rather than failing to build.
 *
 * `str.uppercase`'s hazard is the locale and this corpus cannot reach it: the seed is ASCII, so
 * a root-locale fold and a platform fold agree here. That is stated rather than claimed.
 *
 * ### What is deliberately absent
 *
 * A `dp` or `sp` in a template part is §10.4's refusal, and it cannot be a row: a fixture that
 * carries a finding fails `FixtureValidationTest`, and `ConformanceTest.resolve` refuses an
 * errored document before it renders anything. The rule is asserted where it lives, at
 * `ExpressionPassTest` in `:engine:analysis`, and repeating it here would test analysis rather
 * than agreement.
 *
 * **Mixed integer widths.** `i32 + i64` is not a row because §10.4 refuses it: an arithmetic row
 * requires the two sides to already be the same type, and `Assignability` never widens a number.
 * So the checker does not widen, and the widening question is answered rather than tested.
 *
 * **`?:` inside a `&&`.** Not a row because §10.4 refuses it: `&&` takes two `bool`s and
 * `core.coalesce` answers the fallback's type, so no document can put one beside the other. The
 * elvis level is pinned beside `==` and `<` instead, where it is reachable from both sides.
 *
 * **`&&` short circuit over something that would throw.** Not a row because nothing in this
 * language can be: every refusal in both backends is a type refusal, and §10.4 checks both
 * operands of a `&&` whatever the left one answers, so a document the checker accepts has no
 * right side the evaluator could refuse. The two logical rows therefore prove the answer and the
 * parenthesisation, not the skip — which is what `OperatorContractTest`'s last test pins, from the
 * side that can see the thunk.
 */
@OptIn(ExperimentalTestApi::class)
class ExpressionCorpusTest {

    private companion object {
        const val PACKAGE: String = "dev.rotalex.lutter.generated."
        const val FIXTURE: String = "expression_corpus"
        val HOME: PageId = PageId("p_home")

        /**
         * What every one of the twenty-seven nodes renders, in slot order.
         *
         * The list is the corpus: conformance proves the two backends agree on it, and this
         * literal proves they agree on *this* rather than on anything at all.
         */
        val TEXT: List<String> = listOf(
            "taps",
            "  TAPS  ",
            "  taps  ",
            "Blank: true",
            "Has: true",
            "Count: 2 of 8",
            "1.250",
            "2.0",
            "size=1 of true",
            "empty=false",
            "full=true",
            "missing",
            "Present: false",
            "Agree: false",
            "  TAPS  ",
            "Wide: 9007199254740994",
            "Neg: -9007199254740993",
            "Left: 5",
            "Prec: 8",
            "And: true",
            "Or: true",
            "Same: true",
            "Less: true",
            "AndNull: true",
            "CoalesceEq: false",
            "EqCoalesce: false",
            "CoalesceLt: true",
        )
    }

    @Test
    fun `the whole screen is what the seed set's expressions produce`() {
        assertEquals(
            """
            // Generated by Forge. Do not edit.
            package dev.rotalex.lutter.generated.expression_corpus.screens

            ${"import"} androidx.compose.foundation.layout.Column
            ${"import"} androidx.compose.material3.Text
            ${"import"} androidx.compose.runtime.Composable
            ${"import"} androidx.compose.runtime.Stable
            ${"import"} androidx.compose.runtime.getValue
            ${"import"} androidx.compose.runtime.mutableStateOf
            ${"import"} androidx.compose.runtime.remember
            ${"import"} androidx.compose.runtime.setValue
            ${"import"} androidx.compose.ui.Modifier
            ${"import"} dev.rotalex.lutter.model.value.fixedDecimalText

            @Stable
            public class HomeScreenState {
                public var count: Int by mutableStateOf(2)

                public var label: String by mutableStateOf("  taps  ")

                public var blank: String by mutableStateOf("   ")

                public var ratio: Double by mutableStateOf(1.25)

                public val shout: String
                    get() = label.uppercase()

                public var flag: Boolean by mutableStateOf(true)

                public var wide: Long by mutableStateOf(9007199254740993L)
            }

            @Composable
            public fun rememberHomeScreenState(): HomeScreenState {
                val state: HomeScreenState = remember {
                    HomeScreenState()
                }
                return(state)
            }

            @Composable
            public fun HomeScreen(modifier: Modifier = Modifier, state: HomeScreenState = rememberHomeScreenState()) {
                Column(
                    modifier = modifier,
                ) {
                    Text(state.label.trim())
                    Text(state.label.uppercase())
                    Text(state.label.uppercase().lowercase())
                    Text("Blank: ${'$'}{state.blank.isBlank()}")
                    Text("Has: ${'$'}{state.label.contains("ta")}")
                    Text("Count: ${'$'}{state.count} of ${'$'}{state.label.length}")
                    Text(fixedDecimalText(state.ratio, 3))
                    Text(fixedDecimalText(state.count.toDouble(), 1))
                    Text("size=${'$'}{listOf("a").size} of ${'$'}{listOf("a").contains("a")}")
                    Text("empty=${'$'}{listOf("a").isEmpty()}")
                    Text("full=${'$'}{listOf("a").isNotEmpty()}")
                    Text(listOf("a").getOrNull(7) ?: "missing")
                    Text("Present: ${'$'}{!(listOf("a").getOrNull(7) == null)}")
                    Text(
                        "Agree: ${'$'}{listOf("a").getOrNull(7) == null == (listOf("a").getOrNull(0) == null)}",
                    )
                    Text(state.shout)
                    Text("Wide: ${'$'}{state.wide + 1L}")
                    Text("Neg: ${'$'}{-state.wide}")
                    Text("Left: ${'$'}{10 - 3 - 2}")
                    Text("Prec: ${'$'}{state.count + state.count * 3}")
                    Text(
                        "And: ${'$'}{state.label.contains("ta") && state.blank.isBlank()}",
                    )
                    Text("Or: ${'$'}{listOf("a").isEmpty() || state.blank.isBlank()}")
                    Text(
                        "Same: ${'$'}{state.label.uppercase() == state.label.uppercase()}",
                    )
                    Text("Less: ${'$'}{state.count < state.label.length}")
                    Text(
                        "AndNull: ${'$'}{state.blank.isBlank() && listOf("a").getOrNull(7) == null}",
                    )
                    Text("CoalesceEq: ${'$'}{listOf("a").getOrNull(7) ?: "d" == "a"}")
                    Text("EqCoalesce: ${'$'}{"a" == listOf("a").getOrNull(7) ?: "d"}")
                    Text("CoalesceLt: ${'$'}{listOf(5).getOrNull(7) ?: 0 < 3}")
                }
            }

            """.trimIndent(),
            generate(FIXTURE).content("screens/HomeScreen.kt"),
        )
    }

    @Test
    fun `the corpus is one screen over page state, with no app state to provide`() {
        val files = generate(FIXTURE)

        assertEquals(listOf("App.kt", "screens/HomeScreen.kt"), files.files.files.map { it.path })
        assertTrue(!files.content("App.kt").contains("CompositionLocalProvider"), "no app state")
    }

    @Test
    fun `the corpus calls every name the seed set declares and nothing else`() {
        val called = calledFunctions(document(FIXTURE))
        val seed = ConformanceHarness.schema().functions.all().map { it.id.value }

        assertEquals(seed.sorted(), called.sorted(), "§10.2's seed set, covered")
    }

    @Test
    fun `the interpreter and the generated screen render the corpus the same text`() = runComposeUiTest {
        val resolved = resolve(FIXTURE)
        setContent {
            UiScreen(ConformanceHarness.runtime(ConformanceHarness.schema()), resolved, HOME, ConformanceHarness.environment())
        }
        assertEquals(TEXT, renderedTexts(), "runtime")

        setContent { GeneratedFixtures.all.getValue(FIXTURE)(Modifier) }
        assertEquals(TEXT, renderedTexts(), "generated")
    }

    /** Every text in the composed tree, in tree order, which is the slot order. */
    private fun ComposeUiTest.renderedTexts(): List<String> {
        val found = mutableListOf<String>()
        collect(onRoot().fetchSemanticsNode(), found)
        return found
    }

    private fun collect(node: SemanticsNode, into: MutableList<String>) {
        valueOrNull(node.config, SemanticsProperties.Text)?.forEach { into += it.text }
        for (child in node.children) collect(child, into)
    }

    // `SemanticsConfiguration` has no optional read in this Compose version, so the read is a
    // `get` that may throw; `runCatching` is the whole of the optionality.
    private fun <T> valueOrNull(config: SemanticsConfiguration, key: SemanticsPropertyKey<T>): T? =
        runCatching { config.get(key) }.getOrNull()

    /** The fixture decoded, whether or not it needs analysing. */
    private fun document(id: String): UiDocument =
        JsonDocumentCodec.decode(FixtureDocuments.json(id).encodeToByteArray()).document

    private fun resolve(id: String) = assertNotNull(
        ConformanceHarness.analyze(ConformanceHarness.schema(), id).resolved,
        "fixture '$id' resolved nothing",
    )

    /** Every `FunctionId` the document's computed properties call, deduplicated. */
    private fun calledFunctions(document: UiDocument): List<String> {
        val found = linkedSetOf<FunctionId>()
        for (id in document.nodes.ids()) {
            val node = document.nodes[id] ?: continue
            for (property in node.props.values) {
                val computed = property as? PropertyValue.Computed ?: continue
                walk(computed.expr, found)
            }
        }
        return found.map { it.value }
    }

    /** Exhaustive over §10.1's nine, so a tenth refuses to compile rather than going unseen. */
    private fun walk(expr: Expr, into: MutableSet<FunctionId>) {
        when (expr) {
            is Expr.Const -> Unit
            is Expr.Ref -> Unit
            is Expr.Member -> walk(expr.receiver, into)
            is Expr.Call -> {
                into += expr.function
                expr.args.forEach { walk(it, into) }
            }
            is Expr.Unary -> walk(expr.operand, into)
            is Expr.Binary -> {
                walk(expr.left, into)
                walk(expr.right, into)
            }
            is Expr.If -> {
                walk(expr.cond, into)
                walk(expr.then, into)
                walk(expr.otherwise, into)
            }
            is Expr.ListLiteral -> expr.items.forEach { walk(it, into) }
            is Expr.Template -> expr.parts.forEach { walk(it, into) }
        }
    }

    /** One fixture decoded, analyzed and generated; the task's own output asserted, not trusted. */
    private fun generate(id: String): CodegenResult {
        val schema = ConformanceHarness.schema()
        val analysis = ConformanceHarness.analyze(schema, id)
        assertTrue(analysis.diagnostics.isEmpty(), "fixture '$id': ${analysis.diagnostics}")
        val resolved = assertNotNull(analysis.resolved, "fixture '$id' resolved nothing")
        val files = KotlinGenerator(schema, CodegenOptions(PACKAGE + id))
            .generate(resolved, analysis.diagnostics)
        assertTrue(
            files.diagnostics.none { it.severity == Severity.Error },
            "fixture '$id': ${files.diagnostics}",
        )
        return files
    }

    private fun CodegenResult.content(path: String): String =
        files.files.firstOrNull { it.path == path }?.content
            ?: error("no '$path' in ${files.files.map { it.path }}")
}