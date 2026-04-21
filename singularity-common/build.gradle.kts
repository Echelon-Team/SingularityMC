// Copyright (c) 2026 Echelon Team. All rights reserved.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

kotlin {
    // JDK 17 bo agent musi dzialac na MC 1.20.1 runtime (wymaga Java 17)
    // Daemon Gradle nadal moze byc na JDK 21 (org.gradle.java.home w ~/.gradle/gradle.properties)
    jvmToolchain(17)
}

dependencies {
    implementation(libs.serialization.json)

    testImplementation(libs.bundles.testing)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.singularity"
            artifactId = "singularity-common"
            version = project.version.toString()
            from(components["java"])
        }
    }
}
