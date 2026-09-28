plugins {
    id("forge.jvm.tool")
}

application {
    mainClass.set("dev.rotalex.lutter.cli.MainKt")
}

dependencies {
    implementation(project(":engine:model"))
    implementation(project(":engine:serialization"))
    implementation(project(":engine:analysis"))
    implementation(project(":engine:codegen"))
    implementation(project(":engine:builtins"))
}
