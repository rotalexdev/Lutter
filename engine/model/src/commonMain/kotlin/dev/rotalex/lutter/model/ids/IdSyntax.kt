package dev.rotalex.lutter.model.ids

/**
 * The syntax rules every identifier in the engine obeys.
 *
 * Internal on purpose. An identifier is constructed through one of the typed classes in
 * `Ids.kt`, and those constructors enforce these rules. Exposing a way to ask "is this a
 * valid id?" separately would be a second place for the answer to live.
 *
 * There are two shapes, and the distinction is load-bearing rather than cosmetic. A
 * *simple* id names something inside a document: a page, a node, a slot. A *namespaced* id
 * names something the engine resolves through a registry: a component type, a modifier, an
 * action, a function, a plugin. Namespaced ids are dotted, `core.Column` and
 * `nav.navigate`, because a registry key has to be able to say who owns the name. A
 * document-local id never can, and giving it a dot would suggest a registry lookup that
 * does not exist.
 */
internal object IdSyntax {

    /**
     * The longest an id may be, in characters.
     *
     * Sixty-four is short enough that an id can be a database column, a file name fragment
     * and a Compose `key` without any of those limits becoming the reason a name is
     * rejected.
     */
    const val MAX_LENGTH: Int = 64

    /** A simple id: `[A-Za-z0-9_]{1,64}`. */
    private val SIMPLE = Regex("^[A-Za-z0-9_]{1,64}$")

    /**
     * A namespaced id: two or more simple segments joined by dots.
     *
     * At least two segments, because one segment is a simple id wearing a dot, and the
     * registry lookup a namespaced id exists for would find nothing.
     *
     * The per-segment bound is in the pattern; the *total* bound is checked separately in
     * [isValidNamespaced]. A pattern alone cannot express both, and an id that satisfies
     * every segment rule can still be arbitrarily long.
     */
    private val NAMESPACED = Regex("^[A-Za-z0-9_]{1,64}(\\.[A-Za-z0-9_]{1,64})+$")

    fun isValidSimple(value: String): Boolean = SIMPLE.matches(value)

    fun isValidNamespaced(value: String): Boolean =
        value.length <= MAX_LENGTH && NAMESPACED.matches(value)
}
