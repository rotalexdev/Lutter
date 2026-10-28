package dev.rotalex.lutter.interpreter

import dev.rotalex.lutter.model.ids.NodeId

/**
 * One failure, shaped for the host that will show it.
 *
 * [nodeId] is optional because an action carries none: the executor is handed a sequence
 * rather than the node it was declared on, so a diagnostic raised while a handler runs
 * arrives without one and the host attributes it to the event it was handling.
 */
public data class RuntimeDiagnostic(
    public val message: String,
    public val nodeId: NodeId? = null,
)
