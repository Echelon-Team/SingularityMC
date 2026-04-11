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
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi
            )
            packageName = "SingularityMC"
            packageVersion = "1.0.0"
            windows {
                menuGroup = "SingularityMC"
                shortcut = true
                dirChooser = true
                // iconFile.set(project.file("src/main/resources/icon.ico"))
                // ^ Icon dodawany w Subsystemie 5 (GUI)
            }
        }
    }
}
