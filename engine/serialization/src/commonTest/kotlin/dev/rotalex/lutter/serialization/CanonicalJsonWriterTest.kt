package dev.rotalex.lutter.serialization

import dev.rotalex.lutter.model.value.Value
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The canonical writer contract: sorted keys, model-order arrays, verbatim scalars.
 *
 * Inputs are parsed by [ForgeJson], so no hand-written JSON reaches the writer unchecked.
 * The writer owns layout; [ForgeJson] owns configuration, and no test builds a `Json {}`.
 */
class CanonicalJsonWriterTest {

    @Test
    fun `object keys are emitted in sorted order at every depth`() {
        val input = ForgeJson.parseToJsonElement(
            """{"zebra":1,"apple":{"y":true,"b":false},"mango":[3,2]}""",
        )

        assertEquals(
            "{\n  \"apple\": {\n    \"b\": false,\n    \"y\": true\n  },\n" +
                "  \"mango\": [\n    3,\n    2\n  ],\n  \"zebra\": 1\n}\n",
            CanonicalJsonWriter.write(input),
        )
        // `[3,2]` stays `[3,2]`: arrays keep model order, only object keys sort.
    }

    @Test
    fun `empty structures stay inline and every text ends with a newline`() {
        assertEquals("{}\n", CanonicalJsonWriter.write(ForgeJson.parseToJsonElement("{}")))
        assertEquals("[]\n", CanonicalJsonWriter.write(ForgeJson.parseToJsonElement("[]")))
        assertEquals("null\n", CanonicalJsonWriter.write(JsonNull))
    }

    @Test
    fun `whole numbers write without a decimal point`() {
        // The spelling comes from the model's CanonicalNumbers through the Value serializer;
        // the writer prints the scalar verbatim and formats nothing itself.
        assertEquals(
            "{\n  \"type\": \"dp\",\n  \"v\": 16\n}\n",
            CanonicalJsonWriter.encode(Value.serializer(), Value.Dp(16f)),
        )
        assertEquals(
            "{\n  \"type\": \"dp\",\n  \"v\": 16.5\n}\n",
            CanonicalJsonWriter.encode(Value.serializer(), Value.Dp(16.5f)),
        )

        // Non-finite has no canonical spelling, so encoding refuses instead of printing.
        assertFailsWith<IllegalArgumentException> {
            CanonicalJsonWriter.encode(Value.serializer(), Value.Dp(Float.NaN))
        }
    }
}
