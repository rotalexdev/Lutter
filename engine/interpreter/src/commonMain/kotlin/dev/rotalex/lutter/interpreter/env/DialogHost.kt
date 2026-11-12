package dev.rotalex.lutter.interpreter.env

/**
 * Where a dialog would be shown, held on the environment so the slot exists before any
 * action writes to it.
 *
 * Wave-2's dialog actions are text-only: a title and a message, no content slot and no
 * dismissal the step could reach. That is a deliberate v1, not an omission — the wave-3
 * components (`SlotOutlet`-hosted dialogs) are the ones that will say what a dialog *is*,
 * and members here would otherwise be a shape guessed ahead of them. When those components
 * arrive, this contract grows; nothing that implements it today has to change shape.
 */
public interface DialogHost {
    /** Shows a modal dialog carrying [title] and [message]. */
    public fun show(title: String, message: String)

    /** Shows a bottom sheet carrying [title] and [message]. */
    public fun showSheet(title: String, message: String)

    public companion object {
        /** Shows nothing. The default for an environment with nothing to show. */
        public val None: DialogHost = NoDialogs
    }
}

private object NoDialogs : DialogHost {
    override fun show(title: String, message: String) {}
    override fun showSheet(title: String, message: String) {}
}
