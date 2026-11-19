package dev.rotalex.lutter.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.rotalex.lutter.analysis.resolved.ResolvedPage

/**
 * The page's own store, held for as long as the page composes.
 *
 * §15.3 creates page state with `remember(pageId)`, so a page that leaves and comes back starts
 * from its declarations again and two pages never share a slot. `rememberSaveable` for
 * `Persistence.Saveable` is not here: which types are saveable is §12.2's `StateStrategy`'s
 * question and no engine code reads a non-`None` persistence yet.
 *
 * Internal because [UiScreen] is its only caller, and a host reaches a page's state through the
 * store the screen already holds rather than by building one of its own.
 *
 * [persistent] is the host's §12.1 hook and the page's id is the scope: a `Saveable` declaration
 * comes back from what the host kept, and every write goes through it. Null — the default a
 * preview and a test pass — leaves the page exactly as composition-scoped as it was.
 */
@Composable
internal fun rememberPageStateStore(
    page: ResolvedPage,
    persistent: PersistentStore? = null,
): SnapshotStateStore =
    remember(page.id) { SnapshotStateStore.seeded(page.state, store = persistent, scope = page.id.value) }
