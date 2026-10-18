package dev.rotalex.lutter.serialization

import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.HostFunctionDecl
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.type.TypeRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The null-omission pair under the configuration the engine encodes with: a null key leaves the
 * wire, and an absent nullable comes back as that null.
 *
 * [ForgeJson] sets `explicitNulls = false`, so a null-valued nullable property is written as
 * nothing at all, and every decode in this module is then asked to read a key that is not
 * there. kotlinx tolerates an absent nullable property under that flag and refuses it under the
 * default one, and it is that agreement between two settings rather than anything in
 * `HostFunctionDecl` or `TypeRef` which makes the omission safe. The records' own tests in
 * `:engine:model` cannot show it: they encode with kotlinx's default `Json`, which writes the
 * null, so they prove a round trip this module never performs.
 *
 * ### Why the absences are asserted rather than inferred
 *
 * `decode(encode(d)) == d` cannot tell a dropped key from a preserved one. An encoder that
 * omitted `returns` and a decoder that supplied a value would satisfy it together while the
 * wire said nothing about the field, so each row reads the key list before it decodes. The two
 * tolerance rows are handed literal text rather than this engine's own output, so nothing they
 * assert can be satisfied by an encoder and a decoder that were wrong in the same direction.
 *
 * The dependency this pair creates across the format is written out at [ForgeJson].
 */
class ExplicitNullsTest {

    /** A function that answers nothing, which is the case `returns = null` exists to state. */
    private val unitHost = HostFunctionDecl(
        name = "submitOrder",
        params = emptyList(),
        returns = null,
    )

    /** The smallest document the codec accepts, carrying [unitHost] and nothing else of it. */
    private val document = UiDocument(
        meta = DocumentMeta("Demo"),
        app = AppSpec("com.example.demo", PageId("p_home")),
        pages = mapOf(
            PageId("p_home") to Page(PageId("p_home"), "HomeScreen", "/home", root = NodeId("n_root")),
        ),
        components = emptyMap(),
        nodes = NodeTable.EMPTY.with(Node(id = NodeId("n_root"), type = ComponentType("core.Column"))),
        hostFunctions = listOf(unitHost),
    )

    /**
     * The omission, on the record: `returns` is not one of the keys.
     *
     * The other two keys are here for the contrast that makes this row mean something.
     * `params` has no default, so it is written whatever the configuration; `suspend` carries
     * one and `encodeDefaults` is false, so it is dropped. `returns` is a third case — nullable,
     * no default, and absent all the same — and `explicitNulls` is what removes it, not
     * `encodeDefaults`, which has no say over a property that has no default to skip.
     */
    @Test
    fun `a unit host function writes no returns key`() {
        val text = CanonicalJsonWriter.encode(HostFunctionDecl.serializer(), unitHost)

        assertEquals(listOf("name", "params"), ForgeJson.parseToJsonElement(text).jsonObject.keys.toList())
    }

    /**
     * The tolerance, on the same record and on input written by hand.
     *
     * The literal omits `returns` rather than encoding a null, which is what the row above
     * writes, and it is handed over instead of the row's own bytes so that the two cannot pass
     * each other. `suspend` is absent too and reaches the constructor by the other route: it
     * has a default, so the decoder supplies `false`. The null in the result is read back, not
     * assumed from the fact that the decode did not throw.
     */
    @Test
    fun `an absent returns key decodes as a null return`() {
        val text = """{"name":"submitOrder","params":[]}"""

        val decoded = ForgeJson.decodeFromString(HostFunctionDecl.serializer(), text)

        assertEquals(unitHost, decoded)
        assertNull(decoded.returns, "the key was absent, so the null came from the decoder")
    }

    /**
     * The omission again, through the polymorphic form a document actually stores.
     *
     * `TypeRef.Icon` is the other defaultless nullable in the module that can reach
     * [ForgeJson], and the discriminator is what has to survive the omission: the tag is what
     * tells the decoder which variant an object is, so a `{"type": "icon"}` carrying nothing
     * else is the smallest form this variant can take on the wire.
     */
    @Test
    fun `an icon with no set writes no set key`() {
        val text = CanonicalJsonWriter.encode(TypeRef.serializer(), TypeRef.Icon(set = null))

        val encoded = ForgeJson.parseToJsonElement(text).jsonObject

        assertEquals(listOf("type"), encoded.keys.toList(), "the set key survived as a null")
        // The key is `type` and not `ForgeJson`'s own `k` because `TypeRef` pins its
        // discriminator; the omission does not disturb it.
        assertEquals("icon", encoded.getValue("type").jsonPrimitive.content)
    }

    /**
     * The tolerance again, on the polymorphic form, with the object written out by hand.
     *
     * `TypeRefTest` already decodes a text that carries `"set": null` under kotlinx's default
     * `Json`, so what is unproved is the only shape this module ever writes: the same variant
     * with the key gone entirely.
     */
    @Test
    fun `an absent set key decodes as a null icon set`() {
        val decoded = ForgeJson.decodeFromString(TypeRef.serializer(), """{"type": "icon"}""")

        assertEquals(TypeRef.Icon(set = null), decoded)
        assertNull((decoded as TypeRef.Icon).set, "the key was absent, so the null came from the decoder")
    }

    /**
     * The pair end to end: the canonical bytes a document is stored as, and a document read
     * back out of them.
     *
     * The record rows above are two halves; this is the only row that puts the two together on
     * the path a document takes, envelope and versions included. The null is asserted on the
     * decoded value rather than left to the document equality, because an equality that passed
     * with a decoded `returns` still null would also pass with one that had been dropped on the
     * way in and never restored — the assertion that names the field is the one that rules
     * that out.
     */
    @Test
    fun `a unit host function survives the codec with no returns key`() {
        val bytes = JsonDocumentCodec.encode(document)

        val encoded = (ForgeJson.parseToJsonElement(bytes.decodeToString()) as JsonObject)
            .getValue("payload").jsonObject
            .getValue("hostFunctions").jsonArray.single().jsonObject
        assertEquals(listOf("name", "params"), encoded.keys.toList())

        val decoded = JsonDocumentCodec.decode(bytes).document

        assertEquals(document, decoded)
        assertNull(decoded.hostFunctions.single().returns, "the key was absent, so the decoder supplied the null")
    }
}
