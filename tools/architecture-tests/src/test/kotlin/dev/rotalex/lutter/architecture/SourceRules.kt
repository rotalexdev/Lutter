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

    /**
     * A backticked name on a declaration, with the keyword and any modifiers in front of it.
     *
     * Anchored at the line start and matched per line so a KDoc line beginning `*` and a
     * `//` comment cannot be read as a declaration — both are common in this codebase and
     * both carry backticked prose like `` `num.format` `` that is not a name at all.
     */
    private val declaredBacktickName =
        Regex(
            """^[ \t]*(?:[\w@]+[ \t]+)*(?:fun|class|interface|object)[ \t]+(?:<[^>\n]*>[ \t]*)?(`[^`\n]+`)""",
            RegexOption.MULTILINE,
        )

    /**
     * Characters the JVM refuses in a method name.
     *
     * `. ; [ /` are excluded by the class file format itself. `<` and `>` are legal there but
     * break every Kotlin/Java tool that reads a signature, so a name carrying them is wrong
     * either way.
     *
     * **A space is not on this list.** Kotlin's backticks exist partly to allow
     * `` `a name with spaces` ``, which is the idiom every test in this repository uses and
     * which the JVM accepts. An earlier draft of this rule included whitespace "to be safe"
     * and would have reported every test name in the codebase.
     *
     * The `[` is escaped because an unescaped one inside a character class opens a *nested*
     * class in `java.util.regex` and the whole pattern fails to compile. Python's `re` accepts
     * the unescaped spelling, so a port-and-check pass cannot catch this one — it throws
     * `PatternSyntaxException` from `SourceRules`' initialiser and fails every rule in this
     * module at once, because an `object`'s property initialisers run before any of its
     * functions.
     */
    private val illegalJvmNameCharacter = Regex("""[.;\[\]/<>]""")

    /**
     * The JVM-only collection members that reach `commonMain` without an import.
     *
     * Every name here is a member of `java.util.Map` / `java.util.Collection` and of nothing
     * in Kotlin's common stdlib, and every one is reached through JVM interop, so
     * `NoPlatformApisInCommonMainTest` cannot see it: that rule reads `imports`, and these
     * calls need none.
     *
     * The leading dot is what keeps a *declaration* out of it. `fun merge(other: Overlay)` is
     * an ordinary domain method and is not reported, while `overlay.merge(other)` is. What the
     * dot cannot do is read the receiver's type, so a future non-collection type with a method
     * of one of these names is reported too; that is the stated cost of a text rule, and the
     * fix is a name, not a suppression.
     *
     * The trailing `(` or `{` is what makes it a *call*. Both are needed, and dropping the
     * `{` is the bug this rule was written after: `removeIf { it.isStale() }` and
     * `replaceAll { k, v -> v }` take their functional interface as a trailing lambda and have
     * no parentheses at all, so a pattern that demanded `(` reports the seven members that are
     * called with arguments and silently misses the two that are not. A rule that covers the
     * common spelling of a defect and not the other spelling of it is worse than no rule,
     * because it reads as coverage.
     */
    private val jvmOnlyCollectionMember =
        Regex(
            """\.\s*(putIfAbsent|putIfPresent|computeIfAbsent|computeIfPresent|compute|merge|removeIf|replaceAll|getOrDefault)\s*[({]""",
        )

    /**
     * `forEach` in the shape only `java.util.Map.forEach(BiConsumer)` has.
     *
     * Arity is the discriminator, and it is exact. The Java form takes two parameters;
     * Kotlin's `Map.forEach` takes one — a `Map.Entry` — and Kotlin's `Iterable.forEach` takes
     * one element. So `{ (key, value) -> }` is the common `Map.forEach` and is legal, and two
     * *bare* comma-separated parameters is the only spelling that has to be a `BiConsumer`.
     * The parentheses are the whole rule: they are what keeps this from reporting all
     * twenty-eight legitimate `forEach` calls in this repository's `commonMain`.
     *
     * `forEachIndexed` cannot match, because `\s*\{` has to follow `forEach` itself.
     */
    private val forEachAsBiConsumer = Regex("""\.\s*forEach\s*\{\s*[A-Za-z_]\w*\s*,\s*[A-Za-z_]\w*\s*->""")

    /**
     * Declarations in [text] whose backticked name cannot be a JVM method name.
     *
     * Kotlin's backticks allow almost any character in an identifier, and the compiler accepts
     * it — then the JVM backend rejects it with `Name contains illegal characters`, which
     * fails the *whole module's* test source set rather than the one test. A dotted function
     * name has cost this repository four CI round-trips, every one of them a red build whose
     * only cause was a name.
     */
    fun illegalJvmNameOccurrences(text: String): List<String> =
        declaredBacktickName
            .findAll(text)
            .mapNotNull { match ->
                val name = match.groupValues[1]
                val offender = illegalJvmNameCharacter.find(name)?.value ?: return@mapNotNull null
                val line = text.take(match.range.first).count { it == '\n' } + 1
                "$name carries '$offender', which the JVM refuses in a method name (line $line)"
            }.toList()

    /**
     * Calls in [text] to a collection member that only exists on the JVM.
     *
     * This is the replacement for a compiler that is no longer asked. `wasmJs` was what used
     * to prove `commonMain` was pure: `owners.putIfAbsent(name, page.name)` in
     * `ReservedCodegenNames.kt` resolved on desktop and failed only on
     * `:engine:codegen:compileKotlinWasmJs`, with `Unresolved reference 'putIfAbsent'`. That
     * call carried no `java.` import, so `NoPlatformApisInCommonMainTest` would not have
     * reported it either. With the Wasm target gone nothing is left that notices, which is
     * what this function and the test that drives it are for.
     *
     * Comments and literals are removed first, and that is the part that makes the rule
     * trustworthy rather than a nuisance. This codebase explains itself in KDoc, so a
     * paragraph about `putIfAbsent` reads exactly like the call that has to be reported, and a
     * rule that fires on a comment is a rule that gets deleted after its first false alarm.
     * A member named in a KDoc line or inside a message is prose; a member named in the code
     * between them is the defect.
     *
     * Limitations, stated because they are real: the strip is a single left-to-right pass, so
     * a `${…}` interpolation *inside a raw string* is treated as string content and is not
     * scanned. Everything else — line comments, block and KDoc comments across lines, quoted
     * and raw strings, char literals — is handled, and newlines are preserved so the line
     * numbers below are the file's own.
     */
    fun jvmOnlyCollectionMemberOccurrences(text: String): List<String> {
        val code = codeWithoutCommentsOrLiterals(text)

        val named = jvmOnlyCollectionMember.findAll(code).map { it.groupValues[1] to it.range.first }
        val biConsumer = forEachAsBiConsumer.findAll(code).map { "forEach" to it.range.first }

        return (named + biConsumer)
            .map { (member, at) ->
                val line = code.take(at).count { it == '\n' } + 1
                "$member is a JVM-only collection member with no common equivalent (line $line)"
            }.sorted()
            .toList()
    }

    /**
     * [text] with comments and literals removed and newlines kept, so a match's column still
     * carries the line number it had in the file.
     *
     * One left-to-right pass rather than a chain of regular expressions, and that is the point
     * rather than an accident: each construct is consumed whole, so a `//` inside a string is
     * eaten with the string, a `"` inside a line comment is eaten with the comment, and the
     * order the branches are tested in cannot matter. Per-line regexes cannot do this — a
     * `"""` opener is three characters and a KDoc runs for as many lines as it likes — which is
     * the limitation `codeOnly` above states for brace counting.
     *
     * Every branch calls [appendSkippedNewlines], including the two whose span cannot contain
     * one. That is deliberate: it makes "the line numbers survive" structural instead of
     * something to be re-derived per branch, and getting it wrong is invisible until a real
     * file fails — a KDoc is twenty lines long, so skipping it without counting reports a
     * finding on the wrong line, and a line number is the entire reason the message carries
     * one.
     */
    private fun codeWithoutCommentsOrLiterals(text: String): String {
        val code = StringBuilder(text.length)
        var index = 0

        while (index < text.length) {
            val character = text[index]
            val next = text.getOrNull(index + 1)
            val skipTo =
                when {
                    character == '/' && next == '/' -> indexOfOrEnd(text, '\n', index)
                    character == '/' && next == '*' -> endOfBlockComment(text, index + 2)
                    text.startsWith("\"\"\"", index) -> endOfRawString(text, index + 3)
                    character == '"' -> endOfQuoted(text, index + 1, '"')
                    character == '\'' -> endOfQuoted(text, index + 1, '\'')
                    else -> -1
                }

            if (skipTo >= 0) {
                code.appendSkippedNewlines(text, index, skipTo)
                index = skipTo
            } else {
                if (character == '\n') code.append('\n') else code.append(character)
                index++
            }
        }

        return code.toString()
    }

    /**
     * Appends one newline for every newline in `text[start until end]`, so a stripped comment or
     * literal leaves every line after it on the line it was written on.
     *
     * Applied uniformly rather than only to the constructs that can span lines — a line comment
     * and a quoted string stop before their newline, so appending for them is a no-op, and one
     * unconditional call is easier to be right about than four conditional ones.
     */
    private fun StringBuilder.appendSkippedNewlines(text: String, start: Int, end: Int) {
        for (position in start until end) {
            if (text[position] == '\n') append('\n')
        }
    }

    /** Index of the next [character] from [from], or the end of [text] when there is none. */
    private fun indexOfOrEnd(text: String, character: Char, from: Int): Int {
        val found = text.indexOf(character, from)
        return if (found < 0) text.length else found
    }

    /**
     * Index just past the `*/` closing a block comment that opened before [from], or the end
     * of [text]. An unterminated comment consumes the rest of the file rather than throwing:
     * a file whose KDoc is left open is already broken, and reporting it as forty violations
     * is not more useful than reporting none.
     */
    private fun endOfBlockComment(text: String, from: Int): Int {
        val close = text.indexOf("*/", from)
        return if (close < 0) text.length else close + 2
    }

    /** Index just past the `"""` closing a raw string, or the end of [text]. */
    private fun endOfRawString(text: String, from: Int): Int {
        val close = text.indexOf("\"\"\"", from)
        return if (close < 0) text.length else close + 3
    }

    /** Index just past the [terminator] closing a literal opened before [from]. */
    private fun endOfQuoted(text: String, from: Int, terminator: Char): Int {
        var index = from

        while (index < text.length && text[index] != terminator) {
            if (text[index] == '\\') index++
            if (index < text.length && text[index] == '\n') return index
            index++
        }

        return if (index < text.length) index + 1 else text.length
    }

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
