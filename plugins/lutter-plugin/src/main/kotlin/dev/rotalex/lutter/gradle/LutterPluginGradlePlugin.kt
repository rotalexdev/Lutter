package dev.rotalex.lutter.gradle

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
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
 * ### Why the listeners are anonymous `Action` objects
 *
 * Both the Gradle API and the Kotlin DSL define `Action` helpers, and a trailing lambda inside a
 * plain Kotlin plugin class resolves against whichever the compiler reaches first — in this build,
 * the wrong one, with the receiver shape the other expected. Naming the interface and implementing
 * `execute` says exactly which call is meant, keeps the receiver explicit, and costs three short
 * object expressions.
 */
public class LutterPluginGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("lutterPlugin", LutterPluginExtension::class.java)

        project.plugins.withType(
            JavaPlugin::class.java,
            object : Action<JavaPlugin> {
                override fun execute(plugin: JavaPlugin) {
                    // The payload is this module's own jar. Wiring it here rather than asking a
                    // build script for it keeps a plugin project's script to its identity; a
                    // project that assembles its jar by hand sets `payload` instead, and the task
                    // still refuses anything but exactly one file.
                    project.tasks.named("jar", Jar::class.java).configure(
                        object : Action<Jar> {
                            override fun execute(jar: Jar) {
                                extension.payload.from(jar.archiveFile)
                                // The container must run this jar first. `dependsOn` a
                                // `FileCollection` did not produce the edge in practice (PR
                                // #195: the container ran alone and found zero payload files),
                                // so the task is named directly — lazily, because the callback
                                // runs before the container task is registered.
                                project.tasks
                                    .withType(LutterPluginContainer::class.java)
                                    .configureEach(
                                        object : Action<LutterPluginContainer> {
                                            override fun execute(container: LutterPluginContainer) {
                                                container.dependsOn(jar)
                                            }
                                        },
                                    )
                            }
                        },
                    )

                    // `assemble` is the task a build already runs for a plugin project, so the
                    // container is what a plain `assemble` produces rather than a task a CI
                    // configuration has to remember to name.
                    project.tasks.named("assemble").configure(
                        object : Action<Task> {
                            override fun execute(assemble: Task) {
                                assemble.dependsOn("lutterPluginContainer")
                            }
                        },
                    )
                }
            },
        )

        project.tasks.register(
            "lutterPluginContainer",
            LutterPluginContainer::class.java,
            object : Action<LutterPluginContainer> {
                override fun execute(task: LutterPluginContainer) {
                    task.group = "lutter"
                    task.description =
                        "Packages the plugin payload and its manifest into a .lutterplugin container."
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
                    task.outputFile.set(
                        extension.outputFile.orElse(project.layout.buildDirectory.file(defaultPath)),
                    )
                }
            },
        )
    }
}
