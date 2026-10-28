package dev.rotalex.lutter.interpreter.env

/**
 * Where a dialog would be shown, held on the environment so the slot exists before any
 * action writes to it.
 *
 * No operation, and that is the whole of it. Dialog actions arrive after the MVP and nothing
 * yet says what a dialog *is* — there is no declaration for its content, title or arms — so
 * members here would be a shape guessed ahead of the action that has to use it, and every
 * host would implement a guess.
 */
public interface DialogHost {
    public companion object {
        /** Shows nothing. The default for an environment with nothing to show. */
        public val None: DialogHost = NoDialogs
    }
}

private object NoDialogs : DialogHost
