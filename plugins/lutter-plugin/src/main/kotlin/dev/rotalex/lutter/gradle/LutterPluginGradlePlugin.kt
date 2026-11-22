package dev.rotalex.lutter.gradle

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.bundling.Jar

/**
 * `dev.rotalex.lutter.plugin`: one task, `lutterPluginContainer` (ADR-018; phase 16 S9).
 *
 * The plugin is deliberately small. It registers the [LutterPluginExtension] a build script fills
 * in, and one [LutterPluginContainer] task that turns it into a `.lutterplugin` file. Everything
 * about *what* a container is lives in the task; everything about *whether a host may load it*
 * lives in the engine's manifest model, which this build cannot see and therefore does not
 * duplicate.
 *
 * When the `java` plugin is applied — the case for every plugin project — the `jar` task's archive
 * becomes the default payload, so a build script states its identity and nothing else. A project
 * that assembles its jar some other way sets `payload` itself.
 *
 * The configuration here is written with explicit [Action] instances rather than with trailing
 * lambdas: the Kotlin DSL offers a receiver-lambda overload of `named`/`register` beside Gradle's
 * own `Action` one, and inside a plain Kotlin plugin class the trailing form resolves to the wrong
 * candidate. An `Action` says which method is meant without leaning on overload resolution.
 */
public class LutterPluginGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("lutterPlugin", LutterPluginExtension::class.java)

        project.plugins.withType(
            JavaPlugin::class.java,
            Action {
                project.tasks.named("jar", Jar::class.java).configure(
                    Action { jar -> extension.payload.from(jar.archiveFile) },
                )
                // `assemble` is the task a build already runs for a plugin project, so the
                // container is what a plain `assemble` produces rather than a task a CI
                // configuration has to remember to name.
                project.tasks.named("assemble").configure(
                    Action { assemble -> assemble.dependsOn("lutterPluginContainer") },
                )
            },
        )

        project.tasks.register(
            "lutterPluginContainer",
            LutterPluginContainer::class.java,
            Action { task ->
                task.group = "lutter"
                task.description = "Packages the plugin payload and its manifest into a .lutterplugin container."
                task.pluginId.set(extension.id)
                task.pluginVersion.set(extension.version)
                task.apiVersion.set(extension.apiVersion)
                task.build.set(extension.pluginBuild)
                task.schemaEntryPoint.set(extension.schemaEntryPoint)
                task.runtimeEntryPoint.set(extension.runtimeEntryPoint)
                task.requirements.set(extension.dependsOn)
                task.payload.from(extension.payload)
                val defaultPath = project.provider {
                    "lutter-plugin/${extension.id.get()}-${extension.version.get()}.lutterplugin"
                }
                task.outputFile.set(extension.outputFile.orElse(project.layout.buildDirectory.file(defaultPath)))
            },
        )
    }
}
