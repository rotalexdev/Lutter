plugins {
    id("forge.kmp.library")
    alias(rootLibs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        // PLAN §21.1/§33.3: Android and Desktop share a filesystem, and a web target would
        // have none. This intermediate set is new: nothing needed storage before P4, so no
        // pattern existed. It stays scoped to the two targets that have a filesystem, so
        // adding one later is a `dependsOn` rather than a move.
        val nonWebMain by creating {
            dependsOn(getByName("commonMain"))
            dependencies {
                // From `rootLibs`: catalog 1.2.7 carries `kotlinx-io-core` 0.9.1.
                implementation(rootLibs.kotlinx.io.core)
            }
        }
        val androidMain by getting { dependsOn(nonWebMain) }
        val desktopMain by getting { dependsOn(nonWebMain) }
        val desktopTest by getting {
            dependencies {
                // `runBlocking` for the storage tests; the production code stays coroutine-free.
                implementation(rootLibs.kotlinx.coroutines.core)
            }
        }
    }
}

// The `rootLibs` accessor generated for this project has no `findLibrary`; the catalog
// object does. Resolving through the extension also keeps the alias a string, so no build
// file depends on how Gradle turns dashes into dots when generating accessors.
dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(rootLibs.kotlinx.serialization.json)

    // The golden harness, and the only reason `ModuleGraphRules.ALLOWED_IN_TESTS` exists.
    // Test scope on purpose: the harness is fixtures and comparison, and `commonTest` is the
    // only place either belongs. PLAN §32 Phase 3's acceptance is "golden JSON files
    // identical on every target", and `CanonicalJsonGoldenTest` is what makes that a fact
    // rather than an intention.
    //
    // Not a cycle in the task graph: this is the same shape as Gradle's `testFixtures`, where
    // a module's tests depend on a helper module that depends on the module's main code.
    // `compileTestKotlin` waits for the helper's jar; the helper's main compilation waits for
    // `compileKotlin`. Nothing waits for itself.
    commonTestImplementation(project(":engine:test-support"))

    // kotlinx-io is wired at P4, scoped to `nonWebMain` above rather than blanketed in
    // advance. 0.9.1 marks the filesystem API unstable in prose only, so there is no
    // opt-in annotation to scope; the source set is the scoping.
}
