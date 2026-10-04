package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.schema.component.PlatformTag

/** Which layout the file plan follows; only the standard one exists. */
public enum class GeneratedLayout {
    Standard,
}

/** Which navigation the host runs; only the trivial one exists, strategies defer. */
public sealed interface NavigationStrategy {
    public data object SimpleBackStack : NavigationStrategy
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
    public val navigation: NavigationStrategy = NavigationStrategy.SimpleBackStack,
    public val state: StateStrategy = ComposeSnapshotState,
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
 * The kind is stated rather than inferred from which id is null: `App.kt` and
 * `state/AppState.kt` both carry no id, so an id-only plan cannot tell them apart and the
 * second one emitted a second copy of the first.
 */
public enum class PlannedFileKind {
    App,
    AppState,
    Screen,
    Component,
}

/** One file the generator will emit: its path, package and source page or component. */
public data class PlannedFile(
    public val path: String,
    public val packageName: String,
    public val kind: PlannedFileKind,
    public val pageId: PageId?,
    public val componentId: ComponentDeclId?,
)

/** The file list: every page becomes a screen, every component a function file. */
public data class GenPlan(
    public val basePackage: String,
    public val files: List<PlannedFile>,
)

/** §16.5's paths: `App.kt`, one screen per page, one component file each, the app state holder. */
public fun planDocument(document: ResolvedDocument, basePackage: String): GenPlan {
    val files: MutableList<PlannedFile> = mutableListOf()
    files += PlannedFile("App.kt", basePackage, PlannedFileKind.App, null, null)
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
    val pages = document.pages.entries.sortedBy { it.key.value }
    for ((id, page) in pages) {
        val name: String = page.name + "Screen"
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
