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

    // kotlinx-io is wired at P4, scoped to `nonWebMain` above rather than blanketed in
    // advance. 0.9.1 marks the filesystem API unstable in prose only, so there is no
    // opt-in annotation to scope; the source set is the scoping.
}
