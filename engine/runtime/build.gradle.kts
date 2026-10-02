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
    // Api for the same reason as ui above: `ScopeHandle` carries a RowScope, ColumnScope or
    // BoxScope so a modifier applier can apply against the receiver a renderer opened.
    commonMainApi(rootLibs.jetbrains.compose.foundation)
}
