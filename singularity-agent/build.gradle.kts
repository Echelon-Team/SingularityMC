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
    implementation(libs.tiny.remapper)
    implementation(libs.mapping.io)

    // JSON serialization dla ModuleDescriptor parsowania (Sub 2a Task 1)
    implementation(libs.serialization.json)

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
