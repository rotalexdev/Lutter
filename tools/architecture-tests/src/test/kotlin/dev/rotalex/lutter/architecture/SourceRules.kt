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

    /*
     * Declaration shape, shared by the two rules that have to reason about a type rather than
     * about a token: which line a `class`/`object` head sits on, which annotations sit directly
     * above it, and where its primary constructor ends. All three are read off the text because
     * nothing here has a syntax tree; the shared qualifier list is what keeps the two rules
     * agreeing about what a declaration head looks like.
     */
    private val typeModifiers =
        "public|private|protected|internal|expect|actual|final|open|abstract|sealed|data|" +
            "value|inline|annotation|enum|inner|external"

    private val declarationHead =
        Regex("""^[ \t]*(?:(?:$typeModifiers)[ \t]+)*(?:class|object)[ \t]+([A-Za-z_]\w*)""")

    private val sealedRoot =
        Regex("""^[ \t]*(?:(?:$typeModifiers)[ \t]+)*sealed[ \t]+(?:interface|class)[ \t]+([A-Za-z_]\w*)""")

    private val annotationLine = Regex("""^[ \t]*@""")

    private val serializable = Regex("""^[ \t]*@Serializable\b""")

    private val serialName = Regex("""@SerialName\s*\(""")

    /*
     * The subject of a branching expression. `[^()]` spans newlines, so a subject written one
     * term per line is read whole; a nested call inside the subject is allowed one level of
     * parentheses, which is all this codebase writes.
     */
    private val branchingSubject = Regex("""\bwhen\b[ \t\r\n]*(\((?:[^()]|\([^()]*\))*\))""")

    private val componentOrNodeType =
        Regex("""\bComponentType\b|\bcomponentType\b|\.\s*type\b|(?<![\w.])type(?![\w])""")

    private val floatType = Regex("""(?<![\w.])(?:Float|Double)(?![\w])""")

    private val canonicalSerializer = Regex("""Canonical(?:Float|Double)""")

    private val whitespace = Regex("""\s+""")

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

    /**
     * The persisted sealed hierarchies declared in [text].
     *
     * Returned by name rather than by file so a variant declared away from its root is still
     * checked: the caller unions the answer across everything it scans.
     */
    fun persistedRootsIn(text: String): Set<String> {
        val lines = text.lines()
        val roots = mutableSetOf<String>()

        lines.forEachIndexed { index, line ->
            val named = sealedRoot.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            if (annotationBlockAbove(lines, index).any { serializable.containsMatchIn(it) }) roots += named
        }

        return roots
    }

    /**
     * Variants of a hierarchy in [persistedRoots] that never spell the tag they are written with.
     *
     * A variant is a declaration whose supertype list names a persisted root. A class that
     * merely holds a value of that type is not one, which is why the supertype colon is looked
     * for outside the primary constructor's parentheses.
     */
    fun untaggedVariantOccurrences(text: String, persistedRoots: Set<String>): List<String> {
        val lines = text.lines()
        val occurrences = mutableListOf<String>()

        lines.forEachIndexed { index, line ->
            val declared = declarationHead.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            val colon = depthZeroColons(lines, index).firstOrNull() ?: return@forEachIndexed
            val supertype = firstWord(textAfter(lines, colon))
            if (supertype !in persistedRoots) return@forEachIndexed
            if (annotationBlockAbove(lines, index).any { serialName.containsMatchIn(it) }) return@forEachIndexed

            occurrences += "$declared is a $supertype without a tag (line ${index + 1})"
        }

        return occurrences
    }

    /**
     * Branching expressions in [text] whose subject is a component type or a node's type.
     *
     * Only the subject is read. The arms are where a registry lookup would be written, and a
     * rule that inspected them would report every `when` in the engine rather than the ones that
     * are a dispatch.
     */
    fun componentWhenOccurrences(text: String): List<String> =
        branchingSubject
            .findAll(text)
            .map { it.groupValues[1] }
            .filter { componentOrNodeType.containsMatchIn(it) }
            .map { subject -> "dispatches on ${collapse(subject)}" }
            .toList()

    /**
     * Persisted floating-point fields in [text] that do not carry the canonical serializer.
     *
     * A persisted field is one on a type the serialization plugin was pointed at. That is the
     * whole boundary: a float in a decoder, an evaluator or the canonicalizer itself is not a
     * number on its way into a document, and a rule that reported those would be reporting the
     * code that is doing the work.
     */
    fun uncanonicalFloatOccurrences(text: String): List<String> {
        val lines = text.lines()
        val occurrences = mutableListOf<String>()

        lines.forEachIndexed { index, line ->
            val declared = declarationHead.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            if (annotationBlockAbove(lines, index).none { serializable.containsMatchIn(it) }) return@forEachIndexed

            constructorParameters(lines, index)
                .filter { floatType.containsMatchIn(declaredTypeOf(it)) }
                .filterNot { canonicalSerializer.containsMatchIn(it) }
                .forEach { parameter ->
                    occurrences +=
                        "$declared persists a float that is not canonical (line ${index + 1}): " +
                            collapse(parameter)
                }
        }

        return occurrences
    }

    /** Contiguous annotation lines above [declarationLine]; a blank line does not end the block. */
    private fun annotationBlockAbove(lines: List<String>, declarationLine: Int): List<String> {
        val block = mutableListOf<String>()
        var index = declarationLine - 1

        while (index >= 0) {
            val line = lines[index]
            // A blank line ends the block. It does not get skipped: an annotation block is
            // the contiguous run of annotations directly above the declaration, so walking
            // past a gap collects the *previous* declaration's annotations too — and then a
            // variant with no tag of its own inherits the one declared above it and the rule
            // reports nothing. It looked for a while like the rule worked, because the first
            // variant in a hierarchy is genuinely tagged and every later one was being
            // laundered through it. The negative test is the only reason that was caught.
            if (line.isBlank()) break
            else if (annotationLine.containsMatchIn(line)) block += line
            else break
            index--
        }

        return block.asReversed()
    }

    /**
     * Every `:` the declaration on [fromLine] has outside its constructor's parentheses.
     *
     * The scan stops at the opening brace of the body, and string literals and trailing
     * comments are removed first so a default value cannot move the brace it is balancing.
     */
    private fun depthZeroColons(lines: List<String>, fromLine: Int): List<Pair<Int, Int>> {
        val found = mutableListOf<Pair<Int, Int>>()
        var depth = 0

        for (index in fromLine until lines.size) {
            val code = codeOnly(lines[index])
            for (column in code.indices) {
                when (code[column]) {
                    '(' -> depth++
                    ')' -> depth--
                    '{' -> if (depth == 0) return found
                    ':' -> if (depth == 0) {
                        found += index to column
                    }
                }
            }
        }

        return found
    }

    /**
     * [line] with string literal contents and anything from a `//` onwards removed.
     *
     * Per line, and blind to a raw string: a `"""` opener is read as an ordinary quote, so a
     * file whose raw string spans lines can put a bracket count out. That is the same
     * limitation `mutableObjectStateOccurrences` states for brace counting, and the fix — a
     * file-level scanner that tracks `"""` — is a rewrite of this helper rather than a patch.
     */
    private fun codeOnly(line: String): String {
        val code = StringBuilder(line.length)
        var index = 0

        while (index < line.length) {
            when {
                line[index] == '"' -> {
                    code.append('"')
                    index++
                    while (index < line.length && line[index] != '"') {
                        if (line[index] == '\\') index++
                        index++
                    }
                    if (index < line.length) code.append('"')
                }

                line[index] == '/' && index + 1 < line.length && line[index + 1] == '/' -> return code.toString()

                else -> {
                    code.append(line[index])
                }
            }
            index++
        }

        return code.toString()
    }

    /** The text from [colon] onwards, far enough to reach a supertype written on the next line. */
    private fun textAfter(lines: List<String>, colon: Pair<Int, Int>): String {
        val head = lines[colon.first].substring(colon.second + 1)
        val tail = lines.subList(colon.first + 1, minOf(colon.first + 3, lines.size))
        return (listOf(head) + tail).joinToString(" ")
    }

    private fun firstWord(text: String): String =
        text.trimStart().takeWhile { !it.isWhitespace() && it != ',' }

    /** [text] with each run of whitespace written as a single space. */
    private fun collapse(text: String): String = text.split(whitespace).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * The primary constructor's parameters of the declaration on [fromLine].
     *
     * Angle and square brackets are part of the parameter and are kept in it; they only change
     * *when a comma separates two parameters*, because `Map<K, V>` has a comma that does not.
     * A closing bracket counts only while something is open, so an arrow in a default value
     * cannot drive the count down and make the next comma look top-level.
     */
    private fun constructorParameters(lines: List<String>, fromLine: Int): List<String> {
        val parameters = mutableListOf<String>()
        val parameter = StringBuilder()
        var depth = 0

        for (index in fromLine until lines.size) {
            for (character in codeOnly(lines[index])) {
                if (character == '(') {
                    depth++
                } else if (character == ')') {
                    depth--
                    if (depth == 0) return (parameters + parameter.toString()).trimmed()
                } else if (character == ',' && depth == 1) {
                    parameters += parameter.toString()
                    parameter.clear()
                } else if (depth > 0) {
                    parameter.append(character)
                    if (character == '<' || character == '[') depth++
                    if ((character == '>' || character == ']') && depth > 1) depth--
                }
            }
        }

        return emptyList()
    }

    private fun List<String>.trimmed(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }

    /** The type a constructor parameter declares: the text after its first depth-zero colon. */
    private fun declaredTypeOf(parameter: String): String {
        var depth = 0

        parameter.forEachIndexed { index, character ->
            when (character) {
                '<', '(', '[' -> depth++
                '>', ']', ')' -> if (depth > 0) depth--
                ':' -> if (depth == 0) return parameter.substring(index + 1)
            }
        }

        return ""
    }
}
