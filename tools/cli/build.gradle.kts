plugins {
    // The shared Rotalex JVM convention, plus the standard `application` plugin for the
    // runnable entry point. The shared catalog has no `jvm.application` convention, and
    // inventing a local one is what this branch stopped doing.
    alias(rootLibs.plugins.convention.jvm.library)
    application
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
