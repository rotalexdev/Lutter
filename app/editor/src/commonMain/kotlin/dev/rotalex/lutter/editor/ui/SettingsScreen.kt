package dev.rotalex.lutter.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.rotalex.lutter.editor.settings.EditorSettings
import dev.rotalex.lutter.editor.settings.ThemeChoice
import dev.rotalex.lutter.editor.ui.theme.LutterSpacing

/**
 * The settings, as a list of the things a person can change about how the editor looks and saves.
 *
 * A body rather than a whole screen: [EditorShell] owns the top bar and the back action, so this
 * composable is the same shape as `PalettePane` and `InspectorPane` — content, arranged by the
 * frame for the width it was given. Two screens with their own bars would be two places to keep
 * the insets right.
 *
 * Everything here is a control bound to [settings] and reporting [onChange]; the caller writes and
 * this redraws. Nothing in this file remembers a value, which is what makes the screen a view of
 * the store instead of a second copy of it — the copy is how a settings screen and a settings file
 * start disagreeing.
 */
@Composable
public fun SettingsScreen(
    settings: EditorSettings,
    onChange: (EditorSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LutterSpacing.lg, vertical = LutterSpacing.md)
            .testTag("settings"),
        verticalArrangement = Arrangement.spacedBy(LutterSpacing.xs),
    ) {
        SectionTitle("Appearance")
        ThemeChoice.entries.forEach { choice ->
            SettingChoice(
                label = choice.label,
                // The one line that says what picking it does, because "System" on its own reads
                // like a fourth theme rather than like a fourth way to choose one.
                detail = choice.detail,
                selected = settings.theme == choice,
                tag = "settings:theme:${choice.name}",
                onSelect = { onChange(settings.copy(theme = choice)) },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = LutterSpacing.sm))

        SectionTitle("Code")
        Text(
            text = "Text size ${codeScaleLabel(settings.codeTextScale)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = LutterSpacing.xs),
        )
        Slider(
            value = settings.codeTextScale,
            onValueChange = { scale -> onChange(settings.copy(codeTextScale = scale)) },
            valueRange = EditorSettings.CODE_SCALE_RANGE,
            // A tenth between the ends (seven positions). A slider that lands on 1.037× makes the
            // setting a number nobody chose; the stops are the sizes somebody would pick.
            steps = 5,
            modifier = Modifier.fillMaxWidth().padding(horizontal = LutterSpacing.xs).testTag("settings:code-scale"),
        )
        Text(
            text = "The generated Kotlin is long by nature; this is the size that makes it " +
                "readable on the screen you have.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = LutterSpacing.xs),
        )

        HorizontalDivider(Modifier.padding(vertical = LutterSpacing.sm))

        SectionTitle("Saving")
        SettingToggle(
            label = "Autosave",
            detail = if (settings.autosave) {
                "Every change is written as you make it."
            } else {
                "Nothing is written until you press Save, which the frame shows while this is off."
            },
            checked = settings.autosave,
            tag = "settings:autosave",
            onToggle = { on -> onChange(settings.copy(autosave = on)) },
        )
    }
}

/** A section's heading: what the rows under it are about. */
@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = LutterSpacing.sm),
    )
}

/**
 * One of a set of mutually exclusive answers, drawn as a radio row.
 *
 * `selectable` with `Role.RadioButton` and not `clickable`: a row that reports itself as a button
 * is a row a screen reader describes as "button, Dark" and never says whether it is the one in
 * effect, which is the only thing a radio is for.
 */
@Composable
private fun SettingChoice(
    label: String,
    detail: String,
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = LutterSpacing.xs)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LutterSpacing.sm),
    ) {
        RadioButton(selected = selected, onClick = null)
        LabelAndDetail(label = label, detail = detail, modifier = Modifier.weight(1f))
    }
}

/** The same row for a value with two states. */
@Composable
private fun SettingToggle(
    label: String,
    detail: String,
    checked: Boolean,
    tag: String,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onToggle)
            .padding(vertical = LutterSpacing.xs)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LutterSpacing.sm),
    ) {
        LabelAndDetail(label = label, detail = detail, modifier = Modifier.weight(1f))
        // The `Switch` draws the state; the row handles the touch, so the switch itself must not
        // also answer a second time — the same reason the radio above has a null `onClick`.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A row's two lines: the name of the setting and what it does. */
@Composable
private fun LabelAndDetail(label: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the theme choices are called, and the one line each needs. */
private val ThemeChoice.label: String
    get() = when (this) {
        ThemeChoice.System -> "System"
        ThemeChoice.Light -> "Light"
        ThemeChoice.Dark -> "Dark"
    }

private val ThemeChoice.detail: String
    get() = when (this) {
        ThemeChoice.System -> "Follows the device setting."
        ThemeChoice.Light -> "Always light, whatever the device does."
        ThemeChoice.Dark -> "Always dark, whatever the device does."
    }

/**
 * `×1.0`, `×1.2`: one decimal, because two would print the slider's own floating point noise and
 * none would make 1.04 look like 1.0.
 */
internal fun codeScaleLabel(scale: Float): String {
    val tenths: Int = (scale * 10f).toInt()
    return "×${tenths / 10}.${tenths % 10}"
}
