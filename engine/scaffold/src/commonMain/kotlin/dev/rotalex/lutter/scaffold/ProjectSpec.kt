package dev.rotalex.lutter.scaffold

import dev.rotalex.lutter.schema.component.PlatformTag

/**
 * A generated application as a project on disk, rather than as a set of Kotlin files.
 *
 * PLAN §33's post-MVP row pairs the two halves of generation and says which is which:
 * `:engine:codegen` turns a `UiDocument` into Kotlin, and this turns a project into the
 * Gradle structure that Kotlin is compiled in — "project/build-file scaffolding from
 * GeneratedFiles + ProjectSpec". They are separate because they fail differently: a codegen
 * mistake produces quarrelling Kotlin, and a scaffold mistake produces a project with the
 * wrong modules, which no amount of correct Kotlin can repair.
 *
 * Deliberately small, and deliberately not `CodegenOptions`. Everything a scaffold needs is
 * project-level — a name, a package, which runners exist — and everything a generator needs is
 * document-level. Merging them would give the scaffold a navigation strategy it never reads
 * and would make the generator's knobs look like they change project shape.
 *
 * @property appName the application's display name; also the Gradle project name of each app
 *   module, so it must be usable as a directory name.
 * @property basePackage the Kotlin package the generated sources live in, which the modules'
 *   `namespace` and source directories are derived from.
 * @property targetPlatforms the runners this project is generated for — the app modules this
 *   spec emits. A project with only [PlatformTag.Desktop] declares no `androidApp`.
 */
public data class ProjectSpec(
    public val appName: String,
    public val basePackage: String,
    public val targetPlatforms: Set<PlatformTag> = PlatformTag.ALL,
)
