package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.schema.component.ScopeId

/**
 * The layout scopes of wave 1, named once.
 *
 * A slot that provides one and a modifier that requires one must agree on the spelling, so
 * both read it from here: the analyzer's set arithmetic and the runtime's scope handle key on
 * the same value.
 */
public object LayoutScopes {
    /** `Row.children` provides it and `layout.weight` requires it (§7.3). */
    public val Row: ScopeId = ScopeId("compose.RowScope")

    /** `Column.children` provide it. */
    public val Column: ScopeId = ScopeId("compose.ColumnScope")

    /** `Box.children` provide it. */
    public val Box: ScopeId = ScopeId("compose.BoxScope")
}
