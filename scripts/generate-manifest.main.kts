#!/usr/bin/env kotlin
//
// Generuje manifest per-OS dla 3-package release flow (v1.2.x+).
//
// Usage:
//   kotlin scripts/generate-manifest.main.kts \
//       <launcherTarGz> <jreTarGz> <autoUpdateBin> \
//       <osSuffix> <version> <autoUpdateVersion> \
//       <outputPath>
//
//   <launcherTarGz>    — path do launcher-<os>.tar.gz (size + sha256 liczony)
//   <jreTarGz>         — path do jre-<os>.tar.gz
//   <autoUpdateBin>    — path do auto-update-<os>.exe (Windows) / auto-update-<os> (Linux)
//   <osSuffix>         — "windows" lub "linux"
//   <version>          — release tag bez prefiksu v (np. "0.4.7")
//   <autoUpdateVersion>— z `auto-update/Cargo.toml [package].version` (np. "1.2.4")
//   <outputPath>       — gdzie zapisać manifest-<os>.json
//
// Output matches `auto-update/src/manifest.rs::Manifest` Serde schema —
// camelCase fields, walidowane Sha256 (64 lowercase hex), ManifestPath
// (no traversal). Zmiany schema MUSZĄ być w sync między Rust struct +
// ten script.
//
// **Asset naming jest IMMUTABLE** (patrz memory rule
// `project_release_asset_naming_immutable`): `launcher-<os>.tar.gz`,
// `jre-<os>.tar.gz`, `auto-update-<os>[.exe]`, `manifest-<os>.json` —
// hardcoded w installer Pascal. Zmiana nazwy = dead installer hash =
// reset SmartScreen reputation.

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.6.3")

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant

// Script NIE używa @Serializable data classes — `.main.kts` CLI runtime
// nie ma kotlinx-serialization compiler plugin loadowanego, więc reflective
// `encodeToString(manifest)` z @Serializable data class wybucha
// `SerializationException: Serializer for class 'Manifest' is not found`
// (verified w CI run #24613106653). `buildJsonObject` DSL działa bez
// plugin — operuje na kotlinx-serialization-json primitives bez potrzeby
// compile-time-generated serializers.

if (args.size < 7) {
    System.err.println(
        """
        Usage: generate-manifest.main.kts <launcherTarGz> <jreTarGz> <autoUpdateBin>
                                          <osSuffix> <version> <autoUpdateVersion>
                                          <outputPath>
        """.trimIndent()
    )
    kotlin.system.exitProcess(1)
}

val launcherTarGz = Paths.get(args[0]).toAbsolutePath()
val jreTarGz = Paths.get(args[1]).toAbsolutePath()
val autoUpdateBin = Paths.get(args[2]).toAbsolutePath()
val osSuffix = args[3]
val version = args[4]
val autoUpdateVersion = args[5]
val outputPath = Paths.get(args[6])

require(Files.isRegularFile(launcherTarGz)) { "launcher.tar.gz missing: $launcherTarGz" }
require(Files.isRegularFile(jreTarGz)) { "jre-$osSuffix.tar.gz missing: $jreTarGz" }
require(Files.isRegularFile(autoUpdateBin)) { "auto-update binary missing: $autoUpdateBin" }
require(osSuffix in setOf("windows", "linux")) { "osSuffix must be 'windows' or 'linux', got: $osSuffix" }
require(version.isNotBlank()) { "version must not be blank" }
require(autoUpdateVersion.isNotBlank()) { "autoUpdateVersion must not be blank" }

val repoBaseUrl = "https://github.com/Echelon-Team/SingularityMC/releases/download/v$version"

/**
 * Streaming SHA-256 dla pliku. 64 KB buffer per web-research-v1 analysis:
 * - tar-rs czyta 512-byte tar frame headers + chunked file body
 * - jvm I/O syscall overhead: 4 KB default = 7680 iteracji per 30 MB,
 *   64 KB = 469 iteracji. CPU bottleneck (SHA compute ~300 MB/s single-core)
 *   dominuje ale buffer size wpływa na syscall overhead.
 * - File.forEachBlock(blockSize=65536) — stdlib helper z auto-close +
 *   deterministic kolejność chunków.
 */
fun sha256(path: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    path.toFile().forEachBlock(blockSize = 64 * 1024) { buffer, bytesRead ->
        md.update(buffer, 0, bytesRead)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

// Launcher executable relative path w install_dir. Windows: jpackage
// produkuje `SingularityMC.exe` w root folderze aplikacji (nie bin/).
// Linux: binary idzie do bin/SingularityMC. Rust auto-update używa tego
// dla `launcher::spawn` po extract.
val launcherExecutable = when (osSuffix) {
    "windows" -> "launcher/SingularityMC.exe"
    "linux" -> "launcher/bin/SingularityMC"
    else -> error("unreachable (require check above)")
}

// Asset names — IMMUTABLE convention per memory rule
// `project_release_asset_naming_immutable`. Hardcoded w installer Pascal
// i bootstrap AppRun shell — zmiana tutaj = zmiana URL w manifest = dead
// linki u wszystkich obecnie zainstalowanych userów.
val launcherAssetName = "launcher-$osSuffix.tar.gz"
val jreAssetName = "jre-$osSuffix.tar.gz"
val autoUpdateAssetName = when (osSuffix) {
    "windows" -> "auto-update-windows.exe"
    "linux" -> "auto-update-linux"
    else -> error("unreachable")
}

// Changelog pulling: env var RELEASE_CHANGELOG (CI wstrzykuje z tag
// annotation / release notes), fallback na generic line.
val changelog = System.getenv("RELEASE_CHANGELOG") ?: "- Update to version $version"

// Min auto-update version który rozumie ten manifest schema. Bump major
// component gdy Rust Manifest struct shape się zmienia w incompatible way
// (np. dodajemy required field). `1.0.0` bo v1.x line wszystko jest
// backward-compatible serde (optional fields z #[serde(default)]).
val minAutoUpdateVersion = "1.0.0"

// Files.size(Path) zwraca non-null Long per web-research. Explicit Long
// declaration wymusza Kotlin type inference do JsonPrimitive::Long (nie
// Number/Any). Bez tego DSL może silently wstawić null dla ambiguous types
// (kotlinx.serialization issue #2651).
val launcherSize: Long = Files.size(launcherTarGz)
val jreSize: Long = Files.size(jreTarGz)
val autoUpdateSize: Long = Files.size(autoUpdateBin)

val manifestJson: JsonObject = buildJsonObject {
    put("version", version)
    put("os", osSuffix)
    put("releasedAt", Instant.now().toString())
    put("minAutoUpdateVersion", minAutoUpdateVersion)
    put("launcherExecutable", launcherExecutable)
    put("changelog", changelog)
    put("launcher", buildJsonObject {
        put("url", "$repoBaseUrl/$launcherAssetName")
        put("sha256", sha256(launcherTarGz))
        put("size", launcherSize)
    })
    put("jre", buildJsonObject {
        put("url", "$repoBaseUrl/$jreAssetName")
        put("sha256", sha256(jreTarGz))
        put("size", jreSize)
    })
    put("autoUpdate", buildJsonObject {
        put("url", "$repoBaseUrl/$autoUpdateAssetName")
        put("sha256", sha256(autoUpdateBin))
        put("size", autoUpdateSize)
        put("version", autoUpdateVersion)
    })
}

val json = Json { prettyPrint = true }
Files.writeString(outputPath, json.encodeToString(JsonObject.serializer(), manifestJson))

println("Wrote manifest to $outputPath")
println("  version=$version  autoUpdate=$autoUpdateVersion  os=$osSuffix")
println("  launcher=$launcherAssetName (${launcherSize} B)")
println("  jre=$jreAssetName (${jreSize} B)")
println("  autoUpdate=$autoUpdateAssetName (${autoUpdateSize} B)")
