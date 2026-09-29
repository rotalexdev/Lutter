package dev.rotalex.lutter.model.type

import dev.rotalex.lutter.model.ids.TypeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `TypeRef` contract: nineteen variants, each of which has to keep its place in a stored
 * document while the code around it moves.
 *
 * Three things are defended here:
 *
 *  * **The tag is the wire form.** A `@SerialName` that drifts from the tag, or a tag that
 *    starts following the class name, breaks every document already written and breaks
 *    silently. The test reads the discriminator back out of the encoded JSON rather than
 *    trusting the annotation, so it fails when the two disagree — which is the only way to
 *    catch a rename.
 *  * **The five dimensions of §9.2 answer the two the model owns.** `serialTag` and
 *    `runtimeType` are a table, not a comment, and this is the table test.
 *  * **No two variants share a tag.** A duplicate in a sealed union is silent data
 *    corruption: the decoder picks one of the two and nobody finds out which.
 */
class TypeRefTest {

    // -----------------------------------------------------------------------------------
    // The variants
    // -----------------------------------------------------------------------------------

    /** A type, the tag it must write, and the runtime answer §9.2 gives it. */
    private data class Case(
        val type: TypeRef,
        val tag: String,
        val runtimeType: RuntimeType,
    )

    /**
     * All nineteen, once.
     *
     * One list rather than three, because three lists would drift and a drifting list is a
     * test that passes. The tag and the runtime answer are stated here explicitly rather than
     * read back off the type, so a variant that declares the wrong one fails instead of
     * agreeing with itself.
     */
    private val cases: List<Case> = listOf(
        Case(TypeRef.Bool, "bool", RuntimeType.Boolean),
        Case(TypeRef.Int32, "i32", RuntimeType.Int),
        Case(TypeRef.Int64, "i64", RuntimeType.Long),
        Case(TypeRef.Float32, "f32", RuntimeType.Float),
        Case(TypeRef.Float64, "f64", RuntimeType.Double),
        Case(TypeRef.Str, "str", RuntimeType.Text),
        Case(TypeRef.Color, "color", RuntimeType.Color),
        Case(TypeRef.Dp, "dp", RuntimeType.Dp),
        Case(TypeRef.Sp, "sp", RuntimeType.TextUnit),
        Case(TypeRef.Url, "url", RuntimeType.Text),
        Case(TypeRef.Icon(set = "material"), "icon", RuntimeType.Icon),
        Case(TypeRef.Dimension, "dimension", RuntimeType.Dimension),
        Case(TypeRef.Nullable(TypeRef.Color), "nullable", RuntimeType.Optional),
        Case(TypeRef.ListOf(TypeRef.Dp), "list", RuntimeType.List),
        Case(TypeRef.MapOf(TypeRef.Str), "map", RuntimeType.Map),
        Case(TypeRef.Enum(TypeId("Alignment")), "enum", RuntimeType.EnumValue),
        Case(TypeRef.Object(TypeId("Thing")), "object", RuntimeType.ObjectValue),
        Case(TypeRef.Ref(RefKind.Page), "ref", RuntimeType.Reference),
        Case(TypeRef.Token(TokenKind.Typography), "token", RuntimeType.ThemeToken),
    )

    @Test
    fun `the union has nineteen variants and no two share a tag`() {
        // The count is PLAN §9.1's list, transcribed. It is asserted because a count is a
        // claim about a closed set: a variant added to `TypeRef` and forgotten here would
        // still compile, would still pass every round trip below, and would go untested —
        // which is the same silent loss a duplicate tag causes.
        assertEquals(19, cases.size, "the list of variants is not §9.1's")

        // A duplicate tag is the worst failure this hierarchy can have: both variants compile,
        // every round trip passes for whichever one the decoder happened to pick, and a
        // document written with the other one is corrupted on read.
        val tags = cases.map { it.tag }
        assertEquals(tags.size, tags.toSet().size, "two variants share a tag: $tags")
    }

    @Test
    fun `every variant round trips through json and comes back equal`() {
        for (case in cases) {
            val text = Json.encodeToString(case.type)
            assertEquals(
                case.type,
                Json.decodeFromString<TypeRef>(text),
                "lost '${case.tag}' via '$text'",
            )
        }
    }

    @Test
    fun `the serial name is the wire form and not the class name`() {
        for (case in cases) {
            val text = Json.encodeToString(case.type)

            // What a document actually stores.
            assertEquals(case.tag, discriminatorOf(text), "wrong discriminator in '$text'")

            // What the model claims, which must be the same string and not merely similar.
            assertEquals(case.tag, case.type.serialTag, "'${case.tag}' disagrees with its own tag")

            // And the rename guard. Every one of the nineteen differs from its class name
            // today, which is exactly why the assertion can be made without exceptions: a
            // variant whose tag *became* its class name — `Dp` renamed to `DpValue`, say —
            // would be a type that no longer reads the documents written before the rename,
            // and this is the line that catches it.
            assertNotEquals(
                case.type::class.simpleName,
                case.tag,
                "'${case.type::class.simpleName}' is writing itself as the tag",
            )
        }
    }

    @Test
    fun `the two dimensions the model owns answer for every variant`() {
        // PLAN §9.2's Serialization and Runtime evaluation columns, as data. The other three
        // columns are not asserted here because they are not the model's to answer: editor
        // metadata is `EditorHints` in `:engine:schema`, validation is a property's rule, and
        // codegen is a `KotlinSymbol` in `:engine:codegen`.
        for (case in cases) {
            assertEquals(
                case.runtimeType,
                case.type.runtimeType,
                "wrong runtime type for '${case.tag}'",
            )
        }

        // The runtime answer is many-to-one, and the two collapses are the interesting ones.
        // `Url` and `Str` are both text because §9.2 gives `String` for both — what makes a
        // URL a URL is a validation rule, not a different runtime type.
        assertEquals(RuntimeType.Text, TypeRef.Str.runtimeType)
        assertEquals(RuntimeType.Text, TypeRef.Url.runtimeType)

        // `Nullable` is not "the inner type" and pretending otherwise is the bug this asserts:
        // reading a nullable's runtime answer has to say something, and it says "optional".
        assertEquals(RuntimeType.Optional, TypeRef.Nullable(TypeRef.Bool).runtimeType)
    }

    @Test
    fun `a nested type round trips`() {
        // The three composite variants nest, which is the only place a `TypeRef` can be
        // arbitrarily deep and the only place the serializer has to recurse into a field whose
        // type is the hierarchy it belongs to. `MapOf(Nullable(ListOf(Enum)))` is three levels
        // and three different composite variants, so a serializer that only handled one of them
        // would still pass a shallower case.
        val nested = TypeRef.MapOf(
            TypeRef.Nullable(TypeRef.ListOf(TypeRef.Enum(TypeId("Alignment")))),
        )
        val text = Json.encodeToString(nested)

        assertEquals(nested, Json.decodeFromString<TypeRef>(text))
        assertEquals("map", discriminatorOf(text), "wrong discriminator in '$text'")
    }

    @Test
    fun `the post MVP dimension type is declared and stores its tag`() {
        // §9.1 marks it post-MVP and it is declared anyway, so that a document written against
        // a later engine decodes on this one. A test is what stops "declared" from quietly
        // becoming "declared without a tag", which is the failure mode a post-MVP declaration
        // actually has: nothing reads it, so nothing notices it was never wired up.
        val text = Json.encodeToString(TypeRef.Dimension)

        assertEquals("dimension", discriminatorOf(text))
        assertEquals(TypeRef.Dimension, Json.decodeFromString<TypeRef>(text))
    }

    @Test
    fun `an icon type with no set still stores its tag`() {
        // `TypeRef.Icon.set` is nullable because a property may leave the set to the
        // environment, so this is the variant that can be written with one field missing. The
        // discriminator is what has to survive that.
        val text = Json.encodeToString(TypeRef.Icon(set = null))

        assertEquals("icon", discriminatorOf(text))
        assertEquals(TypeRef.Icon(null), Json.decodeFromString<TypeRef>(text))
    }

    // -----------------------------------------------------------------------------------
    // The two kind enums
    // -----------------------------------------------------------------------------------

    @Test
    fun `a ref kind is written as its name`() {
        // Four entries, four spellings, and `dataModel` is the one that would be guessed
        // wrong: the obvious normalization is `datamodel`, and it would break every document
        // that says `dataModel`.
        val expected = mapOf(
            RefKind.Page to "page",
            RefKind.Component to "component",
            RefKind.Resource to "resource",
            RefKind.DataModel to "dataModel",
        )

        for ((kind, name) in expected) {
            assertEquals("\"$name\"", Json.encodeToString(kind), "wrong name for $kind")
            assertEquals(kind, Json.decodeFromString<RefKind>("\"$name\""))
            assertNotEquals(kind.name, name, "'$name' is the entry name, not the wire form")
        }
    }

    @Test
    fun `a token kind is written as its name`() {
        // PLAN §14.1's example document writes `"kind": "typography"` for `TokenKind
        // .Typography`, and that is the precedent all four follow. `Custom` is absent on
        // purpose: §14.2 makes it a *source* (`ResolvedToken.source = Material | Custom`),
        // not a kind.
        val expected = mapOf(
            TokenKind.Color to "color",
            TokenKind.Typography to "typography",
            TokenKind.Shape to "shape",
            TokenKind.Dimension to "dimension",
        )

        for ((kind, name) in expected) {
            assertEquals("\"$name\"", Json.encodeToString(kind), "wrong name for $kind")
            assertEquals(kind, Json.decodeFromString<TokenKind>("\"$name\""))
            assertNotEquals(kind.name, name, "'$name' is the entry name, not the wire form")
        }
    }

    @Test
    fun `a kind enum is written inside the type that carries it`() {
        // The enums on their own round trip, but a document holds them *inside* a `TypeRef`,
        // and a spelling that is right on its own and wrong in place is still wrong. Each is
        // checked in the shape a document actually writes it — as a nested field of a sealed
        // hierarchy, not as the root of its own.
        val ref = Json.encodeToString(TypeRef.Ref(RefKind.DataModel))
        assertTrue("\"dataModel\"" in ref, "expected 'dataModel' in '$ref'")

        val token = Json.encodeToString(TypeRef.Token(TokenKind.Typography))
        assertTrue("\"typography\"" in token, "expected 'typography' in '$token'")
    }

    /**
     * The discriminator, read out of encoded JSON rather than off the annotation.
     *
     * Parsing the text back is the point. Reading the tag from the serializer descriptor would
     * be reading the same declaration the test is supposed to be checking.
     */
    private fun discriminatorOf(text: String): String =
        Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content
}
