plugins {
    id("forge.kmp.compose")
}

dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(project(":engine:schema"))
    commonMainApi(project(":engine:interpreter"))
    commonMainApi(project(":engine:analysis"))
    // Api, not implementation: Modifier, Rect, Color and TextStyle appear in public
    // signatures, so consumers need them on the compile classpath.
    commonMainApi(rootLibs.jetbrains.compose.ui)
    // Body-only: the screen root container. Nothing public names foundation types.
    commonMainImplementation(rootLibs.jetbrains.compose.foundation)
}
