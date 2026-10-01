package dev.rotalex.lutter.runtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.schema.component.PropertySpec

/**
 * Typed reads over one resolved node. Defaults are already applied upstream.
 *
 * Absent reads as null, which suits optional handles; required ones are proven
 * present by analysis. Computed values throw: evaluation lands with A3's scope.
 */
public interface PropertyReader {
    public operator fun <T> get(spec: PropertySpec<T>): T
}

/**
 * The theme as renderers see it: token names in, compose values out.
 *
 * The skeleton answers the ambient style; real token lookup lands with ThemeHost.
 */
public interface ThemeHandle {
    @Composable
    public fun textStyle(name: TokenName): TextStyle
}

/**
 * The layout scope a renderer opened. Opaque on purpose: receiver arms arrive
 * with the first scope-gated modifier, and a guess now would name them wrong.
 */
public sealed interface ScopeHandle {
    /** Inside a Column's content lambda. The only provider the skeleton has. */
    public data object Column : ScopeHandle
}

/**
 * What a renderer receives: reads, modifiers, slots and the environment.
 *
 * Renderers never touch [UiRuntime] directly; everything they need is here.
 */
public interface RenderScope {
    /** The host side: navigation, state, diagnostics and editor hooks. */
    public val environment: RuntimeEnvironment

    /** Token reads for the current theme. */
    public val theme: ThemeHandle

    /** Typed reads over [node]'s effective properties. */
    public fun props(node: ResolvedNode): PropertyReader

    /** [node]'s modifiers folded in order through the registered appliers. */
    public fun modifierFor(node: ResolvedNode): Modifier

    /** Renders [slot]'s children, each keyed by its own id. */
    @Composable
    public fun RenderSlot(node: ResolvedNode, slot: SlotName)

    /** Opens [handle] for [content], so scoped modifiers below it resolve. */
    @Composable
    public fun withScope(handle: ScopeHandle, content: @Composable RenderScope.() -> Unit)
}
