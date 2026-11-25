package dev.rotalex.lutter.desktopapp

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.PreviewState
import dev.rotalex.lutter.editor.ui.CanvasPane
import dev.rotalex.lutter.editor.ui.FollowPreview
import dev.rotalex.lutter.editor.ui.InspectorPane
import dev.rotalex.lutter.editor.ui.PalettePane
import dev.rotalex.lutter.runtime.UiRuntime

/**
 * The desktop frame: the three panes in a row (PLAN §32 Phase 17 P5).
 *
 * The panes themselves live in `:app:editor` because the Android host draws the same three. This
 * file is the arrangement and the window's share of the work — the row, the widths, and keeping
 * the preview following the session. `EditorPanes.kt` documents each pane.
 */
@Composable
public fun EditorScreen(
    session: EditorSession,
    preview: EditorPreview,
    runtime: UiRuntime,
    modifier: Modifier = Modifier,
) {
    val rendered: PreviewState by preview.state.collectAsState()
    val selection by session.selection.collectAsState()

    FollowPreview(preview)

    Row(modifier.fillMaxSize()) {
        PalettePane(
            sections = session.palette(),
            onInsert = { entry ->
                val target = session.dropTargets(entry.type).firstOrNull()
                if (target != null) session.insert(entry.type, target)
            },
            modifier = Modifier.width(220.dp).fillMaxHeight().testTag("palette"),
        )
        VerticalDivider()
        CanvasPane(
            runtime = runtime,
            state = rendered,
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("canvas"),
        )
        VerticalDivider()
        InspectorPane(
            session = session,
            selection = selection,
            modifier = Modifier.width(280.dp).fillMaxHeight().testTag("inspector"),
        )
    }
}
