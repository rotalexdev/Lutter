package dev.rotalex.lutter.schema.kind

import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.EnumEntryValueKind
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The kind table: all nineteen `TypeRef`s resolve, every kind accepts its sample and
 * refuses a foreign value, and `Dimension` fails loudly instead of answering wrongly.
 */
class ValueKindsTest {

    /** One type, its sample, what decoding must produce, and a value it must refuse. */
    private data class Case(
        val type: TypeRef,
        val sample: Value,
        val expected: Any?,
        val wrong: Value,
    )

    private val label: PropertyKey = PropertyKey("label")
    private val thing: TypeId = TypeId("Thing")
    private val thingFields: Map<PropertyKey, Value> = mapOf(label to Value.Str("hi"))

    private val cases: List<Case> = listOf(
        Case(TypeRef.Bool, Value.Bool(true), true, Value.Str("no")),
        Case(TypeRef.Int32, Value.Int32(42), 42, Value.Bool(true)),
        Case(TypeRef.Int64, Value.Int64(42L), 42L, Value.Int32(1)),
        Case(TypeRef.Float32, Value.Float32(1.5f), 1.5f, Value.Float64(1.5)),
        Case(TypeRef.Float64, Value.Float64(1.5), 1.5, Value.Float32(1.5f)),
        Case(TypeRef.Str, Value.Str("hello"), "hello", Value.Url("https://example.com")),
        Case(
            TypeRef.Color,
            Value.Color(ColorArgb.parse("#FF6200EE")),
            ColorArgb.parse("#FF6200EE"),
            Value.Str("#FF6200EE"),
        ),
        Case(TypeRef.Dp, Value.Dp(16f), 16f, Value.Sp(16f)),
        Case(TypeRef.Sp, Value.Sp(14f), 14f, Value.Dp(14f)),
        Case(
            TypeRef.Url,
            Value.Url("https://example.com"),
            "https://example.com",
            Value.Str("https://example.com"),
        ),
        Case(TypeRef.Enum(TypeId("Align")), Value.Enum("Start"), "Start", Value.Str("Start")),
        Case(
            TypeRef.Nullable(TypeRef.Bool),
            Value.Bool(true),
            true,
            Value.Int32(1),
        ),
        Case(TypeRef.Nullable(TypeRef.Bool), Value.Null, null, Value.Int32(1)),
        Case(
            TypeRef.ListOf(TypeRef.Dp),
            Value.ListOf(listOf(Value.Dp(8f))),
            listOf(8f),
            Value.ListOf(listOf(Value.Str("wide"))),
        ),
        Case(
            TypeRef.MapOf(TypeRef.Dp),
            Value.MapOf(Value.Str("px"), mapOf(PropertyKey("sm") to Value.Dp(4f))),
            mapOf(PropertyKey("sm") to 4f),
            Value.MapOf(Value.Str("px"), mapOf(PropertyKey("sm") to Value.Str("wide"))),
        ),
        Case(
            TypeRef.Object(thing),
            Value.Obj(thing, thingFields),
            thingFields,
            Value.Obj(TypeId("Other"), thingFields),
        ),
        Case(
            TypeRef.Ref(RefKind.Page),
            Value.Ref(RefKind.Page, "p_home"),
            Value.Ref(RefKind.Page, "p_home"),
            Value.Ref(RefKind.Component, "c_1"),
        ),
        Case(
            TypeRef.Token(TokenKind.Color),
            Value.Token(TokenKind.Color, "md.color.primary"),
            Value.Token(TokenKind.Color, "md.color.primary"),
            Value.Token(TokenKind.Shape, "md.shape.corner"),
        ),
        Case(
            TypeRef.Icon("material"),
            Value.Icon("material", "Home"),
            Value.Icon("material", "Home"),
            Value.Icon("other", "Home"),
        ),
        Case(
            TypeRef.Icon(null),
            Value.Icon("material", "Home"),
            Value.Icon("material", "Home"),
            Value.Str("Home"),
        ),
    )

    @Test
    fun `every kind accepts its sample and decodes it`() {
        cases.forEach { (type, sample, expected) ->
            val kind = ValueKinds.kindFor(type)
            assertTrue(kind.accepts(sample), "$type refuses its own sample")
            assertEquals(expected, kind.decode(sample))
        }
    }

    @Test
    fun `every kind refuses a foreign value`() {
        cases.forEach { (type, _, _, wrong) ->
            val kind = ValueKinds.kindFor(type)
            assertFalse(kind.accepts(wrong), "$type accepts a foreign value")
            assertFailsWith<IllegalArgumentException> { kind.decode(wrong) }
        }
    }

    @Test
    fun `dimension has no kind`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ValueKinds.kindFor(TypeRef.Dimension)
        }

        assertTrue(failure.message?.contains("dimension") == true)
    }

    @Test
    fun `nested kinds compose`() {
        val nullableList = ValueKinds.kindFor(TypeRef.Nullable(TypeRef.ListOf(TypeRef.Bool)))

        assertTrue(nullableList.accepts(Value.Null))
        assertTrue(nullableList.accepts(Value.ListOf(listOf(Value.Bool(true)))))
        assertFalse(nullableList.accepts(Value.ListOf(listOf(Value.Str("no")))))

        val optionalItems = ValueKinds.kindFor(TypeRef.ListOf(TypeRef.Nullable(TypeRef.Bool)))

        assertTrue(optionalItems.accepts(Value.ListOf(listOf(Value.Bool(true), Value.Null))))
        assertFalse(optionalItems.accepts(Value.ListOf(listOf(Value.Int32(1)))))
    }

    @Test
    fun `enum entries keep the model kind`() {
        assertTrue(ValueKinds.kindFor(TypeRef.Enum(TypeId("Align"))) is EnumEntryValueKind)
    }
}
