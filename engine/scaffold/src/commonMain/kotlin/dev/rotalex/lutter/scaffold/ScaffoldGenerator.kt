package dev.rotalex.lutter.scaffold

import dev.rotalex.lutter.codegen.GeneratedFile
import dev.rotalex.lutter.codegen.GeneratedFiles
import dev.rotalex.lutter.schema.component.PlatformTag

/**
 * §33's other half of generation: a project, from a [ProjectSpec].
 *
 * `:engine:codegen` answers "what Kotlin does this document mean"; this answers "what Gradle
 * project is that Kotlin compiled in". §32 Phase 14 keeps them separate on purpose, and the
 * separation is the reason both are testable: a scaffold mistake produces a project with the
 * wrong modules — which no amount of correct Kotlin repairs — and a codegen mistake produces
 * quarrelling Kotlin in a project that is fine.
 *
 * ### The shape is D28's, not the JetBrains default
 *
 * `androidApp/`, `desktopApp/` and `shared/`. Not `composeApp/`: AGP 9 refuses
 * `com.android.application` beside `kotlin.multiplatform` in one module, so the per-platform
 * app module is not a preference — it is the only shape that compiles. `shared/` keeps
 * JetBrains' name because a generated project *is* a generated app, which is exactly the role
 * this repository does not have (§22's module list says so in as many words).
 *
 * ### AGP 9 decides the wiring, and the two module classes differ
 *
 * An app module applies `com.android.application` **alone**: AGP 9 has built-in Kotlin and
 * refuses `org.jetbrains.kotlin.android` alongside it. A KMP library needs both
 * `org.jetbrains.kotlin.multiplatform` and `com.android.kotlin.multiplatform.library` — the
 * pairing this repository itself uses — because built-in Kotlin replaces the `kotlin-android`
 * plugin only.
 *
 * ### Emitted, not resolved
 *
 * Every version comes from [ScaffoldVersions], which the build generates from the catalog it
 * resolves against. A number written here would be a second source of truth that goes stale on
 * the first upgrade, and a generated app that pins a version this engine never built against is
 * a bug report waiting for the person who tries it.
 *
 * Ios and Web targets are not emitted. §32 Phase 17 gates them on a platform-scope ADR, so a
 * spec that lists them gets the two runners this build can actually produce rather than a
 * module nothing here can compile.
 */
public object ScaffoldGenerator {

    /** The module holding the generated UI and the runtime wiring. */
    public const val SHARED_MODULE: String = "shared"

    /** The Android entry point, present when the spec targets Android. */
    public const val ANDROID_APP_MODULE: String = "androidApp"

    /** The desktop entry point, present when the spec targets Desktop. */
    public const val DESKTOP_APP_MODULE: String = "desktopApp"

    /**
     * [spec] as a project, with [codegen]'s files placed in the shared module.
     *
     * Pure and total for a well-formed spec: the same arguments produce byte-identical
     * `GeneratedFiles`, which is what §32's "regeneration is byte-stable" asks. Nothing here
     * reads the clock, the environment or a map whose iteration order is unspecified.
     *
     * @param spec the project to generate. Its `appName` must be usable as a Gradle project
     *   name and its `basePackage` as a Kotlin package; both are validated, because a scaffold
     *   that emits a project nobody can open is worse than one that refuses.
     * @param codegen the UI a `UiDocument` generated, placed under
     *   `shared/src/commonMain/kotlin/` with its own relative path.
     */
    public fun generate(
        spec: ProjectSpec,
        codegen: GeneratedFiles = GeneratedFiles(emptyList()),
    ): GeneratedFiles {
        requireAppName(spec.appName)
        requirePackage(spec.basePackage)

        val android: Boolean = PlatformTag.Android in spec.targetPlatforms
        val desktop: Boolean = PlatformTag.Desktop in spec.targetPlatforms
        val packageDirectory: String = spec.basePackage.replace('.', '/')

        val files = mutableListOf<GeneratedFile>()
        files += GeneratedFile("settings.gradle.kts", settings(spec, android, desktop))
        files += GeneratedFile("build.gradle.kts", rootBuild(android, desktop))
        files += GeneratedFile("gradle.properties", GRADLE_PROPERTIES)
        files += GeneratedFile("libs.versions.toml", catalog(android, desktop))
        files += GeneratedFile("$SHARED_MODULE/build.gradle.kts", sharedBuild(spec, android, desktop))

        if (android) {
            files += GeneratedFile("$ANDROID_APP_MODULE/build.gradle.kts", androidAppBuild(spec))
            files += GeneratedFile("$ANDROID_APP_MODULE/src/main/AndroidManifest.xml", manifest(spec))
            files += GeneratedFile(
                "$ANDROID_APP_MODULE/src/main/kotlin/$packageDirectory/androidapp/MainActivity.kt",
                mainActivity(spec),
            )
        }

        if (desktop) {
            files += GeneratedFile("$DESKTOP_APP_MODULE/build.gradle.kts", desktopAppBuild(spec))
            files += GeneratedFile(
                "$DESKTOP_APP_MODULE/src/main/kotlin/$packageDirectory/desktopapp/Main.kt",
                desktopMain(spec),
            )
        }

        codegen.files.forEach { file ->
            files += GeneratedFile("$SHARED_MODULE/src/commonMain/kotlin/${file.path}", file.content)
        }

        return GeneratedFiles(files)
    }

    private const val GRADLE_PROPERTIES: String = """org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g
org.gradle.caching=true
org.gradle.parallel=true

kotlin.code.style=official

# A Compose UI on Android is an AndroidX UI; without this AGP refuses the first dependency
# that resolves to an androidx coordinate.
android.useAndroidX=true
"""

    private fun requireAppName(appName: String) {
        require(appName.isNotBlank()) { "a project needs a name" }
        require(APP_NAME.matches(appName)) {
            "app name '$appName' is not usable as a project name: letters, digits, spaces, '.', '-' and '_' only"
        }
    }

    private fun requirePackage(basePackage: String) {
        require(PACKAGE.matches(basePackage)) {
            "base package '$basePackage' is not a Kotlin package: dot-separated identifiers, at least two"
        }
        require(basePackage.split('.').none { it in RESERVED }) {
            "base package '$basePackage' uses a Kotlin keyword as a segment"
        }
    }

    private val APP_NAME: Regex = Regex("""^[A-Za-z0-9][A-Za-z0-9 ._-]*$""")

    private val PACKAGE: Regex = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$""")

    /** Segments Kotlin refuses unquoted, plus the one Gradle reads as a namespace claim. */
    private val RESERVED: Set<String> = setOf("class", "object", "interface", "fun", "val", "var", "package", "import")

    private fun settings(spec: ProjectSpec, android: Boolean, desktop: Boolean): String = buildString {
        appendLine("// Generated by Lutter. Regenerating this project overwrites this file.")
        appendLine()
        appendLine("pluginManagement {")
        appendLine("    repositories {")
        appendLine("        google()")
        appendLine("        gradlePluginPortal()")
        appendLine("        mavenCentral()")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("dependencyResolutionManagement {")
        appendLine("    repositories {")
        appendLine("        google()")
        appendLine("        mavenCentral()")
        appendLine("    }")
        appendLine("}")
        appendLine()
        // A Gradle project name cannot carry every character an app name can, and the two are
        // the same field on purpose: a project whose root name disagrees with the app is a
        // rename nobody remembers to do twice.
        appendLine("rootProject.name = \"${spec.appName.replace(' ', '-')}\"")
        appendLine()
        appendLine("include(\":$SHARED_MODULE\")")
        if (android) appendLine("include(\":$ANDROID_APP_MODULE\")")
        if (desktop) appendLine("include(\":$DESKTOP_APP_MODULE\")")
    }

    private fun rootBuild(android: Boolean, desktop: Boolean): String = buildString {
        appendLine("// Generated by Lutter. `apply false` keeps one plugin classpath for the build.")
        appendLine()
        appendLine("plugins {")
        if (android) {
            appendLine("    alias(libs.plugins.androidApplication) apply false")
            appendLine("    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false")
        }
        appendLine("    alias(libs.plugins.kotlinMultiplatform) apply false")
        if (desktop) appendLine("    alias(libs.plugins.kotlinJvm) apply false")
        appendLine("    alias(libs.plugins.composeMultiplatform) apply false")
        appendLine("    alias(libs.plugins.composeCompiler) apply false")
        appendLine("}")
    }

    private fun catalog(android: Boolean, desktop: Boolean): String = buildString {
        appendLine("# Generated by Lutter from the catalog this engine builds and tests against.")
        appendLine("# A version here that the engine never resolved is a project nobody has compiled.")
        appendLine()
        appendLine("[versions]")
        appendLine("kotlin = \"${ScaffoldVersions.KOTLIN}\"")
        if (android) appendLine("agp = \"${ScaffoldVersions.AGP}\"")
        appendLine("compose = \"${ScaffoldVersions.COMPOSE}\"")
        appendLine("composeCompiler = \"${ScaffoldVersions.COMPOSE_COMPILER}\"")
        appendLine("jvmTarget = \"${ScaffoldVersions.JVM_TARGET}\"")
        if (android) {
            appendLine("androidCompileSdk = \"${ScaffoldVersions.ANDROID_COMPILE_SDK}\"")
            appendLine("androidMinSdk = \"${ScaffoldVersions.ANDROID_MIN_SDK}\"")
        }
        appendLine()
        appendLine("[plugins]")
        if (android) {
            appendLine(
                "androidApplication = { id = \"com.android.application\", version.ref = \"agp\" }",
            )
            appendLine(
                "androidKotlinMultiplatformLibrary = { id = \"com.android.kotlin.multiplatform.library\", version.ref = \"agp\" }",
            )
        }
        appendLine("kotlinMultiplatform = { id = \"org.jetbrains.kotlin.multiplatform\", version.ref = \"kotlin\" }")
        if (desktop) appendLine("kotlinJvm = { id = \"org.jetbrains.kotlin.jvm\", version.ref = \"kotlin\" }")
        appendLine("composeMultiplatform = { id = \"org.jetbrains.compose\", version.ref = \"compose\" }")
        appendLine("composeCompiler = { id = \"org.jetbrains.kotlin.plugin.compose\", version.ref = \"composeCompiler\" }")
    }

    private fun sharedBuild(spec: ProjectSpec, android: Boolean, desktop: Boolean): String = buildString {
        appendLine("// Generated by Lutter. The platform-neutral half of the app: it holds the generated UI")
        appendLine("// and the runtime that renders it, and the two app modules are only entry points.")
        appendLine()
        appendLine("import org.jetbrains.kotlin.gradle.dsl.JvmTarget")
        appendLine()
        appendLine("plugins {")
        appendLine("    alias(libs.plugins.kotlinMultiplatform)")
        if (android) appendLine("    alias(libs.plugins.androidKotlinMultiplatformLibrary)")
        appendLine("    alias(libs.plugins.composeMultiplatform)")
        appendLine("    alias(libs.plugins.composeCompiler)")
        appendLine("}")
        appendLine()
        appendLine("kotlin {")
        if (android) {
            appendLine("    android {")
            appendLine("        namespace = \"${spec.basePackage}.shared\"")
            appendLine("        compileSdk = libs.versions.androidCompileSdk.get().toInt()")
            appendLine("        minSdk = libs.versions.androidMinSdk.get().toInt()")
            appendLine()
            appendLine("        compilerOptions {")
            appendLine("            jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))")
            appendLine("        }")
            appendLine("    }")
            appendLine()
        }
        if (desktop) {
            appendLine("    jvm(\"desktop\") {")
            appendLine("        compilerOptions {")
            appendLine("            jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))")
            appendLine("        }")
            appendLine("    }")
            appendLine()
        }
        appendLine("    sourceSets {")
        appendLine("        commonMain.dependencies {")
        appendLine("            implementation(compose.runtime)")
        appendLine("            implementation(compose.foundation)")
        appendLine("            implementation(compose.material3)")
        appendLine("        }")
        if (desktop) {
            appendLine()
            appendLine("        val desktopMain by getting {")
            appendLine("            dependencies {")
            appendLine("                implementation(compose.desktop.currentOs)")
            appendLine("            }")
            appendLine("        }")
        }
        appendLine("    }")
        appendLine("}")
    }

    private fun androidAppBuild(spec: ProjectSpec): String = buildString {
        appendLine("// Generated by Lutter. No `org.jetbrains.kotlin.android`: AGP 9 compiles Kotlin itself,")
        appendLine("// and applying the old plugin beside it is a build failure rather than a warning.")
        appendLine("// No `kotlin { compilerOptions { jvmTarget } }` either: built-in Kotlin takes it from")
        appendLine("// `compileOptions.targetCompatibility`, so setting both would be one number twice.")
        appendLine()
        appendLine("plugins {")
        appendLine("    alias(libs.plugins.androidApplication)")
        appendLine("}")
        appendLine()
        appendLine("android {")
        appendLine("    namespace = \"${spec.basePackage}.androidapp\"")
        appendLine("    compileSdk = libs.versions.androidCompileSdk.get().toInt()")
        appendLine()
        appendLine("    defaultConfig {")
        appendLine("        applicationId = \"${spec.basePackage}\"")
        appendLine("        minSdk = libs.versions.androidMinSdk.get().toInt()")
        appendLine("        targetSdk = libs.versions.androidCompileSdk.get().toInt()")
        appendLine("        versionCode = 1")
        appendLine("        versionName = \"1.0\"")
        appendLine("    }")
        appendLine()
        appendLine("    compileOptions {")
        appendLine("        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())")
        appendLine("        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("dependencies {")
        appendLine("    implementation(project(\":$SHARED_MODULE\"))")
        appendLine("}")
    }

    private fun manifest(spec: ProjectSpec): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        appendLine("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">")
        appendLine()
        appendLine("    <application")
        appendLine("        android:label=\"${spec.appName}\"")
        appendLine("        android:theme=\"@android:style/Theme.Material.Light.NoActionBar\">")
        appendLine("        <activity")
        appendLine("            android:name=\"${spec.basePackage}.androidapp.MainActivity\"")
        appendLine("            android:exported=\"true\">")
        appendLine("            <intent-filter>")
        appendLine("                <action android:name=\"android.intent.action.MAIN\" />")
        appendLine("                <category android:name=\"android.intent.category.LAUNCHER\" />")
        appendLine("            </intent-filter>")
        appendLine("        </activity>")
        appendLine("    </application>")
        appendLine()
        appendLine("</manifest>")
    }

    private fun mainActivity(spec: ProjectSpec): String = """package ${spec.basePackage}.androidapp

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * The entry point, and deliberately nothing more.
 *
 * The UI a document means is codegen's `AppRoot`; wiring it here is where the two halves of
 * generation meet, and that is a renderer's job rather than a scaffold's. What this proves is
 * the project itself: the manifest, the module dependency and the Kotlin source set all agree.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "${spec.appName}" })
    }
}
"""

    private fun desktopAppBuild(spec: ProjectSpec): String = buildString {
        appendLine("// Generated by Lutter. A plain JVM application: the window it will show comes from")
        appendLine("// the shared module once the generated UI is wired into `main`.")
        appendLine()
        appendLine("import org.jetbrains.kotlin.gradle.dsl.JvmTarget")
        appendLine()
        appendLine("plugins {")
        appendLine("    alias(libs.plugins.kotlinJvm)")
        appendLine("    application")
        appendLine("}")
        appendLine()
        appendLine("application {")
        appendLine("    mainClass.set(\"${spec.basePackage}.desktopapp.MainKt\")")
        appendLine("}")
        appendLine()
        appendLine("kotlin {")
        appendLine("    compilerOptions {")
        appendLine("        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("dependencies {")
        appendLine("    implementation(project(\":$SHARED_MODULE\"))")
        appendLine("}")
    }

    private fun desktopMain(spec: ProjectSpec): String = """package ${spec.basePackage}.desktopapp

fun main() {
    // The generated UI is in the shared module; a window over it is the renderer's job.
    println("${spec.appName} - generated by Lutter. The shared module carries the UI.")
}
"""
}
