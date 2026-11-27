package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PlatformTag

/** The package §16.5 files `model/Models.kt` under: one level below the app's own. */
internal fun modelPackage(basePackage: String): String = basePackage + ".model"

/**
 * Every declared type as the symbol it is emitted under, keyed by the `TypeId` a `TypeRef` names
 * it by.
 *
 * `DataModelId` and `TypeId` are two value classes over one value space (§5.5:366), so the key is
 * the string they share; the enum already uses `TypeId` directly. The symbol rather than the bare
 * name is what lets a file outside the model package import it.
 */
internal fun declaredTypeSymbols(document: ResolvedDocument, packageName: String): Map<TypeId, KotlinSymbol> {
    val symbols: MutableMap<TypeId, KotlinSymbol> = LinkedHashMap()
    for (declaration in document.dataModels.values) {
        symbols[TypeId(declaration.id.value)] = KotlinSymbol(packageName, declaration.name)
    }
    for (declaration in document.enums.values) {
        symbols[declaration.id] = KotlinSymbol(packageName, declaration.name)
    }
    return symbols
}

/** Which layout the file plan follows; only the standard one exists. */
public enum class GeneratedLayout {
    Standard,
}

/** Which header the files carry; minimal keeps output stable across runs. */
public enum class HeaderPolicy {
    Minimal,
}

/** Printer tuning: indent and the width that decides wrapping. */
public data class FormattingOptions(
    public val indentWidth: Int = 4,
    public val shortArgWidth: Int = 60,
    public val lineWidth: Int = 100,
)

/** Generator inputs, PLAN §16.8 field for field at skeleton scope. */
public data class CodegenOptions(
    public val basePackage: String,
    public val layout: GeneratedLayout = GeneratedLayout.Standard,
    // The default names the package above because `emitNavigateCall` is handed no file to read
    // it from, and a navigator that cannot say where `Route` lives cannot name it in a screen.
    public val navigation: NavigationStrategy = SimpleBackStack(basePackage),
    public val state: StateStrategy = ComposeSnapshotState,
    /**
     * Who builds a page's state holder. Defaults to [NoInjection], which is whatever [state] says
     * — so a run that selects nothing emits the bytes it emitted before this option existed.
     */
    public val injection: InjectionStrategy = NoInjection,
    public val resources: ResourceStrategy = ResourceStrategy.None,
    public val header: HeaderPolicy = HeaderPolicy.Minimal,
    public val formatting: FormattingOptions = FormattingOptions(),
    public val targetPlatforms: Set<PlatformTag> = PlatformTag.ALL,
)

/** Which imports the printer writes: everything outside the defaults, aliased on clash. */
public data class ImportPolicy(
    public val defaultPackages: Set<String> = DefaultPackages,
) {
    public companion object {
        /** Kotlin's own default imports: always in scope, never written. */
        public val DefaultPackages: Set<String> = setOf(
            "kotlin",
            "kotlin.annotation",
            "kotlin.collections",
            "kotlin.comparisons",
            "kotlin.io",
            "kotlin.ranges",
            "kotlin.sequences",
            "kotlin.text",
        )
    }
}

/**
 * Which emitter owns a planned file.
 *
 * The kind is stated rather than inferred from which id is null: `App.kt`, `Navigation.kt`,
 * `AppHost.kt` and `state/AppState.kt` all carry no id, so an id-only plan cannot tell them
 * apart and the second one emitted a second copy of the first.
 */
public enum class PlannedFileKind {
    App,
    Navigation,
    AppHost,
    AppState,
    Store,
    Screen,
    Component,

    /** `di/Injection.kt`: what a container needs declared before a screen's lookup finds it. */
    Injection,

    /** `model/Models.kt`: the document's own object and enum types, §16.5 and §16.6. */
    Model,
}

/** One file the generator will emit: its path, package and source page or component. */
public data class PlannedFile(
    public val path: String,
    public val packageName: String,
    public val kind: PlannedFileKind,
    public val pageId: PageId?,
    public val componentId: ComponentDeclId?,
)

/**
 * The package `di/Injection.kt` lands in, named once because two files need it: the plan places
 * the file, and `App.kt` names the module the file declares.
 */
public fun injectionPackage(basePackage: String): String = basePackage + ".di"

/** The file list: every page becomes a screen, every component a function file. */
public data class GenPlan(
    public val basePackage: String,
    public val files: List<PlannedFile>,
)

/**
 * §16.5's paths: `App.kt`, `Navigation.kt`, `AppHost.kt`, one screen per page, one component file
 * each, and the app state holder.
 *
 * `Navigation.kt` and `AppHost.kt` are planned whatever the document declares, because both are
 * what a handler's call needs to exist: a `nav.navigate` names `Route`, a `ui.showSnackbar` names
 * `SnackbarHost`, and an empty interface is the cheapest of the three that compiles.
 */
public fun planDocument(
    document: ResolvedDocument,
    basePackage: String,
    injection: InjectionStrategy = NoInjection,
): GenPlan {
    val files: MutableList<PlannedFile> = mutableListOf()
    files += PlannedFile("App.kt", basePackage, PlannedFileKind.App, null, null)
    files += PlannedFile("Navigation.kt", basePackage, PlannedFileKind.Navigation, null, null)
    files += PlannedFile("AppHost.kt", basePackage, PlannedFileKind.AppHost, null, null)
    // The app-state holder is a file of its own only when something declares app state: an
    // empty class and a local nobody reads would compile and prove nothing.
    if (document.appState.isNotEmpty()) {
        files += PlannedFile(
            "state/" + StateEmitter.AppStateName + ".kt",
            basePackage + ".state",
            PlannedFileKind.AppState,
            null,
            null,
        )
    }
    // §16.5's `model/Models.kt` exists only when there is a declaration to put in it: a file
    // with no data class would be a package nobody imports and a golden that proves nothing.
    if (document.dataModels.isNotEmpty() || document.enums.isNotEmpty()) {
        files += PlannedFile(
            "model/Models.kt",
            modelPackage(basePackage),
            PlannedFileKind.Model,
            null,
            null,
        )
    }
    // A container's bindings are a file of their own, and only a container has any: the plan asks
    // the strategy rather than guessing, so a run that selected nothing plans nothing new.
    if (injection.emitsModule && document.pages.isNotEmpty()) {
        files += PlannedFile(
            "di/Injection.kt",
            injectionPackage(basePackage),
            PlannedFileKind.Injection,
            null,
            null,
        )
    }
    // §12.1's persistent row is the only document fact that asks for a store, so a document
    // without one plans no file and emits no local.
    if (StateEmitter.savesAnything(document)) {
        files += PlannedFile(
            "state/" + StateEmitter.PersistentStoreName + ".kt",
            basePackage + ".state",
            PlannedFileKind.Store,
            null,
            null,
        )
    }
    val pages = document.pages.entries.sortedBy { it.key.value }
    for ((id, page) in pages) {
        val name: String = page.name + ReservedCodegenNames.ScreenSuffix
        files += PlannedFile(
            "screens/" + name + ".kt",
            basePackage + ".screens",
            PlannedFileKind.Screen,
            id,
            null,
        )
    }
    val components = document.components.entries.sortedBy { it.key.value }
    for ((id, _) in components) {
        files += PlannedFile(
            "components/" + id.value + ".kt",
            basePackage + ".components",
            PlannedFileKind.Component,
            null,
            id,
        )
    }
    return GenPlan(basePackage, files.sortedBy { it.path })
}
