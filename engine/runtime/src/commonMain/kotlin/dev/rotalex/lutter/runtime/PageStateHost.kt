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
 */
@Composable
internal fun rememberPageStateStore(page: ResolvedPage): SnapshotStateStore =
    remember(page.id) { SnapshotStateStore.seeded(page.state) }
