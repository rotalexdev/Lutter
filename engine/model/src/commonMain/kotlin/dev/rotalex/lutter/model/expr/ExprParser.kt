package dev.rotalex.lutter.model.expr

import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value

/**
 * §10.8's text view, read back: the exact inverse of [ExprPrinter].
 *
 * The contract is data-class equality — `parse(print(ast)) == ast` — and the grammar is the
 * printer's KDoc, so this file only records the reading rules the text form implies:
 *
 * - There is no precedence table. The printer wraps every compound operand in parens, so an
 *   operand in the text is always a primary or a parenthesized group, and a flat left-to-right
 *   fold over the operator symbols reads the tree back exactly.
 * - A `-` followed by a digit is a negative literal; a `-` followed by anything else is
 *   negation. The printer keeps the two spellings apart the same way (`-(5)` vs `-5`), so the
 *   reading is total.
 * - A bare name at the start of an expression is a function call chain and must reach a `(`:
 *   `list.size(x)`. A reference always spells its binding kind (`state.`, `param.`,
 *   `event.`, `item.`), because which variant a name resolves to is a question about scope,
 *   and a view that guessed would not round-trip.
 * - A string with no `${…}` is a `Const`; a string with any is a `Template` whose literal
 *   parts become `Const` parts. The uniform `${…}`-every-part spelling is what keeps an
 *   all-literal template from collapsing into a single literal on the way back.
 * - The grammar reads what the printer writes and a little lenient more (a compound operand
 *   without its canonical parens, for a person typing by hand); it refuses anything else with
 *   the position named, and it refuses input the expression does not fully consume.
 */
public object ExprParser {

    /** The expression [text] spells, exactly. @throws IllegalArgumentException when it cannot. */
    public fun parse(text: String): Expr {
        val parser = Parser(text)
        val expr = parser.parseExpression()
        parser.skipWhitespace()
        if (!parser.atEnd()) {
            parser.fail("trailing input")
        }
        return expr
    }
}

/** A position-based recursive descent over the canonical text; see [ExprParser]'s KDoc. */
private class Parser(private val source: String) {

    var pos: Int = 0
        private set

    fun atEnd(): Boolean = pos >= source.length

    fun skipWhitespace() {
        while (pos < source.length && source[pos].isWhitespace()) pos++
    }

    fun fail(message: String): Nothing =
        throw IllegalArgumentException("$message (at position $pos in '$source')")

    private fun peek(): Char? = source.getOrNull(pos)

    private fun peekAt(offset: Int): Char? = source.getOrNull(pos + offset)

    private fun expect(c: Char) {
        if (peek() != c) fail("expected '$c'")
        pos++
    }

    private fun expectWord(word: String) {
        skipWhitespace()
        if (peekAt(word.length)?.let { it.isLetterOrDigit() || it == '_' } == true) {
            fail("expected '$word'")
        }
        if (!source.startsWith(word, pos)) fail("expected '$word'")
        pos += word.length
    }

    fun parseExpression(): Expr {
        skipWhitespace()
        if (peekWordIs("if")) return parseIf()
        val left = parseOperand()
        return parseBinaryTail(left)
    }

    private fun parseIf(): Expr {
        expectWord("if")
        expectAfterWhitespace('(')
        val cond = parseExpression()
        expectAfterWhitespace(')')
        expectWord("then")
        val then = parseExpression()
        expectWord("else")
        val otherwise = parseExpression()
        return Expr.If(cond, then, otherwise)
    }

    /** One primary plus its member postfixes, then the flat operator fold. */
    private fun parseBinaryTail(left: Expr): Expr {
        var result = left
        while (true) {
            skipWhitespace()
            val op = readBinaryOp() ?: return result
            val right = parseOperand()
            result = Expr.Binary(op, result, right)
        }
    }

    private fun readBinaryOp(): BinaryOp? {
        if (peek() == null) return null
        for ((symbol, op) in twoCharSymbols) {
            if (source.startsWith(symbol, pos)) {
                pos += symbol.length
                return op
            }
        }
        val c = peek()!!
        val single = when (c) {
            '+' -> BinaryOp.Add
            '-' -> BinaryOp.Sub
            '*' -> BinaryOp.Mul
            '<' -> BinaryOp.Lt
            '>' -> BinaryOp.Gt
            else -> return null
        }
        pos++
        return single
    }

    private fun parseOperand(): Expr {
        skipWhitespace()
        val c = peek() ?: fail("unexpected end of expression")
        val primary = when {
            c == '(' -> {
                pos++
                val expr = parseExpression()
                skipWhitespace()
                expect(')')
                expr
            }
            c == '[' -> parseList()
            c == '!' -> {
                pos++
                Expr.Unary(UnaryOp.Not, parseOperand())
            }
            c == '-' -> {
                pos++
                skipWhitespace()
                if (peek()?.isDigit() == true) {
                    Expr.Const(readNegativeNumber())
                } else {
                    Expr.Unary(UnaryOp.Neg, parseOperand())
                }
            }
            c == '"' -> parseString()
            c.isDigit() -> Expr.Const(readNumber())
            c.isLetter() || c == '_' -> parseWord()
            else -> fail("unexpected character '$c'")
        }
        return parsePostfix(primary)
    }

    private fun parseList(): Expr {
        expect('[')
        val items = mutableListOf<Expr>()
        skipWhitespace()
        if (peek() != ']') {
            while (true) {
                items += parseExpression()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> break
                    else -> fail("expected ',' or ']' in a list literal")
                }
            }
        }
        expect(']')
        return Expr.ListLiteral(items)
    }

    private fun parseWord(): Expr {
        val start = pos
        while (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) pos++
        val word = source.substring(start, pos)
        return when (word) {
            "true" -> Expr.Const(Value.Bool(true))
            "false" -> Expr.Const(Value.Bool(false))
            "null" -> Expr.Const(Value.Null)
            "state", "param", "event", "item" -> parseRef(word)
            "if" -> fail("an 'if' here is not part of the canonical form")
            "then", "else" -> fail("a keyword is not an expression")
            else -> parseCall(word)
        }
    }

    private fun parseRef(kind: String): Expr {
        skipWhitespace()
        expect('.')
        val name = readIdentifier()
        val target = when (kind) {
            "state" -> RefTarget.State(StateId(name))
            "param" -> RefTarget.Param(ParamName(name))
            "event" -> RefTarget.EventArg(name)
            else -> RefTarget.Item(name)
        }
        return Expr.Ref(target)
    }

    /**
     * A name chain that must reach a `(`: the only bare names in the grammar are
     * fully-qualified calls, and a name that stops short is a typo this parser names rather
     * than guesses about.
     */
    private fun parseCall(first: String): Expr {
        val segments = mutableListOf(first)
        while (peek() == '.') {
            pos++
            segments += readIdentifier()
        }
        skipWhitespace()
        if (peek() != '(') {
            fail("a bare name is not an expression: calls spell ns.name(...)")
        }
        pos++
        val args = mutableListOf<Expr>()
        skipWhitespace()
        if (peek() == ')') {
            pos++
            return Expr.Call(FunctionId(segments.joinToString(".")), args)
        }
        while (true) {
            args += parseExpression()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ')' -> {
                    pos++
                    return Expr.Call(FunctionId(segments.joinToString(".")), args)
                }
                else -> fail("expected ',' or ')' in a call")
            }
        }
    }

    /** Postfix member access, canonical or not: `.name` and `?.name`. */
    private fun parsePostfix(expr: Expr): Expr {
        var result = expr
        while (true) {
            if (peek() == '.') {
                pos++
                result = Expr.Member(result, readIdentifier(), safe = false)
            } else if (peek() == '?' && peekAt(1) == '.') {
                pos += 2
                result = Expr.Member(result, readIdentifier(), safe = true)
            } else {
                return result
            }
        }
    }

    private fun parseString(): Expr {
        expect('"')
        val literal = StringBuilder()
        val parts = mutableListOf<Expr>()
        var interpolated = false
        while (true) {
            val c = peek() ?: fail("unterminated string")
            when {
                c == '\\' -> {
                    pos++
                    val escaped = peek() ?: fail("unterminated escape")
                    literal.append(
                        when (escaped) {
                            '\\' -> '\\'
                            '"' -> '"'
                            '$' -> '$'
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> fail("unknown escape '\\$escaped'")
                        },
                    )
                    pos++
                }
                c == '"' -> {
                    pos++
                    break
                }
                c == '$' && peekAt(1) == '{' -> {
                    interpolated = true
                    pos += 2
                    if (literal.isNotEmpty()) {
                        parts += Expr.Const(Value.Str(literal.toString()))
                        literal.clear()
                    }
                    parts += parseExpression()
                    skipWhitespace()
                    expect('}')
                }
                else -> {
                    literal.append(c)
                    pos++
                }
            }
        }
        if (!interpolated) {
            return Expr.Const(Value.Str(literal.toString()))
        }
        if (literal.isNotEmpty()) {
            parts += Expr.Const(Value.Str(literal.toString()))
        }
        return Expr.Template(parts)
    }

    private fun readIdentifier(): String {
        val c = peek() ?: fail("expected an identifier")
        if (!(c.isLetter() || c == '_')) fail("expected an identifier")
        val start = pos
        while (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) pos++
        return source.substring(start, pos)
    }

    private fun peekWordIs(word: String): Boolean =
        source.startsWith(word, pos) &&
            source.getOrNull(pos + word.length)?.let { it.isLetterOrDigit() || it == '_' } != true

    private fun expectAfterWhitespace(c: Char) {
        skipWhitespace()
        expect(c)
    }

    // ---------------------------------------------------------------------------------------
    // Numbers
    // ---------------------------------------------------------------------------------------

    /**
     * A non-negative literal at [pos]: `42`, `42L`, `1.5`, `1.5f`. The digits decide the
     * variant the same way the printer's suffixes do, and anything outside a variant's range
     * is refused rather than silently widened.
     */
    private fun readNumber(): Value = readNumberText(positive = true)

    private fun readNegativeNumber(): Value = readNumberText(positive = false)

    private fun readNumberText(positive: Boolean): Value {
        val start = pos
        while (peek()?.isDigit() == true) pos++
        var fractional = false
        if (peek() == '.') {
            fractional = true
            pos++
            while (peek()?.isDigit() == true) pos++
        }
        val digits = source.substring(start, pos)
        val suffix = when (peek()) {
            'f' -> {
                pos++
                "f"
            }
            'L' -> {
                pos++
                "L"
            }
            else -> ""
        }
        val signedText = if (positive) digits else "-$digits"
        return when {
            suffix == "f" -> {
                require(fractional) { "a Float32 literal carries a decimal point (at position $start in '$source')" }
                Value.Float32(signedText.toFloat())
            }
            suffix == "L" -> {
                require(!fractional) { "an Int64 literal carries no decimal point (at position $start in '$source')" }
                Value.Int64(signedText.toLongOrNull() ?: failNumber(start, digits, "an Int64"))
            }
            fractional -> Value.Float64(signedText.toDouble())
            else -> {
                val long = signedText.toLongOrNull()
                    ?: failNumber(start, digits, "an Int32")
                if (long in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    Value.Int32(long.toInt())
                } else {
                    failNumber(start, digits, "an Int32 (spell the value ${long}L for an Int64)")
                }
            }
        }
    }

    private fun failNumber(start: Int, digits: String, kind: String): Nothing =
        throw IllegalArgumentException(
            "'$digits' is outside the range of $kind (at position $start in '$source')",
        )

    private val twoCharSymbols: List<Pair<String, BinaryOp>> = listOf(
        "==" to BinaryOp.Eq,
        "!=" to BinaryOp.Neq,
        "<=" to BinaryOp.Le,
        ">=" to BinaryOp.Ge,
        "&&" to BinaryOp.And,
        "||" to BinaryOp.Or,
    )
}
