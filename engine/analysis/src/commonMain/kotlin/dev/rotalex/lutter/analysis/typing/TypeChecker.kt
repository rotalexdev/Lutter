package dev.rotalex.lutter.analysis.typing

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCode
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.model.doc.DataModelDecl
import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.expr.UnaryOp
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.function.TypeSig
import dev.rotalex.lutter.schema.modifier.ModifierSpec

/**
 * §10.4's checker: the expected type comes down, the tree's type comes up, and the two meet
 * once at the top.
 *
 * Bidirectional rather than infer-then-compare, because two of §10.4's positions cannot be
 * decided in one direction at all: a `Value.Enum` is an entry name and nothing else, and a
 * list literal carries no element type — §5.4 says both take theirs from the declaration, so
 * an inferring checker would refuse what it should accept.
 *
 * **A `null` result means "already reported".** It is the checker's own state rather than a
 * member of [ExprType], so the vocabulary `:engine:model` shares with both backends carries
 * nothing about analysis. Siblings are still checked after one arm fails, so one fault in an
 * expression yields one finding rather than a cascade of nonsense.
 */
internal class TypeChecker(
    private val schema: SchemaView<ComponentSpec, ModifierSpec, *, *, *>,
    private val dataModels: Map<DataModelId, DataModelDecl>,
) {

    /**
     * Checks [expr] against [expected] where [scope] binds names and [at] says where to report.
     *
     * A null [ExprCheck.type] means the expression was refused and nothing may be built from it.
     */
    public fun check(
        expr: Expr,
        expected: TypeRef?,
        scope: ExprScope,
        at: DiagnosticLocation,
    ): ExprCheck {
        val ctx = Context(scope, at, mutableListOf(), linkedSetOf())
        val type = inspect(expr, expected?.let { ExprType.Of(it) }, ctx)
        return ExprCheck(ctx.found, type, ctx.refs.toSet())
    }

    /** The one place a produced type meets a promised one (§10.4). */
    private fun inspect(expr: Expr, expected: ExprType?, ctx: Context): ExprType? {
        val found = infer(expr, expected, ctx) ?: return null
        if (expected == null || Assignability.accepts(expected, found)) return found
        return refuse(
            ctx, DiagnosticCodes.ExprTypeMismatch,
            "expects '${label(expected)}' but found '${label(found)}' (node '${ctx.at.nodeId}')",
            ctx.base + mapOf("expected" to label(expected), "found" to label(found)),
        )
    }

    private fun infer(expr: Expr, expected: ExprType?, ctx: Context): ExprType? = when (expr) {
        is Expr.Const -> literal(expr.value, expected, ctx)
        is Expr.Ref -> reference(expr, ctx)
        is Expr.Member -> member(expr, ctx)
        is Expr.Call -> call(expr, ctx)
        is Expr.Unary -> unary(expr, ctx)
        is Expr.Binary -> binary(expr, ctx)
        is Expr.If -> conditional(expr, expected, ctx)
        is Expr.ListLiteral -> listLiteral(expr, expected, ctx)
        is Expr.Template -> template(expr, ctx)
    }

    /**
     * A literal's type. Two arms cannot answer alone and take [expected] instead: §5.4 says an
     * enum value is an entry name, and a container carries no element type of its own.
     */
    private fun literal(value: Value, expected: ExprType?, ctx: Context): ExprType? = when (value) {
        is Value.Null -> ExprType.Null
        is Value.Bool -> ExprType.Of(TypeRef.Bool)
        is Value.Int32 -> ExprType.Of(TypeRef.Int32)
        is Value.Int64 -> ExprType.Of(TypeRef.Int64)
        is Value.Float32 -> ExprType.Of(TypeRef.Float32)
        is Value.Float64 -> ExprType.Of(TypeRef.Float64)
        is Value.Str -> ExprType.Of(TypeRef.Str)
        is Value.Url -> ExprType.Of(TypeRef.Url)
        is Value.Color -> ExprType.Of(TypeRef.Color)
        is Value.Dp -> ExprType.Of(TypeRef.Dp)
        is Value.Sp -> ExprType.Of(TypeRef.Sp)
        is Value.Icon -> ExprType.Of(TypeRef.Icon(value.set))
        is Value.Ref -> ExprType.Of(TypeRef.Ref(value.kind))
        is Value.Token -> ExprType.Of(TypeRef.Token(value.kind))
        is Value.Obj -> ExprType.Of(TypeRef.Object(value.typeId))
        is Value.Enum -> fromDeclaration(expected, { it as? TypeRef.Enum }, ctx, "An enum entry")
        is Value.ListOf -> fromDeclaration(expected, { it as? TypeRef.ListOf }, ctx, "A list literal")
        is Value.MapOf -> fromDeclaration(expected, { it as? TypeRef.MapOf }, ctx, "A map literal")
    }

    /** The type such a value borrows from the declaration above it, or a refusal. */
    private fun fromDeclaration(
        expected: ExprType?,
        select: (TypeRef) -> TypeRef?,
        ctx: Context,
        what: String,
    ): ExprType? {
        val payload = unwrap(expected)?.let(select)
        if (payload != null) return ExprType.Of(payload)
        return refuse(
            ctx, DiagnosticCodes.ExprTypeMismatch,
            "$what carries no type of its own; it takes one from the declared property " +
                "(node '${ctx.at.nodeId}')",
            ctx.base,
        )
    }

    /** §17.1's scope resolution: every name the expression reaches is recorded, resolved or not. */
    private fun reference(expr: Expr.Ref, ctx: Context): ExprType? {
        ctx.refs += expr.target
        val target = expr.target
        return when (target) {
            is RefTarget.State -> ctx.scope.state[target.id]?.let { ExprType.Of(it) }
                ?: unresolved(ctx, "state '${target.id}'")

            is RefTarget.Param -> ctx.scope.params[target.name]?.let { ExprType.Of(it) }
                ?: unresolved(ctx, "parameter '${target.name}'")

            // §17.1 puts event args and items in this pass's scope resolution, and neither is
            // bound in a property position: §11.2 is where an event argument is introduced and
            // §10.1 marks an item as wave 2. So both are unresolved rather than given a type.
            is RefTarget.EventArg -> unresolved(ctx, "event argument '${target.name}'")

            is RefTarget.Item -> unresolved(ctx, "item '${target.name}'")
        }
    }

    /**
     * §10.4's member rule: a nullable receiver requires `safe = true`, and the safe form
     * yields a nullable field, so the field is read off the inner type and lifted again.
     */
    private fun member(expr: Expr.Member, ctx: Context): ExprType? {
        val receiver = infer(expr.receiver, null, ctx) ?: return null
        val holder = receiver as? ExprType.Of
            ?: return refuse(
                ctx, DiagnosticCodes.ExprNullableAccess,
                "a null receiver has no field '${expr.name}' (node '${ctx.at.nodeId}')",
                ctx.base + mapOf("field" to expr.name),
            )
        val declared = holder.type
        val nullableReceiver = declared is TypeRef.Nullable
        if (nullableReceiver && !expr.safe) {
            return refuse(
                ctx, DiagnosticCodes.ExprNullableAccess,
                "field '${expr.name}' on '${label(holder)}' needs safe access " +
                    "(node '${ctx.at.nodeId}')",
                ctx.base + mapOf("field" to expr.name, "receiver" to label(holder)),
            )
        }
        val record = (if (declared is TypeRef.Nullable) declared.inner else declared) as? TypeRef.Object
            ?: return mismatch(ctx, "'${expr.name}' has no fields on '${label(holder)}'")
        val model = dataModel(record.id) ?: return unresolved(ctx, "object type '${record.id}'")
        val field = model.fields.firstOrNull { it.name.value == expr.name }
            ?: return unresolved(ctx, "field '${expr.name}' of '${record.id}'")
        return ExprType.Of(if (nullableReceiver) TypeRef.Nullable(field.type) else field.type)
    }

    /**
     * A call, typed from its `FunctionSpec` (§10.3): arguments positionally, element variables
     * bound as they are met, and the result read back through the same bindings.
     */
    private fun call(expr: Expr.Call, ctx: Context): ExprType? {
        // The cast is total, as it is for an enum spec in pass 3: an assembly binding a stub in
        // the functions slot has no spec here, and a call nothing describes is an unknown
        // function rather than a crash. §10.2's seed set arrives with the builtins unit.
        val spec = schema.functions[expr.function] as? FunctionSpec
            ?: return refuse(
                ctx, DiagnosticCodes.ExprUnknownFunction,
                "calls unknown function '${expr.function}' (node '${ctx.at.nodeId}')",
                ctx.base + mapOf("function" to expr.function.value),
            )
        if (expr.args.size != spec.params.size) {
            return mismatch(
                ctx, "'${expr.function}' takes ${spec.params.size} arguments, " +
                    "and this call passes ${expr.args.size}",
            )
        }
        val elements = mutableMapOf<String, TypeRef>()
        for (index in spec.params.indices) {
            val parameter = spec.params[index]
            val found = infer(expr.args[index], null, ctx) ?: return null
            if (fromSig(parameter.type, found, elements)) continue
            return mismatch(
                ctx, "argument ${index + 1} of '${expr.function}' is '${label(found)}' " +
                    "where '${parameter.name}' takes '${labelSig(parameter.type)}'",
            )
        }
        val returns = instantiate(spec.returns, elements, ctx) ?: return null
        return ExprType.Of(returns)
    }

    /** Whether [found] fills [sig], binding §10.3's element variables on the way. */
    private fun fromSig(sig: TypeSig, found: ExprType, elements: MutableMap<String, TypeRef>): Boolean =
        when (sig) {
            is TypeSig.Exact -> Assignability.accepts(ExprType.Of(sig.type), found)
            is TypeSig.Element -> bind(sig.name, found, elements)
            is TypeSig.ListOf -> {
                val list = (found as? ExprType.Of)?.type
                list is TypeRef.ListOf && fromSig(sig.element, ExprType.Of(list.element), elements)
            }
            // "Or null" is read off the *sig*, so the value's own nullable layer is lifted off
            // before the inner sig is tried. Without that a `Nullable` position matched a plain
            // value and refused the very `T?` it names, which left `core.coalesce` untypeable: a
            // fallback has to be the same `T` the nullable carries.
            is TypeSig.Nullable -> found is ExprType.Null || fromSig(sig.inner, lifted(found), elements)
            is TypeSig.OneOf -> sig.options.any { fromSig(it, found, elements) }
        }

    /** One binding per element variable: a second one must agree or the call is wrong. */
    private fun bind(name: String, found: ExprType, elements: MutableMap<String, TypeRef>): Boolean {
        val type = (found as? ExprType.Of)?.type ?: return false
        val bound = elements[name]
        if (bound != null) return bound == type
        elements[name] = type
        return true
    }

    /** The declared result type, with this call's element variables substituted. */
    private fun instantiate(sig: TypeSig, elements: Map<String, TypeRef>, ctx: Context): TypeRef? =
        when (sig) {
            is TypeSig.Exact -> sig.type
            is TypeSig.Element -> elements[sig.name] ?: unbound(ctx, sig.name)
            is TypeSig.ListOf -> instantiate(sig.element, elements, ctx)?.let { TypeRef.ListOf(it) }
            is TypeSig.Nullable -> instantiate(sig.inner, elements, ctx)?.let { TypeRef.Nullable(it) }
            is TypeSig.OneOf -> agreed(sig, elements, ctx)
        }

    /**
     * A union's result type: every option has to resolve to the same one.
     *
     * §10.3 writes no rule for reading a union back, so this refuses rather than picks the
     * first — a call that can be two different types has no single result type to report, and
     * guessing one would hand the emitter a type the document never agreed to.
     */
    private fun agreed(sig: TypeSig.OneOf, elements: Map<String, TypeRef>, ctx: Context): TypeRef? {
        val resolved = sig.options.mapNotNull { instantiate(it, elements, ctx) }
        val first = resolved.firstOrNull() ?: return null
        if (resolved.all { it == first }) return first
        return refuse(
            ctx, DiagnosticCodes.ExprTypeMismatch,
            "returns one of several types and they do not agree (node '${ctx.at.nodeId}')",
            ctx.base,
        )
    }

    private fun unary(expr: Expr.Unary, ctx: Context): ExprType? {
        val operand = infer(expr.operand, null, ctx) ?: return null
        val op = expr.op.name.lowercase()
        return when (expr.op) {
            UnaryOp.Not -> if (operand == BOOL) BOOL else mismatch(ctx, "'$op' needs a bool")
            UnaryOp.Neg -> if (numeric(operand)) operand else mismatch(ctx, "'$op' needs a number")
        }
    }

    /**
     * §10.4's no-implicit-conversion rule, structurally: the two sides of an arithmetic
     * operator must already be the same type, because [Assignability] never widens a number.
     * §10.6 rules locale-dependent ordering out, so text is not an operand here either.
     */
    private fun binary(expr: Expr.Binary, ctx: Context): ExprType? {
        val left = infer(expr.left, null, ctx)
        val right = infer(expr.right, null, ctx)
        if (left == null || right == null) return null
        return when (expr.op) {
            BinaryOp.Add, BinaryOp.Sub, BinaryOp.Mul ->
                if (left == right && (numeric(left) || left == TEXT)) left else operands(ctx, expr, left, right)

            BinaryOp.Eq, BinaryOp.Neq ->
                if (sameOrNull(left, right)) BOOL else operands(ctx, expr, left, right)

            BinaryOp.Lt, BinaryOp.Le, BinaryOp.Gt, BinaryOp.Ge ->
                if (left == right && numeric(left)) BOOL else operands(ctx, expr, left, right)

            BinaryOp.And, BinaryOp.Or ->
                if (left == BOOL && right == BOOL) BOOL else operands(ctx, expr, left, right)
        }
    }

    private fun conditional(expr: Expr.If, expected: ExprType?, ctx: Context): ExprType? {
        // The condition is checked against the expectation rather than against nothing: there
        // is one legal shape and a non-bool condition is wrong whatever the branches say.
        if (inspect(expr.cond, BOOL, ctx) == null) return null
        val then = infer(expr.then, expected, ctx)
        val otherwise = infer(expr.otherwise, expected, ctx)
        if (then == null || otherwise == null) return null
        if (then == otherwise) return then
        return refuse(
            ctx, DiagnosticCodes.ExprTypeMismatch,
            "'if' branches are '${label(then)}' and '${label(otherwise)}' (node '${ctx.at.nodeId}')",
            ctx.base + mapOf("then" to label(then), "otherwise" to label(otherwise)),
        )
    }

    /**
     * A list literal, with the declared element type travelling into every item — which is
     * what makes `listOf()` and a list of enum entries answerable at all.
     */
    private fun listLiteral(expr: Expr.ListLiteral, expected: ExprType?, ctx: Context): ExprType? {
        val declared = elementOf(expected)
        var joined: ExprType? = null
        var clean = true
        for (item in expr.items) {
            val found = inspect(item, declared?.let { ExprType.Of(it) }, ctx)
            if (found == null) {
                clean = false
                continue
            }
            val seen = joined
            if (seen == null) {
                joined = found
            } else if (seen != found) {
                clean = false
                refuse(
                    ctx, DiagnosticCodes.ExprTypeMismatch,
                    "a list literal holds '${label(found)}' where '${label(seen)}' already is " +
                        "(node '${ctx.at.nodeId}')",
                    ctx.base + mapOf("found" to label(found), "expected" to label(seen)),
                )
            }
        }
        if (!clean) return null
        val element = declared ?: (joined as? ExprType.Of)?.type
        if (element == null) {
            return refuse(
                ctx, DiagnosticCodes.ExprTypeMismatch,
                "a list literal takes its element type from the declared property " +
                    "(node '${ctx.at.nodeId}')",
                ctx.base,
            )
        }
        return ExprType.Of(TypeRef.ListOf(element))
    }

    /**
     * §10.4's template rule: `str`, `i32`, `i64` and `bool`, and nothing else.
     *
     * A float is refused by name rather than by set membership alone, because §10.6's hazard
     * is the reason for the restriction and `num.format` is the way out the plan gives; a
     * message listing only the permitted types would send the author looking for the rule.
     */
    private fun template(expr: Expr.Template, ctx: Context): ExprType? {
        var clean = true
        for (part in expr.parts) {
            val found = inspect(part, null, ctx)
            if (found == null) {
                clean = false
                continue
            }
            if (found in TEMPLATE_PARTS) continue
            clean = false
            val declared = (found as? ExprType.Of)?.type
            val message = if (declared != null && floating(declared)) {
                "a template part of type '${label(found)}' has to go through num.format " +
                    "(node '${ctx.at.nodeId}')"
            } else {
                "a template part may be str, i32, i64 or bool, and this one is '${label(found)}' " +
                    "(node '${ctx.at.nodeId}')"
            }
            refuse(ctx, DiagnosticCodes.ExprTypeMismatch, message, ctx.base + mapOf("found" to label(found)))
        }
        return if (clean) TEXT else null
    }

    /** The declared model behind an object type; ids compare as strings, as `ReferenceIndex` does. */
    private fun dataModel(id: TypeId): DataModelDecl? =
        dataModels.entries.firstOrNull { it.key.value == id.value }?.value

    /** The element type a declared `ListOf` promises, so it can travel into each item. */
    private fun elementOf(expected: ExprType?): TypeRef? = (unwrap(expected) as? TypeRef.ListOf)?.element

    /** The promised type with one `Nullable` layer removed, or null when there is none. */
    private fun unwrap(expected: ExprType?): TypeRef? {
        val declared = (expected as? ExprType.Of)?.type ?: return null
        return if (declared is TypeRef.Nullable) declared.inner else declared
    }

    private fun unbound(ctx: Context, name: String): TypeRef? {
        report(
            ctx, DiagnosticCodes.ExprTypeMismatch,
            "returns element '$name', which this call never binds (node '${ctx.at.nodeId}')",
            ctx.base + mapOf("element" to name),
        )
        return null
    }

    /**
     * The one message for every operator whose operands do not fit. Two hints, both from
     * §10.4 and §10.6, because a pair of type names alone does not say which rule broke.
     */
    private fun operands(ctx: Context, expr: Expr.Binary, left: ExprType, right: ExprType): ExprType? {
        val op = expr.op.name.lowercase()
        val hint = when {
            numeric(left) && numeric(right) -> "; there is no implicit numeric conversion, " +
                "so wrap one side in num.toDouble"

            expr.op in ORDERING && left == TEXT ->
                "; text comparison is limited to ==, != and the str.* functions"
            else -> ""
        }
        return refuse(
            ctx, DiagnosticCodes.ExprTypeMismatch,
            "'$op' takes '${label(left)}' and '${label(right)}'$hint (node '${ctx.at.nodeId}')",
            ctx.base + mapOf("op" to op, "left" to label(left), "right" to label(right)),
        )
    }

    /** A type failure that is neither an operator nor a function; the detail is already prose. */
    private fun mismatch(ctx: Context, detail: String): ExprType? =
        refuse(ctx, DiagnosticCodes.ExprTypeMismatch, "$detail (node '${ctx.at.nodeId}')", ctx.base)

    private fun unresolved(ctx: Context, what: String): ExprType? = refuse(
        ctx, DiagnosticCodes.ExprUnresolvedRef,
        "reads $what, which is not in scope (node '${ctx.at.nodeId}')",
        ctx.base + mapOf("ref" to what),
    )

    /** Records the finding and yields the null a refused expression answers with. */
    private fun refuse(
        ctx: Context,
        code: DiagnosticCode,
        message: String,
        args: Map<String, String>,
    ): ExprType? {
        report(ctx, code, message, args)
        return null
    }

    private fun report(ctx: Context, code: DiagnosticCode, message: String, args: Map<String, String>) {
        ctx.found += Diagnostic(Severity.Error, code, ctx.at, message, args)
    }
}

/** The state and params an expression at one node may name, each with its declared type. */
internal class ExprScope(
    public val state: Map<StateId, TypeRef>,
    public val params: Map<ParamName, TypeRef>,
)

/** One expression's outcome: what it produced, what it names, and everything it reported. */
internal class ExprCheck(
    public val diagnostics: List<Diagnostic>,
    public val type: ExprType?,
    public val refs: Set<RefTarget>,
)

/**
 * One check in progress. [base] is the `node`/`property` pair §10.4 requires every one of
 * these diagnostics to carry, built once because every rule below needs it.
 */
private class Context(
    val scope: ExprScope,
    val at: DiagnosticLocation,
    val found: MutableList<Diagnostic>,
    val refs: MutableSet<RefTarget>,
) {
    val base: Map<String, String> = mapOf(
        "node" to at.nodeId?.value.orEmpty(),
        "property" to at.property?.value.orEmpty(),
    )
}

/**
 * §10.4's rendering of a type: the tag §9.2 puts on the wire, so a diagnostic quotes the word
 * the document used rather than a Kotlin class name the author has never seen.
 */
private fun label(type: ExprType): String {
    val declared = (type as? ExprType.Of)?.type ?: return "null"
    return declared.serialTag
}

/** §10.3's rendering of a signature type, for the argument that failed to fill it. */
private fun labelSig(sig: TypeSig): String = when (sig) {
    is TypeSig.Exact -> sig.type.serialTag
    is TypeSig.Element -> sig.name
    is TypeSig.ListOf -> "list of ${labelSig(sig.element)}"
    is TypeSig.Nullable -> "${labelSig(sig.inner)} or null"
    is TypeSig.OneOf -> sig.options.joinToString(" or ") { labelSig(it) }
}

/** [found] with one `Nullable` layer removed, so an "or null" sig meets a bare inner type. */
private fun lifted(found: ExprType): ExprType {
    val declared = (found as? ExprType.Of)?.type as? TypeRef.Nullable ?: return found
    return ExprType.Of(declared.inner)
}

private val BOOL: ExprType = ExprType.Of(TypeRef.Bool)
private val TEXT: ExprType = ExprType.Of(TypeRef.Str)

/** §10.6's ordering operators: locale-dependent comparison, which `str` may not take part in. */
private val ORDERING: Set<BinaryOp> = setOf(BinaryOp.Lt, BinaryOp.Le, BinaryOp.Gt, BinaryOp.Ge)

/** §10.4's permitted template parts, exactly: the four types a `Template` may interpolate. */
private val TEMPLATE_PARTS: Set<ExprType> = setOf(
    TEXT,
    ExprType.Of(TypeRef.Int32),
    ExprType.Of(TypeRef.Int64),
    BOOL,
)

/** §10.4's arithmetic types. `dp` and `sp` are quantities rather than numbers to add. */
private fun numeric(type: ExprType): Boolean {
    val declared = (type as? ExprType.Of)?.type ?: return false
    return when (declared) {
        TypeRef.Int32, TypeRef.Int64, TypeRef.Float32, TypeRef.Float64 -> true
        else -> false
    }
}

/** §10.4:856's "floating types", which are the ones `num.format` exists to print. */
private fun floating(declared: TypeRef): Boolean = declared == TypeRef.Float32 || declared == TypeRef.Float64

/**
 * §10.6 makes functions total and index errors return `Null`, so a null is a value a
 * comparison can legally hold; comparing two unlike types still is not.
 */
private fun sameOrNull(left: ExprType, right: ExprType): Boolean =
    left == right || left is ExprType.Null || right is ExprType.Null
