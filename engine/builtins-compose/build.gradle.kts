plugins {
    id("forge.kmp.compose")
}

dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(project(":engine:schema"))
    commonMainApi(project(":engine:interpreter"))
    commonMainApi(project(":engine:analysis"))
    commonMainApi(project(":engine:runtime"))
    commonMainApi(project(":engine:builtins"))
    // Implementation: renderers are internal, so no compose type leaks into signatures.
    commonMainImplementation(rootLibs.jetbrains.compose.ui)
    commonMainImplementation(rootLibs.jetbrains.compose.foundation)
    commonMainImplementation(rootLibs.jetbrains.compose.material3)
}
