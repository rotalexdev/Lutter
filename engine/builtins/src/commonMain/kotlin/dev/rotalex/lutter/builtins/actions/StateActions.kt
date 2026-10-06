package dev.rotalex.lutter.builtins.actions

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionMetadata
import dev.rotalex.lutter.schema.action.ActionSpec

/**
 * §11.4's `state.set`, the action §12.3 keeps explicit instead of a two-way binding (ADR-009).
 *
 * It declares no parameter, and that is a gap rather than a shape. §12.3 states what the target
 * must satisfy — writable, not derived, type-compatible — and its example passes `event.value`,
 * but no section names the argument keys, so naming them here would put a string on the wire
 * that §11.3 would then have to be amended to match.
 */
public object StateActions {

    /** `state.set`: §12.3's write to one writable target; the plan leaves its argument keys unnamed. */
    public val set: ActionSpec = ActionSpec(
        id = ActionId("state.set"),
        metadata = ActionMetadata("Set state"),
        params = emptyList(),
        emit = ActionEmit.Intrinsic,
    )

    /** Every spec here, in §11.4's order. */
    public val all: List<ActionSpec> = listOf(set)
}
