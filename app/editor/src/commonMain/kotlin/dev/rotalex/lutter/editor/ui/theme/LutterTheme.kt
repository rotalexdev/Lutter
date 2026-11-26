package dev.rotalex.lutter.editor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rotalex.lutter.editor.settings.ThemeChoice

/**
 * The editor's one theme.
 *
 * ### Why this exists
 *
 * Both hosts used to call `MaterialTheme { }` with no arguments. That is not "the default theme",
 * it is *no* theme: Material 3 then falls back to its baseline scheme, baseline typography and
 * baseline shapes, which is why an editor with real panes in it still read as a prototype. A design
 * system is a decision, and a decision that exists nowhere is not made.
 *
 * ### Why the tokens are indigo and slate
 *
 * The editor is a developer tool that sits next to an IDE all day, so the surfaces are near-neutral
 * and the one saturated role — [lightColorScheme]'s primary — is reserved for what is actionable:
 * the selected tab, the focused field, the drop target. A teal tertiary marks "the document is
 * valid"; the error role is left to Material's own red so a refusal never looks decorative.
 *
 * ### What is deliberately not here
 *
 * No dynamic colour: that is an Android-only API and this theme is compiled into `commonMain` for
 * the desktop window as well. No custom font family: the platform's UI font is the right default
 * for a tool, and a bundled font would be several hundred kilobytes for a difference nobody asked
 * for. Both are additive later without changing a caller.
 */
@Composable
public fun LutterTheme(
    theme: ThemeChoice = ThemeChoice.System,
    content: @Composable () -> Unit,
) {
    // `System` asks the platform, which is the only thing that knows: `isSystemInDarkTheme` is a
    // composable read, so a device that flips at sunset flips this window with it. The other two
    // answers are the reader overruling the device, which is why they are not "dark: Boolean".
    val dark: Boolean = when (theme) {
        ThemeChoice.System -> isSystemInDarkTheme()
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = LutterTypography,
        shapes = LutterShapes,
        content = content,
    )
}

/**
 * The editor's spacing scale.
 *
 * Named by size rather than by role, because a role name ("pane padding") stops being true the
 * moment two panes want different padding and then there are two roles and no scale. Four to
 * twenty-four in fours is the whole system; a value outside it is a layout that is not in the
 * system yet.
 */
public object LutterSpacing {
    public val xs: Dp = 4.dp
    public val sm: Dp = 8.dp
    public val md: Dp = 12.dp
    public val lg: Dp = 16.dp
    public val xl: Dp = 24.dp
}

/**
 * The palette, in Material 3's roles rather than in colours.
 *
 * Every value below is a role: `primary` is what is actionable, `surfaceVariant` is what recedes,
 * `error` is a refusal. Naming a hex here is the only place a hex is allowed; the panes ask for
 * `MaterialTheme.colorScheme.primary` and never for a colour of their own.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E0F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF00696E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF1F8),
    onTertiaryContainer = Color(0xFF002022),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF303038),
    inverseOnSurface = Color(0xFFF3EFF7),
    inversePrimary = Color(0xFFBAC3FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF2A3A9E),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFF80D4DA),
    onTertiary = Color(0xFF00363A),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFF9CF1F8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46464F),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303038),
    inversePrimary = Color(0xFF3F51B5),
)

/**
 * The baseline scale with the three weights a tool actually needs changed.
 *
 * Titles are the labels of the panes and the app bar, so they carry [FontWeight.SemiBold] rather
 * than the baseline's regular weight: at 14–22sp against a near-neutral surface, regular title
 * weight is indistinguishable from body text and the hierarchy disappears. Labels get a little
 * more tracking for the same reason at 11sp. Nothing else is overridden — the scale itself is
 * Material's and it is already good.
 */
private val LutterTypography: Typography = Typography().let { baseline ->
    baseline.copy(
        titleLarge = baseline.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = baseline.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = baseline.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
        labelSmall = baseline.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

/**
 * Rounded, but not playful: 8–16dp.
 *
 * The panes are cards on a page, and their radius is what tells a reader that a block of controls
 * belongs together. `extraLarge` is left for sheets and dialogs, which Material draws itself.
 */
private val LutterShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
