package dev.rotalex.lutter.runtime

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
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
 * present by analysis. Computed constants resolve; other expressions throw until Phase 6.
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
 * The layout scope a renderer opened: the receiver a gated modifier applies against.
 *
 * Each arm carries its receiver because the implicit one does not reach a modifier applier —
 * a nested function body has no access to the `RowScope` the caller's lambda introduced. An
 * applier casts to the arm it needs and reads the receiver there.
 */
public sealed interface ScopeHandle {
    /** Inside a Column's content lambda. */
    public class Column(public val scope: ColumnScope) : ScopeHandle

    /** Inside a Row's content lambda. */
    public class Row(public val scope: RowScope) : ScopeHandle

    /** Inside a Box's content lambda. */
    public class Box(public val scope: BoxScope) : ScopeHandle
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
