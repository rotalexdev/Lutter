// Phase 17 P6: the Android host.
//
// No `org.jetbrains.kotlin.android`: AGP 9 has built-in Kotlin, and applying the old plugin
// beside it is a build failure rather than a warning. No `kotlin { compilerOptions { jvmTarget } }`
// either — built-in Kotlin takes it from `compileOptions.targetCompatibility`, so setting both
// would be one number twice.
plugins {
    alias(rootLibs.plugins.android.application)
    // The panes come from `:app:editor`, which is a Compose Multiplatform module. Using the same
    // plugin here keeps one Compose runtime version in the build: mixing the CMP artifacts with a
    // second `androidx.compose` BOM is how two runtimes end up on one classpath.
    alias(rootLibs.plugins.compose)
    alias(rootLibs.plugins.compose.compiler)
}

android {
    namespace = "dev.rotalex.lutter.androidapp"
    compileSdk = rootLibs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.rotalex.lutter"
        minSdk = rootLibs.versions.androidMinSdk.get().toInt()
        targetSdk = rootLibs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootLibs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(rootLibs.versions.jvmTarget.get())
    }

    // Robolectric reads the module's resources, so the unit tests need them on the host
    // classpath. NIA's modules set exactly this, and it is the difference between a Robolectric
    // test that finds its theme and one that fails on a missing resource.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            // The Compose test artifacts each ship these two licenses; without the exclusion the
            // unit-test classpath has the same file twice and packaging fails.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets.getByName("main") {
        // The document the activity opens is the repository's own fixture, not a copy: the
        // directory is packaged as assets and read by name. A copy is the version that goes
        // stale the first time the fixture changes.
        assets.srcDir("../integration/generated-compile/fixtures-navigating")
    }
}

dependencies {
    implementation(project(":app:editor"))
    implementation(project(":engine:builtins-compose"))
    implementation(project(":engine:runtime"))
    implementation(project(":engine:serialization"))

    implementation(libs.androidx.activity.compose)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material3)

    // Host-side UI tests, the way NowInAndroid's `app` and `core/designsystem` do it: Robolectric
    // boots an Android runtime on the unit runner, so `check` covers the activity's wiring without
    // a device or an emulator. `ui-test-manifest` is debug-only because it exists to provide the
    // `ComponentActivity` the rule launches, and it must not reach a release build.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    // `kotlin-test` is absent from the shared catalog: Kotlin modules get it through
    // `kotlin("test")`, which a plain AGP module has no accessor for. The version is the same
    // Kotlin the build compiles with, so there is still one number.
    testImplementation("org.jetbrains.kotlin:kotlin-test:${rootLibs.versions.kotlin.get()}")
}
