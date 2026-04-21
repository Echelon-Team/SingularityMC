// Copyright (c) 2026 Echelon Team. All rights reserved.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

kotlin {
    // JDK 17 bo agent musi dzialac na MC 1.20.1 runtime (wymaga Java 17)
    jvmToolchain(17)
}

dependencies {
    implementation(project(":singularity-common"))
    implementation(libs.bundles.logging)

    // Bytecode libraries — aktywowane w Sub 2a
    implementation(libs.bundles.asm)
    implementation(libs.mixin)
    implementation(libs.guava)  // runtime dep Mixin 0.8.7 (ImmutableList w MixinEnvironment)
    implementation(libs.tiny.remapper)
    implementation(libs.mapping.io)
    implementation(libs.jetbrains.annotations)  // wymagane przez mapping-io 0.7.1

    // JSON serialization dla ModuleDescriptor parsowania (Sub 2a Task 1)
    implementation(libs.serialization.json)

    // YAML parsing (TestBot scenarios — Sub 5 Task 7)
    implementation(libs.snakeyaml)

    testImplementation(libs.bundles.testing)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes(
            "Premain-Class" to "com.singularity.agent.AgentMain",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
    // minimize() — wyłączony w szkielecie, włączony w Subsystemie 2
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
