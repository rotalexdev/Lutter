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

/** Which state the screens read; only snapshot state exists. */
public sealed interface StateStrategy {
    public data object ComposeSnapshotState : StateStrategy
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
    public val state: StateStrategy = StateStrategy.ComposeSnapshotState,
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

/** One file the generator will emit: its path, package and source page or component. */
public data class PlannedFile(
    public val path: String,
    public val packageName: String,
    public val pageId: PageId?,
    public val componentId: ComponentDeclId?,
)

/** The file list: every page becomes a screen, every component a function file. */
public data class GenPlan(
    public val basePackage: String,
    public val files: List<PlannedFile>,
)

/** Plans [document] under [basePackage]: `App.kt`, one screen per page, one file per component. */
public fun planDocument(document: ResolvedDocument, basePackage: String): GenPlan {
    val files: MutableList<PlannedFile> = mutableListOf()
    files += PlannedFile("App.kt", basePackage, null, null)
    val pages = document.pages.entries.sortedBy { it.key.value }
    for ((id, page) in pages) {
        val name: String = page.name + "Screen"
        files += PlannedFile("screens/" + name + ".kt", basePackage + ".screens", id, null)
    }
    val components = document.components.entries.sortedBy { it.key.value }
    for ((id, _) in components) {
        files += PlannedFile("components/" + id.value + ".kt", basePackage + ".components", null, id)
    }
    return GenPlan(basePackage, files.sortedBy { it.path })
}
