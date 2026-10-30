package dev.rotalex.lutter.builtins.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The runtime half of [MaterialIconSet]: the same names, resolved to the vectors the
 * renderer draws. The schema side maps to Kotlin symbols for codegen; this maps to the
 * image vectors composition needs. `BuiltinRenderersCoverageTest` holds the two maps to
 * the same keys, because a name registered on one side only renders on one side only.
 */
internal object MaterialIconImages {
    /** The resolver `IconRenderer` reads: icon name → vector. */
    internal val images: Map<String, ImageVector> = mapOf(
        "home" to Icons.Filled.Home,
        "search" to Icons.Filled.Search,
        "settings" to Icons.Filled.Settings,
        "close" to Icons.Filled.Close,
        "add" to Icons.Filled.Add,
        "check" to Icons.Filled.Check,
        "delete" to Icons.Filled.Delete,
        "menu" to Icons.Filled.Menu,
        "arrow_back" to Icons.AutoMirrored.Filled.ArrowBack,
        "favorite" to Icons.Filled.Favorite,
        "star" to Icons.Filled.Star,
        "share" to Icons.Filled.Share,
    )
}
