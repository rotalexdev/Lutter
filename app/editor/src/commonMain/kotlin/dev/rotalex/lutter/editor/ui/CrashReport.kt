package dev.rotalex.lutter.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The stack trace of the last crash, on the launch after it.
 *
 * ### Why the app shows it
 *
 * The app is a nightly: the person running it is the person who reports what breaks, and the two
 * platforms disagree about where a crash goes. A desktop launch from a menu has no terminal to
 * print to; a phone has no `adb` in a tester's hand. A trace that only reaches a log the reporter
 * cannot read is a trace nobody sends, so the frame that recorded it shows it instead, selectable
 * so it can be copied into a message.
 *
 * ### Why it is a screen and not a dialog
 *
 * It is read once, it is long, and the thing behind it is a document the app has not decided it
 * can draw yet. [onContinue] is the person saying "I have what I need"; it also deletes the
 * record, so the next launch opens the editor instead of this screen.
 */
@Composable
public fun CrashReportScreen(
    trace: String,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.colorScheme
    Surface(modifier.fillMaxSize(), color = theme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "The editor closed unexpectedly last time",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "This is the trace it left. Copy it into a report — it is the only record " +
                    "of what happened, and the editor kept your document.",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.onSurfaceVariant,
            )
            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(theme.surfaceVariant)
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("crash-report"),
            ) {
                Text(
                    text = trace,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().testTag("crash-continue"),
            ) {
                Text("Continue to the editor")
            }
        }
    }
}
