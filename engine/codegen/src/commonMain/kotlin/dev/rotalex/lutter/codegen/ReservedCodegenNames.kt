package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedPage

/**
 * The top-level identifiers this generator emits, and the document names they reserve.
 *
 * §16.4 resolves a document name against a *library* symbol. Nothing resolved one against the
 * generator's own output, so a page named `Route` and the sealed interface the strategy emits
 * were two answers to one name and only the compile harness noticed.
 *
 * **The check lives here rather than in `Analyzer` because the names live here.** §17.1 files
 * strategy-dependent checks in pass 8, which does not exist, and `ModuleGraphRules.ALLOWED` gives
 * `:engine:analysis` only `:engine:model` and `:engine:schema` while `:engine:codegen` already
 * depends on it — so a naming pass reading the strategies would close a cycle. [KotlinGenerator]
 * calls [findings] before it plans, which is still §16.7's rule: a located finding, not a red
 * build in the harness, and no caller can skip it.
 *
 * Only a page's name and a component's id are checked, because they are the only document names
 * that become a top-level declaration. §12.1 writes a `StateDecl.name` as a holder member or a
 * composable local, where nothing emitted here can reach it.
 */
public object ReservedCodegenNames {

    /** §16.5's `AppHost.kt`, the interface the embedding app implements. */
    public const val AppHostName: String = "AppHost"

    /** §16.5's `SnackbarHost`, the one presentation operation `ui.showSnackbar` reaches. */
    public const val SnackbarHostName: String = "SnackbarHost"

    /** `Route.Home -> HomeScreen(…)`: what the screen of a page is called. */
    public const val ScreenSuffix: String = "Screen"

    /** `class HomeScreenState`: §12.1's page holder, spelled onto a page's name. */
    public const val PageStateSuffix: String = "ScreenState"

    /** `rememberHomeScreenState`: the same holder's constructor, spelled in front of that name. */
    public const val RememberPrefix: String = "remember"

    /**
     * Every name emitted whole: the two this generator writes itself, plus whatever the active
     * strategies emit. No strategy owns the first two — `KotlinGenerator` writes `AppHost.kt`
     * whether or not a strategy has an opinion about presentation hosts.
     */
    public fun literals(options: CodegenOptions): Set<String> =
        setOf(AppHostName, SnackbarHostName) +
            options.navigation.reservedTopLevelNames +
            options.state.reservedTopLevelNames

    /**
     * The three top-level names a page called [pageName] has generated for it.
     *
     * Derived rather than listed, because none of them exists until a page does — a literal
     * list of per-page forms is a list whoever adds the next emitted declaration has to
     * remember, which is the drift this object exists to remove.
     */
    public fun derivedFor(pageName: String): Set<String> = setOf(
        pageName + ScreenSuffix,
        pageName + PageStateSuffix,
        RememberPrefix + pageName + PageStateSuffix,
    )

    /**
     * One `name.reserved` per declared name this generator also emits: pages in id order, then
     * components, so two runs over one document report in the same order.
     *
     * At most one finding per declared name. A name that is both a literal and some page's
     * screen is reported against the literal, which is the one every plugin reserves too.
     */
    public fun findings(document: ResolvedDocument, options: CodegenOptions): List<Diagnostic> {
        val pages: List<ResolvedPage> = document.pages.entries.sortedBy { it.key.value }.map { it.value }
        val reserved: Set<String> = literals(options)
        val owners: MutableMap<String, String> = LinkedHashMap<String, String>()
        for (page in pages) {
            for (name in derivedFor(page.name)) owners.getOrPut(name) { page.name }
        }

        val found: MutableList<Diagnostic> = mutableListOf()
        for (page in pages) {
            val at: DiagnosticLocation = DiagnosticLocation(pageId = page.id)
            val owner: String? = owners[page.name]
            if (page.name in reserved) {
                found += finding("Page", page.name, at, "it is emitted as a top-level declaration")
            } else if (owner != null) {
                found += finding("Page", page.name, at, ownerDetail(owner, page.name))
            }
        }
        for (id in document.components.keys.sortedBy { it.value }) {
            if (id.value in reserved) {
                val at: DiagnosticLocation = DiagnosticLocation(componentDeclId = id)
                found += finding("Component", id.value, at, "it is emitted as a top-level declaration")
            } else {
                val owner: String? = owners[id.value]
                if (owner != null) {
                    val at: DiagnosticLocation = DiagnosticLocation(componentDeclId = id)
                    found += finding("Component", id.value, at, ownerDetail(owner, id.value))
                }
            }
        }
        return found
    }

    private fun ownerDetail(owner: String, name: String): String =
        "page '" + owner + "' emits a screen called '" + name + "'"

    private fun finding(kind: String, name: String, at: DiagnosticLocation, detail: String): Diagnostic =
        Diagnostic(
            Severity.Error,
            DiagnosticCodes.NameReserved,
            at,
            "'" + kind + " " + name + "' is a reserved codegen name: " + detail,
        )
}