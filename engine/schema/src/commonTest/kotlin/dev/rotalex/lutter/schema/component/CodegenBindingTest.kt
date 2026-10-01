package dev.rotalex.lutter.schema.component

import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The binding wire form: three variants, three tags, and the pinning rule that keeps them.
 *
 * `encodeToString` infers the format from the value, so a concrete subtype writes no
 * discriminator. Every encode here pins the base type; the test below it proves the pin
 * is load-bearing by showing the unpinned form losing the tag.
 */
class CodegenBindingTest {

    private val call: CodegenBinding.ComposeCall = CodegenBinding.ComposeCall(
        function = KotlinSymbol("androidx.compose.material3", "Text"),
        params = listOf(
            ParamBinding(
                param = "text",
                from = listOf(PropertyKey("text")),
                positional = Positional.WhenSole,
            ),
            ParamBinding(
                param = "verticalArrangement",
                from = listOf(PropertyKey("spacing"), PropertyKey("verticalArrangement")),
                emit = ValueEmit.Cases(
                    listOf(
                        EmitCase(
                            setOf(PropertyKey("spacing")),
                            "Arrangement.spacedBy({spacing})",
                            listOf(KotlinSymbol("androidx.compose.foundation.layout", "Arrangement")),
                        ),
                    ),
                ),
            ),
        ),
        events = listOf(EventBinding(EventKey("onClick"), "onClick", listOf("it"))),
        slots = listOf(
            SlotBinding(
                SlotName("content"),
                LambdaTarget.NamedParam("content"),
                KotlinSymbol("androidx.compose.foundation.layout", "ColumnScope"),
            ),
        ),
    )

    private fun tagOf(text: String): String =
        Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content

    @Test
    fun `the three variants carry their tags through the base type`() {
        val cases: List<Pair<CodegenBinding, String>> = listOf(
            call to "composeCall",
            CodegenBinding.Custom(EmitterId("custom.text")) to "custom",
            CodegenBinding.Intrinsic to "intrinsic",
        )
        assertEquals(3, cases.size, "a binding variant without a tag case")

        for ((binding, tag) in cases) {
            val text = Json.encodeToString<CodegenBinding>(binding)

            assertEquals(tag, tagOf(text), "wrong discriminator in '$text'")
            assertEquals(binding, Json.decodeFromString<CodegenBinding>(text))
        }
    }

    @Test
    fun `a full compose call round trips`() {
        val text = Json.encodeToString<CodegenBinding>(call)

        assertEquals(call, Json.decodeFromString<CodegenBinding>(text))
        assertTrue(tagOf(text) == "composeCall")
    }

    @Test
    fun `the pin is load-bearing an unpinned encode loses the tag`() {
        // Intrinsic, not the call above: any nested sealed value (an emit, a slot target)
        // writes its own tag, so only a payload-free variant isolates the pin itself.
        val pinned = Json.encodeToString<CodegenBinding>(CodegenBinding.Intrinsic)
        val unpinned = Json.encodeToString(CodegenBinding.Intrinsic)

        assertTrue("\"type\":\"intrinsic\"" in pinned)
        assertFalse("\"type\"" in unpinned, "concrete encode kept a tag: '$unpinned'")
    }

    @Test
    fun `an unknown tag fails instead of decoding`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<CodegenBinding>("""{"type":"nope"}""")
        }
    }

    @Test
    fun `blank emitter and scope ids fail at construction`() {
        assertFailsWith<IllegalArgumentException> { EmitterId("") }
        assertFailsWith<IllegalArgumentException> { ScopeId("  ") }
        assertEquals("custom.text", EmitterId("custom.text").toString())
    }

    @Test
    fun `every value emit and lambda target spells its tag`() {
        val emits: List<Pair<ValueEmit, String>> = listOf(
            ValueEmit.Direct to "direct",
            ValueEmit.Cases(emptyList()) to "cases",
        )
        val targets: List<Pair<LambdaTarget, String>> = listOf(
            LambdaTarget.Trailing to "trailing",
            LambdaTarget.NamedParam("content") to "namedParam",
        )
        assertEquals(2, emits.size, "a ValueEmit variant without a tag case")
        assertEquals(2, targets.size, "a LambdaTarget variant without a tag case")

        for ((emit, tag) in emits) {
            assertEquals(tag, tagOf(Json.encodeToString<ValueEmit>(emit)))
        }
        for ((target, tag) in targets) {
            assertEquals(tag, tagOf(Json.encodeToString<LambdaTarget>(target)))
        }
    }
}
