package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.PageId
import kotlinx.serialization.Serializable

/**
 * The app-level facts every generated project needs: where it lives, where it starts, and
 * how it navigates.
 *
 * PLAN §13.1 declares the three fields. [startPage] is a `PageId` and not a name, so
 * renaming the page it names is not a document edit (§6.2), and [navigation] has no
 * library type in it, which is what keeps the model free of a decision §16.8's
 * `CodegenOptions.navigation` already holds.
 */
@Serializable
public data class AppSpec(

    /** The generated project's package. §16.4 turns it into the source layout. */
    public val packageName: String,

    /** The page the app opens on. §13.1's analysis checks that it exists. */
    public val startPage: PageId,

    /** Navigation hints. Empty today; see [NavigationSpec]. */
    public val navigation: NavigationSpec = NavigationSpec(),
)

/**
 * Navigation hints, of which there are currently none.
 *
 * PLAN §13.1:1022 declares this class with no fields and argues for it, so the argument is
 * worth one line here rather than a page of prose: the destination a document describes is
 * already `Page.route` plus `Page.params`, and the one hint a reader might expect — a
 * `kind` naming a navigation library — is the decision `CodegenOptions.navigation` holds as
 * a `NavigationStrategy` (§16.8). §4.5's single-resolver rule exists to stop exactly that
 * decision being taken twice.
 *
 * It exists rather than being deleted because [AppSpec.navigation] has a default and is
 * public API: an empty record is a field that can gain a field later, whereas removing it is
 * a break. §31.3 defers the strategies a `kind` would name, so there is nothing for a
 * document-level hint to agree or disagree with yet either.
 *
 * A plain class and not a `data class`: the compiler forbids a data class with no primary
 * constructor parameters. All instances are equal by construction — a record with no state
 * has nothing to differ on — which is what keeps [AppSpec]'s data-class equality working.
 */
@Serializable
public class NavigationSpec() {

    override fun equals(other: Any?): Boolean = other is NavigationSpec

    override fun hashCode(): Int = NavigationSpec::class.hashCode()
}
