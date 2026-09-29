package dev.rotalex.lutter.architecture

/**
 * Text-level checks that the architecture rules share.
 *
 * These are deliberately string scanners and not PSI queries. The rules they implement have
 * to work on a PSI layer whose last release predates Kotlin 2.4, and a scanner whose failure
 * mode is a false positive in review is a far smaller risk than an enforcer that fails to
 * compile. Each limitation is stated where it applies rather than hidden.
 */
internal object SourceRules {

    /*
     * The `?` is optional and the `>` is mandatory. It used to be the other way round:
     * `Any\s*\?>?` reads as a required literal question mark followed by an *optional*
     * closing angle bracket, so the rule matched `Map<String, Any?>` and let `Map<String,
     * Any>` through - which is the exact shape PLAN §23.4 forbids, and the one its own
     * KDoc below claims to catch. A guardrail that misses the primary case while catching
     * the variant is worse than no guardrail, because it reads as coverage.
     */
    private val untypedStringMap =
        Regex("""(?:Mutable)?Map\s*<\s*String\s*,\s*Any\s*\??\s*>""")

    private val objectDeclaration =
        Regex("""\b(?:companion\s+)?object\s+([A-Za-z_]\w*)?\s*(?::[^={]*)?\{""")

    private val modifiers =
        "public|private|protected|internal|expect|actual|final|open|lateinit|const|override|suspend"

    private val varProperty = Regex("""^\s*(?:(?:$modifiers)\s+)*var\s""")

    private val stringLiteral = Regex("""\"(?:[^\"\\]|\\.)*\"""")

    private val lineComment = Regex("""//.*$""")

    /** Every `Map<String, Any>` (or `Any?`, or `MutableMap`) written in [text]. */
    fun untypedStringMapOccurrences(text: String): List<String> =
        untypedStringMap.findAll(text).map { it.value }.toList()

    /**
     * Objects in [text] that declare a `var` directly in their own body.
     *
     * "Directly" means at the object's own brace depth. A `var` inside a nested class,
     * function or lambda is instance state of something else and is not object state, so it
     * is not reported.
     *
     * Limitations, stated because they are real: braces and quotes are counted per line with
     * string literals removed, so an unbalanced brace inside a block comment or a raw string
     * would derail the scan. In exchange the check cannot be fooled by formatting, and a
     * `var` on an object in a file with a stray brace is something a reviewer sees.
     */
    fun mutableObjectStateOccurrences(text: String): List<String> {
        val occurrences = mutableListOf<String>()
        var depth = 0
        var openObject: String? = null
        var objectDepth = -1

        text.lines().forEachIndexed { index, rawLine ->
            val line = lineComment.replace(stringLiteral.replace(rawLine, ""), "")

            val currentObject = openObject
            if (currentObject == null) {
                val declared = objectDeclaration.find(line)
                if (declared != null) {
                    openObject = declared.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "<anonymous>"
                    objectDepth = depth
                }
            } else if (depth == objectDepth + 1 && varProperty.containsMatchIn(line)) {
                occurrences += "$currentObject declares a var (line ${index + 1})"
            }

            depth += line.count { it == '{' } - line.count { it == '}' }

            if (openObject != null && depth <= objectDepth) {
                openObject = null
                objectDepth = -1
            }
        }

        return occurrences
    }
}
