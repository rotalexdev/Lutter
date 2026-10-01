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
import dev.rotalex.lutter.interpreter.constantOrNull
import dev.rotalex.lutter.model.doc.TokenName
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
 * Unknown pages report a diagnostic and render nothing. [args] feeds page params
 * once expressions bind them; until then it is carried, not read.
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
    Box(modifier = modifier) {
        val scope = DefaultRenderScope(environment, DefaultThemeHandle(document.theme), runtime, null)
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

/** Reads one node's effective props through the shared kind table. */
internal class MapPropertyReader(
    private val props: Map<PropertyKey, ResolvedProp>,
) : PropertyReader {
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(spec: PropertySpec<T>): T {
        val prop = props[spec.key] ?: return null as T
        val const = prop.value.constantOrNull()
            ?: throw IllegalStateException(
                "Property '${spec.key}' needs expression evaluation (Phase 6); the skeleton resolves constants only",
            )
        val decoded = ValueKinds.kindFor(spec.type).decode(const)
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
) : RenderScope {
    override fun props(node: ResolvedNode): PropertyReader = MapPropertyReader(node.props)

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
        DefaultRenderScope(environment, theme, runtime, handle)
}

/**
 * Model colour to compose colour. Same packed layout, so a reinterpretation.
 */
public fun ColorArgb.toCompose(): Color = Color(argb)
