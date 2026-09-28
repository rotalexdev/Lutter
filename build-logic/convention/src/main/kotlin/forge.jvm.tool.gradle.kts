import forge.forgeJvmTarget
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
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

// No explicitApi() here, deliberately. This convention builds applications, and
// `explicitApi()` is a statement about the stability promise a library makes to consumers.
// An executable has no consumers that link against it, so requiring explicit visibility on
// every declaration would be ceremony with no information behind it. See
// `forge.jvm.library` for the JVM library counterpart that does opt in.

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
