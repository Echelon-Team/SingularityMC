// Copyright (c) 2026 Echelon Team. All rights reserved.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        mavenLocal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://repo.spongepowered.org/maven/")
        maven("https://maven.fabricmc.net/")
    }
}

rootProject.name = "SingularityMC"

include(":singularity-common")
include(":singularity-agent")
include(":singularity-launcher")
