plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // JDK 17 bo bundled JRE w launcher (jpackage) latwiej zbudowac z 17
    // i common jest wspolne z agentem ktory wymaga 17 (MC 1.20.1 runtime)
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(project(":singularity-common"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing) // WYMAGANY dla viewModelScope na Desktop
    implementation(libs.bundles.ktor.client)
    implementation(libs.serialization.json)
    implementation(libs.bundles.logging)
    implementation(libs.apache.commons.compress) // untar.gz dla JavaManager Task 29

    testImplementation(libs.bundles.testing)
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.singularity.launcher.MainKt"
        // Launcher JVM args — 512MB heap wystarczy (Compose Desktop + Ktor HTTP client + JSON parsing).
        // Bez tego Compose Desktop bierze ~25% fizycznego RAM (na 16GB = 4GB co jest absurdalne).
        jvmArgs += listOf("-Xmx512m", "-Xms128m")
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm
            )
            packageName = "SingularityMC"
            packageVersion = "1.0.0"
            description = "Multithreaded Minecraft launcher with cross-loader mod support"
            copyright = "2026 Echelon Team"
            vendor = "Echelon Team"

            modules("java.sql", "jdk.unsupported", "java.net.http")

            windows {
                menuGroup = "SingularityMC"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "18159995-d967-4cd2-8885-77bfa97cfa9f"
                // iconFile.set(project.file("src/main/resources/icon.ico"))
            }

            linux {
                shortcut = true
                packageName = "singularitymc"
                debMaintainer = "team@singularitymc.example"
                menuGroup = "Games"
                appCategory = "Game"
                // iconFile.set(project.file("src/main/resources/icon.png"))
            }

            macOS {
                bundleID = "com.singularitymc.launcher"
                packageName = "SingularityMC"
                dockName = "SingularityMC"
                // iconFile.set(project.file("src/main/resources/icon.icns"))
            }
        }
    }
}
