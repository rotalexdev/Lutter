package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

/**
 * Document enum entry to Compose value, closed over what the enum specs declare.
 *
 * Alignment has three forms because a row and a column align on one axis each: the entry
 * naming both is read on either, and the default is the centre of the axis in question.
 * A row's children move vertically, a column's horizontally, so the two projections are
 * not interchangeable.
 *
 * Compose splits its own vocabulary in two: the entries naming a side are absolute and live on
 * [AbsoluteAlignment], the rest on [Alignment] alongside the two single-axis forms. An entry
 * says "left", so it resolves to the absolute family — `Start` would mirror it in RTL.
 */
internal object AlignmentMaps {

    private val twoAxis: Map<String, Alignment> = mapOf(
        "TopLeft" to AbsoluteAlignment.TopLeft,
        "TopCenter" to Alignment.TopCenter,
        "TopRight" to AbsoluteAlignment.TopRight,
        "CenterLeft" to AbsoluteAlignment.CenterLeft,
        "Center" to Alignment.Center,
        "CenterRight" to AbsoluteAlignment.CenterRight,
        "BottomLeft" to AbsoluteAlignment.BottomLeft,
        "BottomCenter" to Alignment.BottomCenter,
        "BottomRight" to AbsoluteAlignment.BottomRight,
    )

    private val verticalAxis: Map<String, Alignment.Vertical> = mapOf(
        "TopLeft" to Alignment.Top,
        "TopCenter" to Alignment.Top,
        "TopRight" to Alignment.Top,
        "CenterLeft" to Alignment.CenterVertically,
        "Center" to Alignment.CenterVertically,
        "CenterRight" to Alignment.CenterVertically,
        "BottomLeft" to Alignment.Bottom,
        "BottomCenter" to Alignment.Bottom,
        "BottomRight" to Alignment.Bottom,
    )

    private val horizontalAxis: Map<String, Alignment.Horizontal> = mapOf(
        "TopLeft" to AbsoluteAlignment.Left,
        "TopCenter" to Alignment.CenterHorizontally,
        "TopRight" to AbsoluteAlignment.Right,
        "CenterLeft" to AbsoluteAlignment.Left,
        "Center" to Alignment.CenterHorizontally,
        "CenterRight" to AbsoluteAlignment.Right,
        "BottomLeft" to AbsoluteAlignment.Left,
        "BottomCenter" to Alignment.CenterHorizontally,
        "BottomRight" to AbsoluteAlignment.Right,
    )

    /** The two-axis form, for a box. */
    fun box(entry: String): Alignment = twoAxis[entry] ?: Alignment.Center

    /** The vertical form, for a row: a row's children move up and down. */
    fun vertical(entry: String): Alignment.Vertical = verticalAxis[entry] ?: Alignment.CenterVertically

    /** The horizontal form, for a column: a column's children move left and right. */
    fun horizontal(entry: String): Alignment.Horizontal =
        horizontalAxis[entry] ?: Alignment.CenterHorizontally
}

/**
 * Shape entry to shape. The two are the enum's whole vocabulary; an unknown entry is the
 * rectangle, which is what every painter in the set defaults to.
 */
internal object ShapeMaps {
    fun of(entry: String): Shape =
        if (entry == "Circle") CircleShape else RectangleShape
}
