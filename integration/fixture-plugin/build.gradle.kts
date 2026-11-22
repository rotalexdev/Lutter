import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // A JVM library, like `:tools:cli`: a plugin is loaded by a JVM host from a jar, so the
    // module that holds one must compile to the platform the host runs on.
    alias(rootLibs.plugins.convention.jvm.library)

    // Phase 16 S9's packager, from its own included build. This module is the one that proves it
    // end to end: the task below writes a real container from this module's own jar.
    id("dev.rotalex.lutter.plugin")
}

kotlin {
    // Explicit API mode. A plugin's entry point is found by name at load time, so it has to be a
    // `public` declaration whether or not anything imports it — the compiler cannot see the caller
    // that will reflectively reach it, which is exactly the case explicit visibility is for.
    explicitApi()

    // ABI tracking, for the same reason `:tools:cli` and `:tools:plugin-container` carry it: the
    // packaging plugin and the CLI both link this module, so a change to a spec it exports is a
    // change to a surface someone else builds against.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(rootLibs.versions.jvmTarget.get()))
    }
}

lutterPlugin {
    // Repeated from `FixturePlugin` rather than referenced, because a build script is compiled
    // before the module it configures. The names are not left to trust: `PluginContainerRoundTrip`
    // asserts the container's manifest against the constants, so a rename that misses this block
    // fails a test instead of shipping a container a host cannot load.
    id.set("forge.fixture")
    version.set("1.0.0")
    apiVersion.set(1)
    build.set("desktop-only")
    schemaEntryPoint.set("dev.rotalex.lutter.fixture.plugin.FixturePlugin")
}

dependencies {
    // `ModuleGraphRules.ALLOWED`'s row for this module, and no other: the spec vocabulary it
    // contributes and the manifest's identity. No `:engine:analysis`, no `:engine:codegen` — a
    // plugin contributes to a schema, it does not read one.
    implementation(project(":engine:model"))
    implementation(project(":engine:schema"))

    // The container this module's own build writes is read back with the real reader: the
    // packager lives in an included build and cannot link `:tools:plugin-container`, so this edge
    // is what keeps the two implementations of the format honest.
    testImplementation(project(":tools:plugin-container"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.jupiter))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // `PluginContainerRoundTrip` reads what `lutterPluginContainer` wrote, so the task has to have
    // run and the test has to know where the file is. Both are wired here rather than discovered:
    // a test that guessed a path would silently pass on a stale container.
    dependsOn("lutterPluginContainer")
    val container = layout.buildDirectory.file("lutter-plugin/forge.fixture-1.0.0.lutterplugin")
    inputs.file(container).withPropertyName("pluginContainer").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("lutter.plugin.container", container.get().asFile.absolutePath)
}
