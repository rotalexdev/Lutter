package dev.rotalex.lutter.serialization

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.ParamDecl
import dev.rotalex.lutter.model.doc.SlotDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.buildDocument
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * PLAN §36.1's first bullet, as a test: a document written by hand as wire text and a document
 * written by the builder DSL are the same document.
 *
 * ### Why three rows and not one
 *
 * The tempting test here is a round trip, and it is the one that proves least. A codec that
 * dropped a field on the way out and dropped the same field on the way back round-trips
 * perfectly, so a round trip cannot fail on half the defect classes a codec has — and the DSL
 * row that already exists in `:engine:model` is exactly that, over kotlinx's default JSON rather
 * than over the canonical envelope. So the equality is asserted here against an envelope this
 * file writes out in full and a document it builds with `buildDocument`, and the two were
 * authored against different declarations: the wire side against the records' own field names and
 * tags, the builder side against the scopes' own functions. A change to either side's spelling
 * breaks this, and neither can drift alone.
 *
 * The rows then keep three claims apart. Agreement is what the first one asks. Preservation
 * through the engine's own envelope is what the second asks, and it is a different layer: the
 * first says nothing about whether a document survives the codec it will actually be written
 * through. Determinism is what the third asks, and neither of the others can see it, because two
 * documents can be equal while a writer emits their fields in an order that depends on a hash
 * map's iteration order.
 *
 * ### What the fixture cannot hold
 *
 * The fixture declares state on a page and not on the document, and that is a gap in the DSL
 * rather than a cut in coverage: `buildDocument` takes no parameter for any of the seven
 * document-level fields the document record declares, so a document carrying an `appState` cannot
 * be written with the builder at all. Putting one in the envelope would have failed the equality
 * by construction, or — worse — passed by reaching past the builder for `UiDocument.copy`, which
 * would leave the row asserting agreement about a shape no caller can produce. The page state
 * below is the same `StateDecl` on the same wire, reached through a parameter the builder has.
 *
 * ### Why the envelope is text and the siblings' are not
 *
 * The two codec tests in this module build their envelopes from parsed values, so that a
 * rejection runs against something the real parser produced. That is the right rule for a test
 * whose subject is a refusal, and it is not this one: the subject here is agreement between two
 * independently authored spellings, and a fixture assembled by the same library that will read
 * it back can only ever agree with itself about its own mistakes. So the text is the fixture,
 * and no test in this file builds a `Json {}`.
 */
class DslEqualsJsonTest {

    /**
     * The claim: one document, written twice, compared in that direction.
     *
     * The wire is the expected value and the builder's document is the actual one, so a failure
     * reads wire-first: the wire is the format's own contract, and a reader who has to decide
     * which of the two spellings moved should not have to work that out from the output.
     *
     * The shape assertions come first and are not decoration. An envelope that decoded to an
     * empty or a half-empty document would make the equality below true for a reason that has
     * nothing to do with the DSL, so the fixture's size is pinned before the comparison rather
     * than after it: two pages, nine nodes, the component declaration, and one node's prop key
     * read back out of the decoded table.
     */
    @Test
    fun `the hand-written envelope and the DSL build the same document`() {
        val handDecoded = decodeOrFail(
            HAND_WRITTEN_ENVELOPE.encodeToByteArray(),
            "the hand-written envelope did not decode",
        )

        assertEquals(2, handDecoded.pages.size, "the fixture did not decode two pages:\n$HAND_WRITTEN_ENVELOPE")
        assertEquals(9, handDecoded.nodes.size, "the fixture did not decode nine nodes:\n$HAND_WRITTEN_ENVELOPE")
        assertTrue(
            handDecoded.components.containsKey(ComponentDeclId("c_card")),
            "the fixture lost its component declaration:\n$HAND_WRITTEN_ENVELOPE",
        )
        assertEquals(
            setOf("spacing"),
            handDecoded.nodes.require(NodeId("n_home_root")).props.keys.map { it.value }.toSet(),
            "the home page root lost its prop:\n$HAND_WRITTEN_ENVELOPE",
        )

        val dsl = dslBuilt()
        assertEquals(
            handDecoded,
            dsl,
            "the wire and the builder disagree\nwire:\n${renderDocument(handDecoded)}\ndsl:\n${renderDocument(dsl)}",
        )
    }

    /**
     * The claim: a document the builder wrote survives the codec the engine writes it through.
     *
     * Separate from the row above because that one compares two spellings of a document and this
     * one compares a document with its own bytes, and a codec defect that only appears on the
     * way in cannot fail a comparison between two documents at all. It is also the row that
     * pins the envelope and the versions around the payload, which the equality above never
     * touches because the equality never goes through them.
     */
    @Test
    fun `a document the DSL built survives the engine codec`() {
        val built = dslBuilt()

        val decoded = decodeOrFail(
            JsonDocumentCodec.encode(built),
            "the codec could not read back what it had just written",
        )

        assertEquals(
            built,
            decoded,
            "the codec changed the document\nwire:\n${renderDocument(decoded)}\ndsl:\n${renderDocument(built)}",
        )
    }

    /**
     * The claim: a document's bytes are a function of the document, and those bytes are the
     * canonical text rather than merely equivalent text.
     *
     * The byte comparison is close to unfalsifiable on its own and the reason is worth stating
     * instead of hiding behind the assertion: the writer is handed the same model object twice
     * and nothing about that call varies. So the row is carried by the canonicality half, which
     * pins the properties of the encoded text that a reader can see — object keys sorted at every
     * depth, two spaces of indentation per level with no tab and no carriage return, no
     * whitespace at the end of a line, and one newline after the closing brace.
     *
     * Sorting is checked at every depth rather than at the top of the document because the
     * orders worth checking are the ones a record's own field order does not already produce:
     * the node table's keys, a component declaration's fields, the two arms of a property union,
     * the keys of the maps behind every prop. A check at the envelope header alone would pass
     * over every one of them.
     */
    @Test
    fun `the encoding is canonical and the same document writes the same bytes`() {
        val built = dslBuilt()

        val first = JsonDocumentCodec.encode(built)
        val second = JsonDocumentCodec.encode(built)

        assertContentEquals(first, second, "two encodes of one document produced different bytes")

        val text = first.decodeToString()
        val unsorted = unsortedKeyPaths(ForgeJson.parseToJsonElement(text), "envelope")
        assertTrue(
            unsorted.isEmpty(),
            "canonical text writes every object's keys sorted; these are not:\n" + unsorted.joinToString("\n"),
        )
        assertTrue(text.endsWith("}\n"), "canonical text ends with one newline after the closing brace")
        assertTrue('\t' !in text && '\r' !in text, "canonical text has no tab and no carriage return")
        assertTrue(
            text.lines().all { line -> line.takeWhile { it == ' ' }.length % 2 == 0 },
            "canonical text indents two spaces per level, so no line carries an odd number of spaces",
        )
        assertTrue(
            text.lines().all { line -> line == line.trimEnd() },
            "canonical text leaves no whitespace at the end of a line",
        )
    }

    /**
     * Every object in [element] whose keys are not in ascending order, named by its path.
     *
     * Ascending rather than merely ordered, because the format's promise is a total one and a
     * check that accepted either order would pass over a writer that emitted nothing in
     * particular. A [JsonArray] is not an object and is not checked; only object keys sort.
     */
    private fun unsortedKeyPaths(element: JsonElement, path: String): List<String> = when (element) {
        is JsonObject -> buildList {
            val keys = element.keys.toList()
            if (keys != keys.sorted()) add("$path: ${keys.joinToString(", ")}")
            for ((key, value) in element) addAll(unsortedKeyPaths(value, "$path/$key"))
        }

        is JsonArray -> element.flatMapIndexed { index, value -> unsortedKeyPaths(value, "$path[$index]") }

        else -> emptyList()
    }

    /**
     * The document as text, for a failure message.
     *
     * A `NodeTable` renders as its own size, so an equality failure over two documents reports a
     * difference inside the table as two byte-identical strings. Every field whose own `toString`
     * says something is written, plus every node row in ascending id order with all of its own
     * fields — which is every field except the table's.
     */
    private fun renderDocument(document: UiDocument): String = buildString {
        append("meta          ").append(document.meta).append('\n')
        append("app           ").append(document.app.packageName)
        append(" startPage=").append(document.app.startPage).append('\n')
        append("pages         ").append(document.pages.values.sortedBy { it.id.value }.joinToString(" | "))
        append('\n')
        append("components    ").append(document.components).append('\n')
        append("appState      ").append(document.appState).append('\n')
        append("nodes\n")
        for (id in document.nodes.ids()) {
            append("  ").append(document.nodes.require(id)).append('\n')
        }
    }

    /**
     * [bytes] through the codec, with the offending text in the failure rather than a trace.
     *
     * A hand-written envelope and a hand-built document both go wrong by being nearly right, and
     * the near miss is legible in the text and invisible in a serialization stack trace, so the
     * trace is what gets thrown away.
     */
    private fun decodeOrFail(bytes: ByteArray, what: String): UiDocument = try {
        JsonDocumentCodec.decode(bytes).document
    } catch (failure: SerializationException) {
        throw AssertionError("$what: ${failure.message}\n\n${bytes.decodeToString()}")
    }

    /**
     * The same content, written with the builder.
     *
     * Node ids are given rather than minted, so the id in the envelope and the id in the call
     * below are the same literal and a correspondence between the two can be read off the page
     * without counting. The two are kept deliberately independent rather than shared through a
     * constant: a single source for the ids would make the two sides agree by construction, and
     * the row would stop being able to fail.
     */
    private fun dslBuilt(): UiDocument = buildDocument(
        name = "HandWritten",
        packageName = "com.example.dual",
        startPage = PageId("p_home"),
    ) {
        page(name = "Home", route = "/home", id = PageId("p_home"), state = listOf(LABEL_STATE)) {
            node("core.Column", id = NodeId("n_home_root")) {
                prop("spacing", Value.Dp(8f))
                modifier("layout.padding", mapOf(PropertyKey("all") to PropertyValue.Const(Value.Dp(16f))))
                slot(
                    "children",
                    listOf(
                        node("m3.Text", id = NodeId("n_greeting"), name = "Greeting") {
                            prop("text", Value.Str("Hello"))
                        },
                        node("m3.Text", id = NodeId("n_live")) {
                            computed("text", Expr.Ref(RefTarget.State(StateId("s_label"))))
                        },
                        node("m3.Button", id = NodeId("n_action")) {
                            event("onClick", SET_LABEL_ON_CLICK)
                            slot(
                                "content",
                                listOf(
                                    node("m3.Text", id = NodeId("n_action_label")) {
                                        prop("text", Value.Str("Tap"))
                                    },
                                ),
                            )
                        },
                    ),
                )
            }
        }
        page(name = "Settings", route = "/settings", id = PageId("p_settings"), state = listOf(ENABLED_STATE)) {
            node("core.Column", id = NodeId("n_settings_root")) {
                prop("spacing", Value.Dp(12f))
                slot(
                    "children",
                    listOf(
                        node("m3.Text", id = NodeId("n_settings_title")) {
                            prop("text", Value.Str("Settings"))
                        },
                    ),
                )
            }
        }
        component(
            id = ComponentDeclId("c_card"),
            name = "Card",
            params = listOf(ParamDecl(name = ParamName("title"), type = TypeRef.Str)),
            slots = listOf(SlotDecl(name = SlotName("content"))),
        ) {
            node("core.Column", id = NodeId("n_card_root")) {
                computed("title", Expr.Ref(RefTarget.Param(ParamName("title"))))
                slot(
                    "children",
                    listOf(
                        node("m3.Text", id = NodeId("n_card_body")) {
                            prop("text", Value.Str("Body"))
                        },
                    ),
                )
            }
        }
    }
}

/** The page state the greeting reads and the button's handler writes. */
private val LABEL_STATE: StateDecl = StateDecl(
    id = StateId("s_label"),
    name = "label",
    type = TypeRef.Str,
    initial = Value.Str("Live"),
)

/** The settings page's own state, so a second `StateDecl` shape is on the wire as well. */
private val ENABLED_STATE: StateDecl = StateDecl(
    id = StateId("s_enabled"),
    name = "enabled",
    type = TypeRef.Bool,
    initial = Value.Bool(true),
)

/** One step, so the handler's arguments carry both arms of the property union. */
private val SET_LABEL_ON_CLICK: ActionSequence = ActionSequence(
    listOf(
        ActionStep(
            action = ActionId("state.set"),
            args = mapOf(
                PropertyKey("target") to PropertyValue.Computed(Expr.Ref(RefTarget.State(StateId("s_label")))),
                PropertyKey("value") to PropertyValue.Const(Value.Str("Tapped")),
            ),
        ),
    ),
)

/**
 * The canonical envelope, written out by hand.
 *
 * Every key and tag below was read off a declaration or off a fixture rather than inferred: the
 * envelope's four keys from the envelope record, every record field from its own property name —
 * no plain record in the document carries a tag of its own — and every union arm from the
 * variant's declared wire tag. Those tags are `const` and `expr` for the property union, `ref`
 * for the expression union, `state` and `param` for the target a reference names, and `str`,
 * `dp` and `bool` for the values. The fixtures under the generated-compile suite are the second
 * source for the shapes of a component declaration, a state declaration and an action sequence,
 * and this envelope agrees with them key for key.
 *
 * Two pages, one component declaration, nine nodes, six literal props, two computed props, a
 * modifier with an argument, an event handler and page-level state: enough to reach every record
 * the builder writes, which is the point — a fixture holding only pages and props would pass the
 * first row while proving nothing about a component declaration or a state declaration.
 */
private val HAND_WRITTEN_ENVELOPE: String = """
    {
      "format": "forge.ui-document",
      "formatVersion": 1,
      "schemaVersion": 1,
      "payload": {
        "app": {
          "packageName": "com.example.dual",
          "startPage": "p_home"
        },
        "components": {
          "c_card": {
            "id": "c_card",
            "name": "Card",
            "params": [
              {"name": "title", "type": {"type": "str"}}
            ],
            "root": "n_card_root",
            "slots": [
              {"name": "content"}
            ]
          }
        },
        "meta": {"name": "HandWritten"},
        "nodes": {
          "n_action": {
            "events": {
              "onClick": {
                "steps": [
                  {
                    "action": "state.set",
                    "args": {
                      "target": {
                        "type": "expr",
                        "expr": {"type": "ref", "target": {"type": "state", "id": "s_label"}}
                      },
                      "value": {"type": "const", "value": {"type": "str", "v": "Tapped"}}
                    }
                  }
                ]
              }
            },
            "id": "n_action",
            "slots": {"content": ["n_action_label"]},
            "type": "m3.Button"
          },
          "n_action_label": {
            "id": "n_action_label",
            "props": {"text": {"type": "const", "value": {"type": "str", "v": "Tap"}}},
            "type": "m3.Text"
          },
          "n_card_body": {
            "id": "n_card_body",
            "props": {"text": {"type": "const", "value": {"type": "str", "v": "Body"}}},
            "type": "m3.Text"
          },
          "n_card_root": {
            "id": "n_card_root",
            "props": {
              "title": {
                "type": "expr",
                "expr": {"type": "ref", "target": {"type": "param", "name": "title"}}
              }
            },
            "slots": {"children": ["n_card_body"]},
            "type": "core.Column"
          },
          "n_greeting": {
            "id": "n_greeting",
            "name": "Greeting",
            "props": {"text": {"type": "const", "value": {"type": "str", "v": "Hello"}}},
            "type": "m3.Text"
          },
          "n_home_root": {
            "id": "n_home_root",
            "modifiers": [
              {"args": {"all": {"type": "const", "value": {"type": "dp", "v": 16}}}, "type": "layout.padding"}
            ],
            "props": {"spacing": {"type": "const", "value": {"type": "dp", "v": 8}}},
            "slots": {"children": ["n_greeting", "n_live", "n_action"]},
            "type": "core.Column"
          },
          "n_live": {
            "id": "n_live",
            "props": {
              "text": {"type": "expr", "expr": {"type": "ref", "target": {"type": "state", "id": "s_label"}}}
            },
            "type": "m3.Text"
          },
          "n_settings_root": {
            "id": "n_settings_root",
            "props": {"spacing": {"type": "const", "value": {"type": "dp", "v": 12}}},
            "slots": {"children": ["n_settings_title"]},
            "type": "core.Column"
          },
          "n_settings_title": {
            "id": "n_settings_title",
            "props": {"text": {"type": "const", "value": {"type": "str", "v": "Settings"}}},
            "type": "m3.Text"
          }
        },
        "pages": {
          "p_home": {
            "id": "p_home",
            "name": "Home",
            "root": "n_home_root",
            "route": "/home",
            "state": [
              {
                "id": "s_label",
                "name": "label",
                "type": {"type": "str"},
                "initial": {"type": "str", "v": "Live"}
              }
            ]
          },
          "p_settings": {
            "id": "p_settings",
            "name": "Settings",
            "root": "n_settings_root",
            "route": "/settings",
            "state": [
              {
                "id": "s_enabled",
                "name": "enabled",
                "type": {"type": "bool"},
                "initial": {"type": "bool", "v": true}
              }
            ]
          }
        }
      }
    }
""".trimIndent()
