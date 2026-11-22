package dev.rotalex.lutter.gradle

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
 */
public class LutterPluginGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("lutterPlugin", LutterPluginExtension::class.java)

        project.plugins.withType(JavaPlugin::class.java) {
            project.tasks.named("jar", Jar::class.java) { jar ->
                extension.payload.from(jar.archiveFile)
            }
            project.tasks.named("assemble").configure { it.dependsOn("lutterPluginContainer") }
        }

        val container = project.tasks.register("lutterPluginContainer", LutterPluginContainer::class.java) { task ->
            task.group = "lutter"
            task.description = "Packages the plugin payload and its manifest into a .lutterplugin container."
            task.pluginId.set(extension.id)
            task.pluginVersion.set(extension.version)
            task.apiVersion.set(extension.apiVersion)
            task.build.set(extension.build)
            task.schemaEntryPoint.set(extension.schemaEntryPoint)
            task.runtimeEntryPoint.set(extension.runtimeEntryPoint)
            task.requirements.set(extension.dependsOn)
            task.payload.from(extension.payload)
            val defaultPath = project.provider {
                "lutter-plugin/${extension.id.get()}-${extension.version.get()}.lutterplugin"
            }
            task.outputFile.set(extension.outputFile.orElse(project.layout.buildDirectory.file(defaultPath)))
        }
    }
}
