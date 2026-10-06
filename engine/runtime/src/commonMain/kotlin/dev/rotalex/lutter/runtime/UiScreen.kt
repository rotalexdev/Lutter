package dev.rotalex.lutter.runtime

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.analysis.resolved.ResolvedProp
import dev.rotalex.lutter.analysis.resolved.ResolvedTheme
import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.interpreter.constantOrNull
import dev.rotalex.lutter.interpreter.eval.Evaluator
import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.kind.ValueKinds
import kotlin.coroutines.cancellation.CancellationException

/**
 * The screen: resolves [page], then renders its root through the registry.
 *
 * Unknown pages report a diagnostic and render nothing. [args] binds the page's params, which
 * §12.1 makes a scope apart from the page's own state: a `RefTarget.Param` resolves here and a
 * `RefTarget.State` does not, so the two never shadow one another.
 *
 * The page's store and the read scope are built outside the `Box` so every renderer in the tree
 * shares them, and so `remember` is keyed on the page rather than on the root node's `key`.
 */
@Composable
public fun UiScreen(
    runtime: UiRuntime,
    document: ResolvedDocument,
    page: PageId,
    environment: RuntimeEnvironment,
    args: Map<ParamName, Value> = emptyMap(),
    modifier: Modifier = Modifier,
): Unit {
    val resolved = document.pages[page]
    if (resolved == null) {
        environment.diagnostics(RuntimeDiagnostic("Unknown page '$page'", null))
        return
    }
    val pageState = rememberPageStateStore(resolved)
    // App state first and the page's last: the owner-last order pass 5 resolves a `Ref` in.
    val screen = ScreenEvalScope(
        document.appState + resolved.state,
        pageState,
        environment.appState,
        args,
        runtime.evaluator,
    )
    val expressions = ExpressionSource(runtime.evaluator, screen)
    Box(modifier = modifier) {
        val scope = DefaultRenderScope(
            environment,
            DefaultThemeHandle(document.theme),
            runtime,
            null,
            expressions,
        )
        key(resolved.root.id) {
            environment.hooks.Decorate(resolved.root) {
                RenderNode(runtime, resolved.root, scope)
            }
        }
    }
}

/**
 * One node through the registry. A miss renders nothing: coverage already failed
 * at construction for schema types, so a miss here is an overlay type with no
 * renderer, and blank is kinder than a crash in an editor.
 *
 * No recovery around the call itself: Compose forbids try/catch around composable
 * invocations, and a renderer that throws on resolved input is a contract violation
 * analysis already had the chance to catch — not a data condition to report.
 */
@Composable
public fun RenderNode(runtime: UiRuntime, node: ResolvedNode, scope: RenderScope): Unit {
    val renderer = runtime.renderers[node.type] ?: return
    renderer.Render(node, scope)
}

/**
 * §15.3's property evaluation: the evaluator and the scope its reads resolve through.
 *
 * One type rather than two constructor arguments because the two only ever travel together, and
 * one call site — a modifier entry read off a node — has neither. [eval] is the one line that
 * keeps §15.3's spelling in one place.
 */
internal class ExpressionSource(
    private val evaluator: Evaluator,
    private val scope: EvalScope,
) {
    fun eval(typed: TypedExpr): Value = evaluator.eval(typed, scope)
}

/**
 * Reads one node's or one modifier entry's effective props through the shared kind table.
 *
 * A constant is answered before [expressions] is consulted, so a document that resolves today
 * resolves byte for byte as it did. Only a computed value reaches the evaluator — which is the
 * whole of §15.3's property row.
 *
 * [expressions] is null on the modifier path, where §15.3 folds arguments through the appliers
 * and no scope reaches one. A computed argument there is refused by name rather than guessed at.
 */
internal class MapPropertyReader(
    private val props: Map<PropertyKey, ResolvedProp>,
    private val expressions: ExpressionSource?,
) : PropertyReader {
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(spec: PropertySpec<T>): T? {
        val prop = props[spec.key] ?: return null
        val const = prop.value.constantOrNull()
        val value = if (const != null) {
            const
        } else {
            val source = expressions ?: throw IllegalStateException(
                "Property '${spec.key}' is a modifier argument, and §15.3 gives a modifier applier " +
                    "no scope to evaluate it in",
            )
            val typed = prop.typed ?: throw IllegalStateException(
                "Property '${spec.key}' carries no checked expression; §17.1's pass 5 attaches one " +
                    "and a document without it was refused",
            )
            source.eval(typed)
        }
        val decoded = ValueKinds.kindFor(spec.type).decode(value)
        if (decoded is Value.Token) return TokenName(decoded.name) as T
        return decoded as T
    }
}

/** The skeleton theme: ambient style now, token lookup with ThemeHost later. */
internal class DefaultThemeHandle(theme: ResolvedTheme) : ThemeHandle {
    @Composable
    override fun textStyle(name: TokenName): TextStyle = TextStyle.Default
}

/** The scope renderers actually get: reads, fold, keyed slots, handle threading. */
internal class DefaultRenderScope(
    override val environment: RuntimeEnvironment,
    override val theme: ThemeHandle,
    private val runtime: UiRuntime,
    private val handle: ScopeHandle?,
    private val expressions: ExpressionSource,
) : RenderScope {
    override fun props(node: ResolvedNode): PropertyReader = MapPropertyReader(node.props, expressions)

    override fun modifierFor(node: ResolvedNode): Modifier {
        var current: Modifier = Modifier
        for (entry in node.modifiers) {
            val applier = runtime.modifiers[entry.type] ?: continue
            current = applier.apply(current, entry.args, ScopeBag(node.scopes, handle))
        }
        return current
    }

    @Composable
    override fun RenderSlot(node: ResolvedNode, slot: SlotName): Unit {
        val children = node.slots[slot] ?: return
        for (child in children) {
            key(child.id) {
                environment.hooks.Decorate(child) {
                    RenderNode(runtime, child, copy(handle = handle))
                }
            }
        }
    }

    @Composable
    override fun withScope(handle: ScopeHandle, content: @Composable RenderScope.() -> Unit): Unit =
        content(copy(handle = handle))

    private fun copy(handle: ScopeHandle?): DefaultRenderScope =
        DefaultRenderScope(environment, theme, runtime, handle, expressions)
}

/**
 * Model colour to compose colour. Same packed layout, so a reinterpretation.
 */
public fun ColorArgb.toCompose(): Color = Color(argb)
