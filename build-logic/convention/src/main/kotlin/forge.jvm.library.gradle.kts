import forge.forgeJvmTarget
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.compile.JavaCompile
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

// One JDK number, applied to both compilers. `jvmTarget` comes from gradle.properties, the
// same value the Rotalex convention plugins read from the shared catalog, so this module and
// every other Rotalex project emit the same bytecode.
//
// It is set on the *tasks*, not only on the java extension. Setting
// `sourceCompatibility`/`targetCompatibility` on the extension is not enough: the Kotlin
// plugin's target validation then compares a Java task still defaulting to the running JDK
// against Kotlin's 17, and the build fails with
//
//   Inconsistent JVM-target compatibility detected for tasks
//   'compileTestJava' (21) and 'compileTestKotlin' (17)
//
// Forcing it on every JavaCompile removes the default that loses the argument, and it does
// so without introducing a second JDK number: there is no jvmToolchain here, and CI pins
// the JDK that runs Gradle through setup-java, which is where that belongs.
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = forgeJvmTarget
    targetCompatibility = forgeJvmTarget
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
