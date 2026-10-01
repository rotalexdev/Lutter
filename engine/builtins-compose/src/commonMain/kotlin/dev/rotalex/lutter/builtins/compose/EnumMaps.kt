package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.layout.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.ui.graphics.Shape

/**
 * Document enum entry to Compose value, closed over what the enum specs declare.
 *
 * Alignment has three forms because a row and a column align on one axis each: the entry
 * naming both is read on either, and the default is the centre of the axis in question.
 * A row's children move vertically, a column's horizontally, so the two projections are
 * not interchangeable.
 */
internal object AlignmentMaps {

    private val twoAxis: Map<String, Alignment> = mapOf(
        "TopLeft" to Alignment.TopLeft,
        "TopCenter" to Alignment.TopCenter,
        "TopRight" to Alignment.TopRight,
        "CenterLeft" to Alignment.CenterLeft,
        "Center" to Alignment.Center,
        "CenterRight" to Alignment.CenterRight,
        "BottomLeft" to Alignment.BottomLeft,
        "BottomCenter" to Alignment.BottomCenter,
        "BottomRight" to Alignment.BottomRight,
    )

    private val verticalAxis: Map<String, Alignment.Vertical> = mapOf(
        "TopLeft" to Alignment.Vertical.Top,
        "TopCenter" to Alignment.Vertical.Top,
        "TopRight" to Alignment.Vertical.Top,
        "CenterLeft" to Alignment.Vertical.Center,
        "Center" to Alignment.Vertical.Center,
        "CenterRight" to Alignment.Vertical.Center,
        "BottomLeft" to Alignment.Vertical.Bottom,
        "BottomCenter" to Alignment.Vertical.Bottom,
        "BottomRight" to Alignment.Vertical.Bottom,
    )

    private val horizontalAxis: Map<String, Alignment.Horizontal> = mapOf(
        "TopLeft" to Alignment.Horizontal.Left,
        "TopCenter" to Alignment.Horizontal.Center,
        "TopRight" to Alignment.Horizontal.Right,
        "CenterLeft" to Alignment.Horizontal.Left,
        "Center" to Alignment.Horizontal.Center,
        "CenterRight" to Alignment.Horizontal.Right,
        "BottomLeft" to Alignment.Horizontal.Left,
        "BottomCenter" to Alignment.Horizontal.Center,
        "BottomRight" to Alignment.Horizontal.Right,
    )

    /** The two-axis form, for a box. */
    fun box(entry: String): Alignment = twoAxis[entry] ?: Alignment.Center

    /** The vertical form, for a row: a row's children move up and down. */
    fun vertical(entry: String): Alignment.Vertical = verticalAxis[entry] ?: Alignment.Vertical.Center

    /** The horizontal form, for a column: a column's children move left and right. */
    fun horizontal(entry: String): Alignment.Horizontal =
        horizontalAxis[entry] ?: Alignment.Horizontal.Center
}

/**
 * Shape entry to shape. The two are the enum's whole vocabulary; an unknown entry is the
 * rectangle, which is what every painter in the set defaults to.
 */
internal object ShapeMaps {
    fun of(entry: String): Shape =
        if (entry == "Circle") CircleShape else RectangleShape
}
