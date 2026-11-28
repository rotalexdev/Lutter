package dev.rotalex.lutter.editor

/**
 * How much room the window has, as the editor's own three sizes.
 *
 * ### One seam, read everywhere
 *
 * The shell used to decide its arrangement with a local `maxWidth < 600.dp`, which meant the
 * number lived in a composable and every new decision — which pane becomes a sheet, whether the
 * navigation is a rail or a bar — wrote it again. This is the single answer to "how wide is the
 * window", so a decision about layout is a decision about this value and nothing else (D39).
 *
 * ### The boundaries are Material's
 *
 * 600dp and 840dp are the compact/medium/expanded boundaries of `WindowSizeClass`, so a window
 * that Material would call expanded is expanded here too; a phone held in one hand is [Compact]
 * whatever its pixel count, because the unit is density-independent.
 *
 * ### Why a value and not the runtime's class
 *
 * `WindowSizeClass` comes from `androidx.window`, which the editor depends on for exactly this
 * kind of question, but the arrangement must also be *previewable*: the toolbar can ask for
 * another class without resizing the window, so the compact sheets are reachable on a desktop and
 * a test can see them. A plain value can be overridden; a class computed by the platform cannot.
 */
public enum class EditorWindowClass {

    /** One pane and a sheet: a phone, or a window that has been dragged narrow. */
    Compact,

    /** The canvas and the inspector, with the palette arriving over them. */
    Medium,

    /** The three panes side by side, the arrangement a desk has room for. */
    Expanded;

    public companion object {

        /** The width at which a second pane fits beside the canvas. */
        public const val MediumWidthDp: Int = 600

        /** The width at which a third pane fits as well. */
        public const val ExpandedWidthDp: Int = 840

        /**
         * The class a window [widthDp] wide gets.
         *
         * Truncated at the call site: a window measured at 599.6dp is [Compact], because the
         * alternative — a fraction of a density-independent pixel flipping the arrangement — is not
         * a decision worth modelling.
         */
        public fun of(widthDp: Int): EditorWindowClass = when {
            widthDp >= ExpandedWidthDp -> Expanded
            widthDp >= MediumWidthDp -> Medium
            else -> Compact
        }
    }
}
