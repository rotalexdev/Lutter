package dev.rotalex.lutter.interpreter.env

/**
 * Where a snackbar is shown, held on the environment so the MVP's one presentation action has
 * somewhere to write.
 *
 * One operation, and one is the whole of it: nothing answers a snackbar, so no step waits on one
 * and no document could reach a dismiss. Whether the next message replaces this one or queues
 * behind it belongs to whoever renders it, and answering that here would make every host
 * implement a guess — which is what a second operation before a renderer exists would be.
 */
public interface SnackbarHost {

    /** Shows [message]. Whether it is still up afterwards is the host's business. */
    public fun show(message: String)

    public companion object {
        /** Shows nothing. The default for an environment with nothing to show. */
        public val None: SnackbarHost = NoSnackbars
    }
}

private object NoSnackbars : SnackbarHost {
    override fun show(message: String): Unit = Unit
}
