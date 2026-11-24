plugins {
    id("lutter.kmp.library")
}

dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(project(":engine:schema"))
    commonMainApi(project(":engine:interpreter"))
}
