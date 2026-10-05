package dev.rotalex.lutter.builtins.actions

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionMetadata
import dev.rotalex.lutter.schema.action.ActionSpec

/**
 * §11.4's `ui.showSnackbar`, the MVP's only presentation action.
 *
 * It declares no parameter, and §11.4 is the whole of what the plan says about it: the id appears
 * in §11.4's MVP list and in §31.2's, and in neither §11.5's list of what codegen emits nor
 * §33.4's `IntrinsicHandlers.kt` row. §11.3's `ActionEnv` offers `scope`, `state`, `navigator`,
 * `dialogs` and `host`, and a snackbar is not a dialog, so where it is raised from is still open.
 */
public object UiActions {

    /** `ui.showSnackbar`: §11.4 names the id and nothing about its arguments. */
    public val showSnackbar: ActionSpec = ActionSpec(
        id = ActionId("ui.showSnackbar"),
        metadata = ActionMetadata("Show snackbar"),
        params = emptyList(),
        emit = ActionEmit.Intrinsic,
    )

    /** Every spec here, in §11.4's order. */
    public val all: List<ActionSpec> = listOf(showSnackbar)
}
