package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.model.doc.DataModelDecl
import dev.rotalex.lutter.model.doc.EnumTypeDecl
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.model.diagnostic.Severity

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

    /** The dialog presentation host, the one the two dialog actions reach beside `SnackbarHost`. */
    public const val DialogHostName: String = "DialogHost"

    /** `Route.Home -> HomeScreen(…)`: what the screen of a page is called. */
    public const val ScreenSuffix: String = "Screen"

    /** `class HomeScreenState`: §12.1's page holder, spelled onto a page's name. */
    public const val PageStateSuffix: String = "ScreenState"

    /**
     * `val lutterAppState = LocalAppState.current`: the hoisted local a handler's lambda reads
     * app state through, and the one name here that is a function local rather than a
     * top-level declaration. A document's own state name is spelled onto a parameter or a local
     * of its composable — where nothing emitted here can reach it, per §12.1 — so `findings`
     * never checks this one; the `lutter` prefix is what keeps the space closed all the same.
     *
     * Internal because `literals` reserves *top-level* names and nothing outside the module
     * spells the local: making it public would put a member in the ABI dump for a generator
     * detail, which is what kept [ActionEmitter]'s dead `imports` list public too.
     */
    internal const val HoistedAppStateName: String = "lutterAppState"

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
     * The top-level names a page called [pageName] has generated for it: its screen, plus every
     * one the selected strategy emits for its row — `HomeScreenState` and
     * `rememberHomeScreenState` under the default, `HomeScreenState` and `HomeViewModel` under
     * the viewmodel one.
     *
     * Derived rather than listed, because none of them exists until a page does — a literal
     * list of per-page forms is a list whoever adds the next emitted declaration has to
     * remember, which is the drift this object exists to remove. The row is the strategy's to
     * spell, so asking it is the only way one strategy's names cannot be reserved under another.
     */
    public fun derivedFor(pageName: String, state: StateStrategy): Set<String> =
        setOf(pageName + ScreenSuffix) + state.pageReservedNames(pageName)

    /**
     * One `name.reserved` per declared name this generator also emits: pages in id order, then
     * components, then models, then enums, so two runs over one document report in the same order.
     *
     * At most one finding per declared name. A name that is both a literal and some page's
     * screen is reported against the literal, which is the one every plugin reserves too.
     *
     * Models and enums are checked here rather than anywhere else because `model/Models.kt` is a
     * top-level declaration like a screen is, and D27 could not check them: the names were not on
     * `ResolvedDocument`, so there was nothing to read. Two declarations that spell one name are
     * refused against whichever came first in this order, which is what makes the finding stable.
     */
    public fun findings(document: ResolvedDocument, options: CodegenOptions): List<Diagnostic> {
        val pages: List<ResolvedPage> = document.pages.entries.sortedBy { it.key.value }.map { it.value }
        val reserved: Set<String> = literals(options)
        val owners: MutableMap<String, String> = LinkedHashMap<String, String>()
        for (page in pages) {
            for (name in derivedFor(page.name, options.state)) owners.getOrPut(name) { page.name }
        }

        val found: MutableList<Diagnostic> = mutableListOf()

        /** Names emitted whole, so a second declaration spelling one is a duplicate. */
        val claimed: MutableMap<String, String> = LinkedHashMap<String, String>()

        /**
         * Refuses [name] if something already owns it, and otherwise claims it.
         *
         * Claiming is the part D27 lacked: a model called `User` and a component called `User`
         * are one declaration emitted twice, and neither of them is a literal.
         */
        fun check(kind: String, name: String, at: DiagnosticLocation, claim: Boolean = true) {
            val owner: String? = owners[name]
            val claim: String? = claimed[name]
            if (name in reserved) {
                found += finding(kind, name, at, "it is emitted as a top-level declaration")
            } else if (owner != null) {
                found += finding(kind, name, at, ownerDetail(owner, name))
            } else if (claim != null) {
                found += finding(kind, name, at, claim + " is emitted under the same name")
            } else if (claim) {
                claimed[name] = kind.lowercase() + " '" + name + "'"
            }
        }

        // A page's name is not emitted whole — `Route.Home` and `HomeScreen` are — so it claims
        // nothing; a component's id and a model's name are the declarations themselves.
        for (page in pages) check("Page", page.name, DiagnosticLocation(pageId = page.id), claim = false)
        for (id in document.components.keys.sortedBy { it.value }) {
            check("Component", id.value, DiagnosticLocation(componentDeclId = id))
        }
        for (id in document.dataModels.keys.sortedBy { it.value }) {
            val model: DataModelDecl = checkNotNull(document.dataModels[id])
            check("Data model", model.name, DiagnosticLocation(path = listOf("dataModels", model.name)))
        }
        for (id in document.enums.keys.sortedBy { it.value }) {
            val declaration: EnumTypeDecl = checkNotNull(document.enums[id])
            check("Enum", declaration.name, DiagnosticLocation(path = listOf("enums", declaration.name)))
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