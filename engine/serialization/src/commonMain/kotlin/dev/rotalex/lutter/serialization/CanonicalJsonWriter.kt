package dev.rotalex.lutter.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical bytes for a JSON value (PLAN §18.2).
 *
 * Object keys sorted UTF-16 lexicographic, arrays in model order, 2-space indent with LF and
 * a trailing newline. Scalars print verbatim, so canonical numbers keep the model's spelling
 * with no second formatter.
 */
internal object CanonicalJsonWriter {

    /** Canonical text of [value]: encoded through [ForgeJson], keys sorted, pretty. */
    internal fun <T> encode(serializer: KSerializer<T>, value: T): String =
        write(ForgeJson.encodeToJsonElement(serializer, value))

    /** Canonical text of [element]. */
    internal fun write(element: JsonElement): String = buildString {
        appendElement(canonicalize(element), 0)
        append('\n')
    }

    /** [element] with every object's keys sorted, recursively. Arrays keep model order. */
    internal fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            val ordered = LinkedHashMap<String, JsonElement>(element.size)
            for ((key, value) in element.entries.sortedBy { it.key }) ordered[key] = canonicalize(value)
            JsonObject(ordered)
        }
        is JsonArray -> JsonArray(element.map(::canonicalize))
        is JsonPrimitive -> element
    }

    // Recursion depth follows document depth; documents nest shallowly.
    private fun StringBuilder.appendElement(element: JsonElement, indent: Int) {
        when (element) {
            is JsonObject -> appendObject(element, indent)
            is JsonArray -> appendArray(element, indent)
            is JsonPrimitive -> append(element.toString())
        }
    }

    // Empty structures stay inline: `{}` is one line a diff can ignore, not two.
    private fun StringBuilder.appendObject(element: JsonObject, indent: Int) {
        if (element.isEmpty()) {
            append("{}")
            return
        }
        append("{\n")
        val entries = element.entries.toList()
        entries.forEachIndexed { index, (key, value) ->
            appendIndent(indent + 1)
            append(JsonPrimitive(key).toString())
            append(": ")
            appendElement(value, indent + 1)
            if (index < entries.size - 1) append(",")
            append("\n")
        }
        appendIndent(indent)
        append("}")
    }

    private fun StringBuilder.appendArray(element: JsonArray, indent: Int) {
        if (element.isEmpty()) {
            append("[]")
            return
        }
        append("[\n")
        element.forEachIndexed { index, value ->
            appendIndent(indent + 1)
            appendElement(value, indent + 1)
            if (index < element.size - 1) append(",")
            append("\n")
        }
        appendIndent(indent)
        append("]")
    }

    private fun StringBuilder.appendIndent(indent: Int) {
        repeat(indent) { append("  ") }
    }
}
