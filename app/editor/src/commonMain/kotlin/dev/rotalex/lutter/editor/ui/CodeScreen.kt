package dev.rotalex.lutter.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.editor.CodeView
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.PreviewState
import dev.rotalex.lutter.editor.generatedCode

/**
 * The document as the Kotlin it becomes: read-only, one block per file, selectable.
 *
 * ### Why it exists
 *
 * The editor's promise is that a document *is* an app. Until this screen there was no way to see
 * that from inside the editor — you had to run the CLI or read the acceptance test — so the
 * promise was true and invisible, which for a person holding a phone is the same as absent.
 *
 * ### What it is not
 *
 * It is not an editor of the generated code, and it must never become one: the document is the
 * source and the Kotlin is a projection of it. Text here is selectable so a reader can copy it
 * out; it is not editable, and there is nothing to save.
 *
 * ### Why the files are generated here and not streamed
 *
 * Generation is a pure function of the resolved document and takes milliseconds on the documents
 * this editor is built for, so it runs once per document change, in `remember`. A document large
 * enough for that to be a dropped frame would be the signal to move it off the composition
 * thread, not a reason to build the machinery first.
 *
 * `FollowPreview` is called here too, not only by the panes: while this screen is shown the
 * panes are out of the composition, and a preview that stopped following would show code for a
 * document the author has already edited.
 */
@Composable
public fun CodeScreen(
    session: EditorSession,
    preview: EditorPreview,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.colorScheme
    val state: PreviewState by preview.state.collectAsState()

    FollowPreview(preview)

    Column(modifier.fillMaxSize().background(theme.surfaceVariant).padding(12.dp)) {
        Text("Generated Kotlin", style = MaterialTheme.typography.titleMedium)
        when (val current = state) {
            PreviewState.Empty -> Text(
                text = "Nothing resolved yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            is PreviewState.Refused -> Text(
                text = current.reason
                    ?: current.diagnostics.joinToString("\n") { "${it.code.value}: ${it.message}" },
                style = MaterialTheme.typography.bodyMedium,
                color = theme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            is PreviewState.Rendered -> {
                val packageName: String = current.document.app.packageName
                val view: CodeView = remember(current.resolved, packageName) {
                    generatedCode(session.schema, current.resolved, packageName)
                }
                Text(
                    text = if (view.isEmpty) {
                        "Nothing was generated · package $packageName"
                    } else {
                        "${view.files.size} files · package $packageName · read-only"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                if (view.diagnostics.isNotEmpty()) {
                    Text(
                        text = view.diagnostics.joinToString("\n") { "${it.code.value}: ${it.message}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (view.isEmpty) {
                    Text(
                        text = "The generator refused this document and said why above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("code"),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(view.files, key = { it.path }) { file ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(theme.surface)
                                    .padding(10.dp)
                                    .testTag("code:${file.path}"),
                            ) {
                                Text(
                                    text = file.path,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = theme.primary,
                                )
                                SelectionContainer {
                                    Text(
                                        text = file.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
