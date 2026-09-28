import forge.forgeJvmTarget
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    // :tools:architecture-tests is a library even though it has one consumer, so it gets
    // the same explicit API discipline as the engine modules. Whether a test module has
    // an "API" is a question about intent, not about who calls it.
    explicitApi()

    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(forgeJvmTarget))
    }
}

// One JDK number, applied to both compilers. `jvmTarget` comes from the shared catalog and
// is the same key the Rotalex convention plugins read, so this module and every other
// Rotalex project emit the same bytecode.
//
// There is deliberately no `jvmToolchain` here. A toolchain is a second, independent JDK
// number, and a Java compilation with no explicit target falls back to it — which is how
// this module ended up with 'compileTestJava' at 21 and 'compileTestKotlin' at 17 and
// failed with "Inconsistent JVM-target compatibility". CI pins the JDK that runs Gradle
// through setup-java, which is where that belongs.
extensions.configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.toVersion(forgeJvmTarget)
    targetCompatibility = JavaVersion.toVersion(forgeJvmTarget)
}

// Konsist is a JVM-only tool: it is an IntelliJ PSI consumer, not a compiler plugin. That
// is why the architecture rules live here and in no other module.
//
// The catalog is reached through the `VersionCatalogsExtension` rather than the generated
// `libs` accessors. A precompiled script plugin is compiled separately from the accessor
// generation, so `libs.foo` does not resolve here; `findLibrary("...")` takes the alias as a
// string and is therefore immune to that, and to the dash-to-dot rule that decides what the
// generated accessor would even have been called.
val gapCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val junit = gapCatalog.findLibrary("junit-jupiter").get()
val junitApi = gapCatalog.findLibrary("junit-jupiter-api").get()
val junitEngine = gapCatalog.findLibrary("junit-jupiter-engine").get()
val junitLauncher = gapCatalog.findLibrary("junit-platform-launcher").get()

dependencies {
    testImplementation(kotlin("test"))

    testImplementation(platform(junit))
    testImplementation(junitApi)
    testRuntimeOnly(junitEngine)

    // The launcher is what Gradle uses to talk to the JUnit Platform. It is a runtime
    // dependency; adding it to the compile classpath would be a lie about what the tests
    // need.
    testRuntimeOnly(junitLauncher)
}

tasks.withType<Test>().configureEach {
    // JUnit 5, not Kotest: Konsist derives an assertion's test name by reflecting over the
    // enclosing test function, and that only resolves under JUnit.
    useJUnitPlatform()
}
