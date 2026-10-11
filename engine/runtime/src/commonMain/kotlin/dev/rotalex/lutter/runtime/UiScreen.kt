package dev.rotalex.lutter.runtime

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.analysis.resolved.ResolvedProp
import dev.rotalex.lutter.analysis.resolved.ResolvedTheme
import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.interpreter.action.ActionEnv
import dev.rotalex.lutter.interpreter.constantOrNull
import dev.rotalex.lutter.interpreter.env.Destination
import dev.rotalex.lutter.interpreter.eval.Evaluator
import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.kind.ValueKinds
import dev.rotalex.lutter.schema.overlay.documentDeclIdOf
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The application root: the page [environment]'s navigator is on, with the arguments it was
 * opened with.
 *
 * [UiScreen] renders the page the caller names, which is what a preview or a single page wants
 * and what an application cannot use: a caller that names one page never reads the navigator,
 * so nothing subscribes it and the composition stays on a page the document already left.
 * Reading [Destination.page] here is what re-renders the destination instead, and it is the
 * whole of what this adds — a host that renders one fixed page still calls [UiScreen].
 */
@Composable
public fun UiApp(
    runtime: UiRuntime,
    document: ResolvedDocument,
    environment: RuntimeEnvironment,
    modifier: Modifier = Modifier,
): Unit {
    val destination: Destination = environment.navigator.current
    UiScreen(runtime, document, destination.page, environment, destination.args, modifier)
}

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
            // Material's press is a plain lambda, so dispatch cannot be composable and needs a
            // scope held here rather than remembered per call.
            rememberCoroutineScope(),
            // §8.4's declarations, lowered by pass 7: an instance renders one of these rather
            // than anything the renderer registry carries.
            declarations = document.components,
        ) { eventArgs -> ScreenActionEnv(screen, pageState, environment, eventArgs) }
        key(resolved.root.id) {
            environment.hooks.Decorate(resolved.root) {
                RenderNode(runtime, resolved.root, scope)
            }
        }
    }
}

/**
 * One node through the registry. A miss is a `doc.*` instance (§5.7), not a blank: a document
 * component has no renderer of its own because an instance draws its declaration's body.
 *
 * A type that is neither is a schema type without coverage, which construction already refused;
 * [RenderScope.RenderInstance] reports that rather than hiding it behind an empty column.
 *
 * No recovery around the call itself: Compose forbids try/catch around composable
 * invocations, and a renderer that throws on resolved input is a contract violation
 * analysis already had the chance to catch — not a data condition to report.
 */
@Composable
public fun RenderNode(runtime: UiRuntime, node: ResolvedNode, scope: RenderScope): Unit {
    val renderer = runtime.renderers[node.type]
    if (renderer != null) {
        renderer.Render(node, scope)
        return
    }
    scope.RenderInstance(node)
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

    /**
     * §15.3's row for one effective property: its constant when it has one, the evaluator when
     * it does not. Both the renderers' reads and a component instance's params come through
     * here, because a param is the same question asked of the node that binds it.
     */
    fun valueOf(prop: ResolvedProp): Value {
        val const = prop.value.constantOrNull()
        if (const != null) return const
        val typed = prop.typed ?: throw IllegalStateException(
            "Property '${prop.key}' carries no checked expression; §17.1's pass 5 attaches one " +
                "and a document without it was refused",
        )
        return eval(typed)
    }

    /** The same evaluator with [params] bound on top — one component instance's inputs. */
    fun withParams(params: Map<ParamName, Value>): ExpressionSource =
        ExpressionSource(evaluator, InstanceScope(scope, params))
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
            source.valueOf(prop)
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

/**
 * The scope renderers actually get: reads, fold, keyed slots, handle threading.
 *
 * The fields past [actionEnv] are what §5.7's instantiation needs and nothing else does:
 * [declarations] are the resolved bodies the overlay synthesized specs for, [open] is the
 * instance currently being expanded, and [opened] is the chain of them, which is where an
 * instantiation cycle is caught — pass 1's cycle check walks the node graph, and a `doc.*` body
 * is not a node of the tree that instantiates it.
 */
internal class DefaultRenderScope(
    override val environment: RuntimeEnvironment,
    override val theme: ThemeHandle,
    private val runtime: UiRuntime,
    private val handle: ScopeHandle?,
    private val expressions: ExpressionSource,
    private val coroutines: CoroutineScope,
    private val actionEnv: (Map<String, Value>) -> ActionEnv,
    private val declarations: Map<ComponentDeclId, ResolvedNode> = emptyMap(),
    private val open: OpenInstance? = null,
    private val opened: List<ComponentDeclId> = emptyList(),
    private val instanceRoot: NodeId? = null,
    private val instanceModifier: Modifier = Modifier,
) : RenderScope {

    /**
     * The handler behind [node]'s [event], run against the page's own scope and store.
     *
     * A node declaring no such event runs nothing. That is the honest answer for a renderer
     * binding a component whose spec declares the event: whether *this* document wrote a handler
     * is the document's business, not a lookup failure.
     */
    override fun Dispatch(node: ResolvedNode, event: EventKey, args: Map<String, Value>) {
        val sequence = node.events[event] ?: return
        coroutines.launch { runtime.runActions(sequence, actionEnv(args), environment) }
    }
    override fun props(node: ResolvedNode): PropertyReader = MapPropertyReader(node.props, expressions)

    override val instance: ResolvedNode? get() = open?.node

    /**
     * The declaration root's own modifiers, with the instance's in front of them.
     *
     * §5.5 makes composition the whole of a document component, so an instance carries a
     * modifier where a wrapping call would take one and it lands on the body's root rather than
     * on a node the document wrote. The root's own modifiers follow, which is the order the
     * modifier list would have had if the two bodies were one node.
     */
    override fun modifierFor(node: ResolvedNode): Modifier {
        var current: Modifier = if (node.id == instanceRoot) instanceModifier else Modifier
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

    @Composable
    override fun RenderInstance(node: ResolvedNode): Unit {
        val expanded = expand(node) ?: return
        key(node.id) {
            environment.hooks.Decorate(expanded.root) {
                RenderNode(runtime, expanded.root, expanded.scope)
            }
        }
    }

    /**
     * The declaration [node] instantiates and the scope its body reads through, or null with the
     * reason already reported.
     *
     * Split out of [RenderInstance] because everything it decides is a question about the
     * document rather than about Compose: which declaration, whether it exists, whether it is
     * already being drawn, and what its params bind to. Only the last step is composable.
     */
    internal fun expand(node: ResolvedNode): Expanded? {
        val decl = documentDeclIdOf(node.type)
        if (decl == null) {
            environment.diagnostics(
                RuntimeDiagnostic("No renderer for '${node.type}' and it names no document component", node.id),
            )
            return null
        }
        val root = declarations[decl]
        if (root == null) {
            environment.diagnostics(RuntimeDiagnostic("Document declares no component '$decl'", node.id))
            return null
        }
        if (decl in opened) {
            // Pass 1's cycle check walks nodes, and a declaration's body is not a node of the
            // tree that instantiates it, so `doc.a` containing an instance of `doc.a` is a tree
            // with no cycle in it and an expansion that never ends.
            environment.diagnostics(
                RuntimeDiagnostic(
                    "Component '${node.type}' instantiates itself: $decl is already open",
                    node.id,
                ),
            )
            return null
        }
        return Expanded(
            root = root,
            scope = copy(
                expressions = expressions.withParams(paramsOf(node)),
                open = OpenInstance(node, decl),
                opened = opened + decl,
                instanceRoot = root.id,
                instanceModifier = modifierFor(node),
            ),
        )
    }

    /** §5.5's params read off the instance's own properties, evaluated where it was written. */
    private fun paramsOf(node: ResolvedNode): Map<ParamName, Value> {
        val params = LinkedHashMap<ParamName, Value>()
        for ((key, prop) in node.props) params[ParamName(key.value)] = expressions.valueOf(prop)
        return params
    }

    private fun copy(
        handle: ScopeHandle? = this.handle,
        expressions: ExpressionSource = this.expressions,
        open: OpenInstance? = this.open,
        opened: List<ComponentDeclId> = this.opened,
        instanceRoot: NodeId? = this.instanceRoot,
        instanceModifier: Modifier = this.instanceModifier,
    ): DefaultRenderScope = DefaultRenderScope(
        environment,
        theme,
        runtime,
        handle,
        expressions,
        coroutines,
        actionEnv,
        declarations,
        open,
        opened,
        instanceRoot,
        instanceModifier,
    )
}

/**
 * One `doc.*` instance being expanded: the node it was written as and the declaration it names.
 *
 * The node rather than its resolved children, because that is what a `core.SlotOutlet` in the
 * body has to reach: §5.5 fills a slot at the call site and draws it in the declaration, so the
 * content lives on the instance and the outlet only knows which slot to ask for.
 */
internal class OpenInstance(
    val node: ResolvedNode,
    val decl: ComponentDeclId,
)

/** What [DefaultRenderScope.expand] resolved: a declaration root and the scope to draw it in. */
internal class Expanded(
    val root: ResolvedNode,
    val scope: DefaultRenderScope,
)

/**
 * Model colour to compose colour. Same packed layout, so a reinterpretation.
 */
public fun ColorArgb.toCompose(): Color = Color(argb)
