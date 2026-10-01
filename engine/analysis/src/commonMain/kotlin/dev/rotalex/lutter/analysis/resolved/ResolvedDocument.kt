package dev.rotalex.lutter.analysis.resolved

import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId

/** One page with its tree built: the unit runtime screens and codegen files consume. */
public data class ResolvedPage(
    public val id: PageId,
    public val name: String,
    public val route: String,
    public val root: ResolvedNode,
)

/**
 * The derived typed form both backends read. Ephemeral — never serialized, never stored.
 *
 * [nodes] indexes every tree node by id for callers that address nodes rather than walk.
 */
public data class ResolvedDocument(
    public val pages: Map<PageId, ResolvedPage>,
    public val components: Map<ComponentDeclId, ResolvedNode>,
    public val nodes: Map<NodeId, ResolvedNode>,
    public val theme: ResolvedTheme,
)
