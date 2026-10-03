package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * IR to text: 4-space indent, LF, one blank line between declarations, trailing newline.
 *
 * Imports compute from the symbols the tree actually uses; conflicts alias deterministically
 * while declared names always win, and aliased usages render with their alias. Same input
 * prints byte-identical output every run.
 */
public class KtPrinter(
    private val policy: ImportPolicy = ImportPolicy(),
    private val formatting: FormattingOptions = FormattingOptions(),
) {
    /** Full source text of [file], ending in exactly one newline. */
    public fun print(file: KtFile): String = buildString {
        if (file.header != null) appendLine(file.header)
        appendLine("package " + file.pkg)
        appendLine()
        val imports: ImportBlock = renderImports(file)
        if (imports.lines.isNotEmpty()) {
            for (line in imports.lines) appendLine(line)
            appendLine()
        }
        for ((index, declaration) in file.declarations.withIndex()) {
            if (index > 0) appendLine()
            append(renderDeclaration(declaration, 0, imports.aliases))
            appendLine()
        }
    }

    // Declared names win: any import sharing one aliases, so generated code still compiles.
    private fun renderImports(file: KtFile): ImportBlock {
        val declared: Set<String> = file.declarations.mapNotNull { declaredNameOf(it) }.toSet()
        val seen: MutableMap<String, MutableList<KotlinSymbol>> = LinkedHashMap()
        collectSymbols(file, seen)
        val plain: MutableList<String> = mutableListOf()
        val aliased: MutableList<String> = mutableListOf()
        val taken: MutableSet<String> = declared.toMutableSet()
        val aliases: MutableMap<String, String> = mutableMapOf()
        val names: List<String> = seen.keys.sorted()
        for (name in names) {
            val symbols: List<KotlinSymbol> = seen.getValue(name).distinct().sortedBy { it.fqn() }
            if (symbols.size == 1 && name !in declared) {
                plain += "import " + symbols.single().fqn()
                taken += name
            } else if (name !in declared) {
                plain += "import " + symbols.first().fqn()
                taken += name
                for (symbol in symbols.drop(1)) {
                    val alias: String = aliasOf(symbol, taken)
                    aliases[symbol.fqn()] = alias
                    aliased += "import " + symbol.fqn() + " as " + alias
                }
            } else {
                for (symbol in symbols) {
                    val alias: String = aliasOf(symbol, taken)
                    aliases[symbol.fqn()] = alias
                    aliased += "import " + symbol.fqn() + " as " + alias
                }
            }
        }
        return ImportBlock(plain.sorted() + aliased.sorted(), aliases)
    }

    /**
     * The name this declaration introduces at file scope.
     *
     * File scope only: a class member cannot shadow a top-level import, so naming it a
     * declared name would alias an import nothing collides with.
     */
    private fun declaredNameOf(declaration: KtDeclaration): String? = when (declaration) {
        is KtDeclaration.Function -> declaration.name
        is KtDeclaration.Property -> declaration.name
        is KtDeclaration.Class -> declaration.name
    }

    // Alias from the owning package's last segment: `material.Text` becomes `MaterialText`.
    private fun aliasOf(symbol: KotlinSymbol, taken: MutableSet<String>): String {
        val stem: String = symbol.packageName.substringAfterLast('.')
            .replaceFirstChar { it.uppercaseChar() } + symbol.name
        var alias: String = stem
        var suffix: Int = 2
        while (alias in taken) {
            alias = stem + suffix.toString()
            suffix += 1
        }
        taken += alias
        return alias
    }

    private fun renderDeclaration(
        declaration: KtDeclaration,
        indent: Int,
        aliases: Map<String, String>,
    ): String = when (declaration) {
        is KtDeclaration.Function -> renderFunction(declaration, indent, aliases)
        is KtDeclaration.Property -> renderProperty(declaration, indent, aliases)
        is KtDeclaration.Class -> renderClass(declaration, indent, aliases)
    }

    /** One `@` line per annotation, sorted by name so two runs over one tree agree. */
    private fun renderAnnotations(
        annotations: List<KotlinSymbol>,
        indent: Int,
        aliases: Map<String, String>,
    ): String = annotations.sortedBy { it.name }.joinToString("") {
        indentOf(indent) + "@" + (aliases[it.fqn()] ?: it.name) + "\n"
    }

    // Explicit `public`: the generated module builds with explicitApi, and the IR carries no
    // visibility to write. The property and class branches print it for the same reason.
    private fun renderFunction(
        function: KtDeclaration.Function,
        indent: Int,
        aliases: Map<String, String>,
    ): String = buildString {
        append(renderAnnotations(function.annotations, indent, aliases))
        append(indentOf(indent) + "public fun " + function.name + "(")
        append(function.params.joinToString(", ") { renderParam(it, aliases) })
        append(") {")
        if (function.body.isEmpty()) {
            append("}")
        } else {
            append("\n")
            for (stmt in function.body) append(renderStmt(stmt, indent + 1, aliases) + "\n")
            append(indentOf(indent) + "}")
        }
    }

    /**
     * `val`/`var`, an optional type, then `=`, `by` or `get()`.
     *
     * The three sources of a value are separate fields because Kotlin spells them differently
     * and accepts only one: a node carrying an initializer and a delegate is refused.
     */
    private fun renderProperty(
        property: KtDeclaration.Property,
        indent: Int,
        aliases: Map<String, String>,
    ): String {
        if (property.initializer != null && property.delegate != null) {
            throw CodegenBug(
                "Property '" + property.name + "' has an initializer and a delegate; Kotlin allows one",
            )
        }
        val keyword: String = if (property.mutable) "public var " else "public val "
        val declared: String = property.type?.let { ": " + renderExpr(it, indent, aliases) } ?: ""
        val assigned: String = property.initializer?.let { " = " + renderExpr(it, indent, aliases) }
            ?: property.delegate?.let { " by " + renderExpr(it, indent, aliases) }
            ?: ""
        val getter: String = property.getter?.let {
            "\n" + indentOf(indent + 1) + "get() = " + renderExpr(it, indent + 1, aliases)
        } ?: ""
        return renderAnnotations(property.annotations, indent, aliases) +
            indentOf(indent) + keyword + property.name + declared + assigned + getter
    }

    /** Braces on the head line when empty, one blank line between members otherwise. */
    private fun renderClass(
        clazz: KtDeclaration.Class,
        indent: Int,
        aliases: Map<String, String>,
    ): String = buildString {
        append(renderAnnotations(clazz.annotations, indent, aliases))
        append(indentOf(indent) + "public class " + clazz.name)
        if (clazz.members.isEmpty()) {
            append(" {}")
        } else {
            append(" {\n")
            for ((index, member) in clazz.members.withIndex()) {
                if (index > 0) append("\n")
                append(renderDeclaration(member, indent + 1, aliases) + "\n")
            }
            append(indentOf(indent) + "}")
        }
    }

    private fun renderParam(param: KtParam, aliases: Map<String, String>): String {
        val type: String = aliases[param.type.fqn()] ?: param.type.name
        val default: KtExpr? = param.default
        if (default == null) return param.name + ": " + type
        return param.name + ": " + type + " = " + renderExpr(default, 0, aliases)
    }

    private fun renderStmt(stmt: KtStmt, indent: Int, aliases: Map<String, String>): String =
        when (stmt) {
            is KtStmt.Expr -> indentOf(indent) + renderExpr(stmt.expr, indent, aliases)
            is KtStmt.LocalProperty -> indentOf(indent) + renderLocalProperty(stmt, indent, aliases)
            is KtStmt.Assign -> indentOf(indent) + renderExpr(stmt.target, indent, aliases) +
                " = " + renderExpr(stmt.value, indent, aliases)
        }

    // No `public` here: a local is not a declaration, and explicitApi governs declarations.
    private fun renderLocalProperty(
        stmt: KtStmt.LocalProperty,
        indent: Int,
        aliases: Map<String, String>,
    ): String {
        if (stmt.initializer != null && stmt.delegate != null) {
            throw CodegenBug(
                "Local '" + stmt.name + "' has an initializer and a delegate; Kotlin allows one",
            )
        }
        val keyword: String = if (stmt.mutable) "var " else "val "
        val declared: String = stmt.type?.let { ": " + renderExpr(it, indent, aliases) } ?: ""
        val assigned: String = stmt.initializer?.let { " = " + renderExpr(it, indent, aliases) }
            ?: stmt.delegate?.let { " by " + renderExpr(it, indent, aliases) }
            ?: ""
        return keyword + stmt.name + declared + assigned
    }

    // First line unindented (the caller positions it); continuations carry absolute indent.
    private fun renderExpr(expr: KtExpr, indent: Int, aliases: Map<String, String>): String =
        when (expr) {
            is KtExpr.Literal -> expr.text
            is KtExpr.Name -> expr.name
            is KtExpr.Snippet -> expr.text
            is KtExpr.Lambda -> renderLambda(expr, indent, aliases).trimStart()
            is KtExpr.Member -> renderExpr(expr.receiver, indent, aliases) + "." + expr.name
            is KtExpr.Ref -> refOf(expr.symbol, aliases)
            is KtExpr.Call -> renderCall(expr, indent, aliases)
            is KtExpr.Chain -> renderChain(expr, indent, aliases)
            is KtCall -> renderKtCall(expr, indent, aliases)
        }

    private fun refOf(ref: KtSymbolRef, aliases: Map<String, String>): String {
        val name: String = aliases[ref.symbol.fqn()] ?: ref.symbol.name
        val member: String? = ref.member
        if (member == null) return name
        return name + "." + member
    }

    private fun renderCall(call: KtExpr.Call, indent: Int, aliases: Map<String, String>): String {
        val callee: String = renderExpr(call.callee, indent, aliases)
        val trailing: KtExpr.Lambda? = call.trailing
        if (call.args.isEmpty() && trailing == null) return callee + "()"
        if (call.args.isEmpty()) return callee + renderLambda(checkNotNull(trailing), indent, aliases)
        val single: KtArg = call.args.singleOrNull() ?: return expandedCall(callee, call, indent, aliases)
        if (single.name == null && trailing == null) {
            val value: String = renderExpr(single.value, indent, aliases)
            if (!value.contains('\n') && value.length <= formatting.shortArgWidth) {
                return callee + "(" + value + ")"
            }
        }
        return expandedCall(callee, call, indent, aliases)
    }

    private fun expandedCall(
        callee: String,
        call: KtExpr.Call,
        indent: Int,
        aliases: Map<String, String>,
    ): String = buildString {
        append(callee + "(")
        if (call.args.isEmpty()) {
            append(")")
        } else {
            append("\n")
            for (arg in call.args) {
                // Nested values render at the arg's own indent; only the first line is
                // positioned here, continuations already carry absolute indent.
                val head: String = if (arg.name == null) "" else arg.name + " = "
                val lines: List<String> = (head + renderExpr(arg.value, indent + 1, aliases)).split('\n')
                val out: MutableList<String> = mutableListOf(indentOf(indent + 1) + lines.first())
                for (extra in lines.drop(1)) out += extra
                out[out.size - 1] = out.last() + ","
                for (line in out) append(line + "\n")
            }
            append(indentOf(indent) + ")")
        }
        val trailing: KtExpr.Lambda? = call.trailing
        if (trailing != null) append(renderLambda(trailing, indent, aliases))
    }

    private fun renderLambda(
        lambda: KtExpr.Lambda,
        indent: Int,
        aliases: Map<String, String>,
    ): String = buildString {
        if (lambda.body.isEmpty()) {
            append(" {}")
        } else {
            append(" {\n")
            for (stmt in lambda.body) append(renderStmt(stmt, indent + 1, aliases) + "\n")
            append(indentOf(indent) + "}")
        }
    }

    // Receiver first, then one entry per line; a chain call wraps its own arguments, and a
    // case-selected entry is spec text its pattern already parenthesised.
    private fun renderChain(chain: KtExpr.Chain, indent: Int, aliases: Map<String, String>): String =
        buildString {
            append(renderExpr(chain.receiver, indent, aliases))
            for (call in chain.calls) {
                append("\n" + indentOf(indent + 1) + "." + renderExpr(call, indent + 1, aliases))
            }
        }

    // A chain call: its name, then its arguments inline or expanded, as a call expands.
    private fun renderKtCall(call: KtCall, indent: Int, aliases: Map<String, String>): String {
        val name: String = aliases[call.function.fqn()] ?: call.function.name
        val inline: String = call.args.joinToString(", ") { renderChainArg(it, indent, aliases) }
        if (!inline.contains('\n') && inline.length <= formatting.lineWidth) {
            return name + "(" + inline + ")"
        }
        return buildString {
            append(name + "(\n")
            for (arg in call.args) {
                val head: String = if (arg.name == null) "" else arg.name + " = "
                val lines: List<String> =
                    (head + renderExpr(arg.value, indent + 1, aliases)).split('\n')
                val out: MutableList<String> =
                    mutableListOf(indentOf(indent + 1) + lines.first())
                for (extra in lines.drop(1)) out += extra
                out[out.size - 1] = out.last() + ","
                for (line in out) append(line + "\n")
            }
            append(indentOf(indent) + ")")
        }
    }

    private fun renderChainArg(arg: KtArg, indent: Int, aliases: Map<String, String>): String {
        val rendered: String = renderExpr(arg.value, indent, aliases)
        if (arg.name == null) return rendered
        return arg.name + " = " + rendered
    }

    private fun indentOf(indent: Int): String = buildString {
        repeat(indent * formatting.indentWidth) { append(' ') }
    }

    // Members, not locals: exprs and stmt recurse into each other, and local
    // functions cannot forward-reference. State travels in parameters.
    private fun recordSymbol(
        symbol: KotlinSymbol,
        filePkg: String,
        seen: MutableMap<String, MutableList<KotlinSymbol>>,
    ): Unit {
        if (symbol.packageName in policy.defaultPackages) return
        if (symbol.packageName == filePkg) return
        seen.getOrPut(symbol.name) { mutableListOf() } += symbol
    }

    private fun collectExprSymbols(
        expr: KtExpr,
        filePkg: String,
        seen: MutableMap<String, MutableList<KotlinSymbol>>,
    ): Unit {
        if (expr is KtExpr.Ref) recordSymbol(expr.symbol.symbol, filePkg, seen)
        if (expr is KtExpr.Literal) expr.imports.forEach { recordSymbol(it, filePkg, seen) }
        if (expr is KtExpr.Snippet) expr.symbols.forEach { recordSymbol(it, filePkg, seen) }
        if (expr is KtCall) {
            if (expr.imported) recordSymbol(expr.function, filePkg, seen)
            expr.args.forEach { collectExprSymbols(it.value, filePkg, seen) }
        }
        if (expr is KtExpr.Member) collectExprSymbols(expr.receiver, filePkg, seen)
        if (expr is KtExpr.Lambda) expr.body.forEach { collectStmtSymbols(it, filePkg, seen) }
        if (expr is KtExpr.Call) {
            collectExprSymbols(expr.callee, filePkg, seen)
            expr.args.forEach { collectExprSymbols(it.value, filePkg, seen) }
            expr.trailing?.body?.forEach { collectStmtSymbols(it, filePkg, seen) }
        }
        if (expr is KtExpr.Chain) {
            collectExprSymbols(expr.receiver, filePkg, seen)
            for (call in expr.calls) collectExprSymbols(call, filePkg, seen)
        }
    }

    private fun collectStmtSymbols(
        stmt: KtStmt,
        filePkg: String,
        seen: MutableMap<String, MutableList<KotlinSymbol>>,
    ): Unit = when (stmt) {
        is KtStmt.Expr -> collectExprSymbols(stmt.expr, filePkg, seen)
        is KtStmt.LocalProperty -> {
            collectOptional(stmt.type, filePkg, seen)
            collectOptional(stmt.initializer, filePkg, seen)
            collectOptional(stmt.delegate, filePkg, seen)
        }
        is KtStmt.Assign -> {
            collectExprSymbols(stmt.target, filePkg, seen)
            collectExprSymbols(stmt.value, filePkg, seen)
        }
    }

    private fun collectOptional(
        expr: KtExpr?,
        filePkg: String,
        seen: MutableMap<String, MutableList<KotlinSymbol>>,
    ): Unit = expr?.let { collectExprSymbols(it, filePkg, seen) } ?: Unit

    private fun collectSymbols(file: KtFile, seen: MutableMap<String, MutableList<KotlinSymbol>>): Unit {
        for (declaration in file.declarations) collectDeclarationSymbols(declaration, file.pkg, seen)
    }

    // Every path that can hold a symbol, class members included: a type reference or an
    // initial value missed here is an import the emitted file needs and never gets.
    private fun collectDeclarationSymbols(
        declaration: KtDeclaration,
        filePkg: String,
        seen: MutableMap<String, MutableList<KotlinSymbol>>,
    ): Unit {
        when (declaration) {
            is KtDeclaration.Function -> {
                declaration.annotations.forEach { recordSymbol(it, filePkg, seen) }
                for (param in declaration.params) {
                    recordSymbol(param.type, filePkg, seen)
                    param.default?.let { collectExprSymbols(it, filePkg, seen) }
                }
                declaration.body.forEach { collectStmtSymbols(it, filePkg, seen) }
            }
            is KtDeclaration.Property -> {
                declaration.annotations.forEach { recordSymbol(it, filePkg, seen) }
                collectOptional(declaration.type, filePkg, seen)
                collectOptional(declaration.initializer, filePkg, seen)
                collectOptional(declaration.delegate, filePkg, seen)
                collectOptional(declaration.getter, filePkg, seen)
            }
            is KtDeclaration.Class -> {
                declaration.annotations.forEach { recordSymbol(it, filePkg, seen) }
                declaration.members.forEach { collectDeclarationSymbols(it, filePkg, seen) }
            }
        }
    }

    private fun KotlinSymbol.fqn(): String = packageName + "." + name

    // Import lines plus the alias each conflicting reference renders with.
    private class ImportBlock(val lines: List<String>, val aliases: Map<String, String>)
}
