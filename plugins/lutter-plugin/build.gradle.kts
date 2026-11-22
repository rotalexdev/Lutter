plugins {
    // `kotlin-dsl` brings the Kotlin compiler and the language level the rest of the build scripts
    // use. It does not register plugins declared in a `gradlePlugin {}` block, so the plugin is
    // applied explicitly beside it.
    `kotlin-dsl`

    // Applied explicitly: `kotlin-dsl` brings Kotlin and precompiled-script support, but a plugin
    // registered through a `gradlePlugin {}` block — and the `pluginUnderTestMetadata` task that
    // lets a TestKit fixture apply it by id — is `java-gradle-plugin`'s. Applying it twice would be
    // harmless; not applying it would leave the fixture below unable to resolve the plugin by id.
    `java-gradle-plugin`
}

group = "dev.rotalex.lutter"

gradlePlugin {
    plugins {
        create("lutterPlugin") {
            // The id a plugin author writes in their own build:
            //     plugins { id("dev.rotalex.lutter.plugin") }
            id = "dev.rotalex.lutter.plugin"
            implementationClass = "dev.rotalex.lutter.gradle.LutterPluginGradlePlugin"
            displayName = "Lutter plugin packager"
            description = "Packages a JVM plugin payload and its manifest into a .lutterplugin container."
        }
    }
}

dependencies {
    // The JSON runtime only — no `@Serializable` classes and no compiler plugin. The manifest is
    // assembled as a `JsonObject`, which needs the encoder and nothing else, and keeping the
    // compiler plugin out of an included build keeps this build's Kotlin setup identical to
    // `build-logic`'s.
    implementation(rootLibs.kotlinx.serialization.json)

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.jupiter))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
