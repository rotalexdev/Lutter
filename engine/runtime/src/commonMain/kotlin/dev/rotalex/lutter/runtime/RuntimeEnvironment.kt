package dev.rotalex.lutter.runtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.interpreter.StateStore
import dev.rotalex.lutter.interpreter.env.HostFunctions
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.ResourceId

/**
 * Where resources load from. Empty until resource loading has a reader.
 *
 * The readers are the plan's two (§20:1598): a load for the bytes-shaped kinds and
 * `stringFor` for locale-resolved strings. Variant selection is the engine's, not the
 * host's — [ResourceSelector] is the one implementation every host shares, so a JVM
 * host and a Wasm host answer the same document with the same variant.
 */
public interface ResourceProvider {

    /**
     * Loads the resource [id] names, decoded.
     *
     * Not composable and not cached: a renderer decides how to hold the result while
     * composition subscribes it. A load may be slow — a URL fetch, a disk read — and
     * the caller owns the coroutine that awaits it.
     */
    public suspend fun load(id: ResourceId): LoadedResource

    /**
     * The string [id] names, resolved for [locale] (a BCP-47 tag).
     *
     * Locale is `stringFor`'s argument because a document can render the same string
     * resource under two locales in one screen — a language picker does exactly that —
     * so the environment's locale cannot be the only answer.
     */
    public suspend fun stringFor(id: ResourceId, locale: String): String?

    public companion object {
        /** No resources. Every lookup is the loader's problem, not the renderer's. */
        public val Empty: ResourceProvider = EmptyResources
    }
}

private object EmptyResources : ResourceProvider {
    override suspend fun load(id: ResourceId): LoadedResource = LoadedResource.Missing

    override suspend fun stringFor(id: ResourceId, locale: String): String? = null
}

/**
 * The editor's neutral hook into rendering. The runtime's only editor concept.
 */
public interface RenderHooks {
    /** Wraps one node's output: selection overlays, bounds capture, nothing by default. */
    @Composable
    public fun Decorate(node: ResolvedNode, content: @Composable () -> Unit)

    /** A measured node's bounds. Ignored by default. */
    public fun onNodeMeasured(id: NodeId, bounds: Rect): Unit = Unit

    public companion object {
        /** No hooks. Content renders undecorated. */
        public val None: RenderHooks = NoneHooks
    }
}

private object NoneHooks : RenderHooks {
    @Composable
    override fun Decorate(node: ResolvedNode, content: @Composable () -> Unit): Unit = content()
}

/**
 * Everything rendering needs from the host, in one place.
 *
 * Passed explicitly, never global: previews, tests and editors each build their own.
 *
 * [appState] is §12.1's app scope and is declared as the interpreter's [StateStore] rather than
 * a runtime type of its own: §12.2 puts the interface in `:engine:interpreter` and the Compose
 * implementation here, so the host seeds a `SnapshotStateStore` and keeps it to write through.
 */
public class RuntimeEnvironment(
    public val navigator: Navigator,
    public val host: HostFunctions = HostFunctions.None,
    public val resources: ResourceProvider = ResourceProvider.Empty,
    public val appState: StateStore = SnapshotStateStore.Empty,
    public val diagnostics: (RuntimeDiagnostic) -> Unit = {},
    public val hooks: RenderHooks = RenderHooks.None,
)
