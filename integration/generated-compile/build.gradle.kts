plugins {
    id("forge.kmp.compose")
}

dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(project(":engine:schema"))
    commonMainApi(project(":engine:serialization"))
    commonMainApi(project(":engine:interpreter"))
    commonMainApi(project(":engine:analysis"))
    commonMainApi(project(":engine:editing"))
    commonMainApi(project(":engine:codegen"))
    commonMainApi(project(":engine:runtime"))
    commonMainApi(project(":engine:builtins"))
    commonMainApi(project(":engine:builtins-compose"))
}
