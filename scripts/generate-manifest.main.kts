#!/usr/bin/env kotlin
//
// Generuje manifest per-OS dla Phase 3 release flow.
//
// Usage:
//   kotlin scripts/generate-manifest.main.kts <launcherDir> <osSuffix> <version> <outputPath>
//
//   <launcherDir>  — ścieżka do unpacked Compose Desktop output,
//                    np. `singularity-launcher/build/compose/binaries/main/app/SingularityMC`
//   <osSuffix>     — `windows` lub `linux`
//   <version>      — semver bez prefiksu `v`, np. `1.2.3`
//   <outputPath>   — gdzie zapisać manifest.json
//
// Output matches `auto-update/src/manifest.rs::Manifest` Serde schema —
// camelCase fields, zwalidowany ManifestPath (no traversal, forward slashes).
// Zmiany schema MUSZĄ być w sync między Rust struct + ten script + Phase 1
// launcher news feed parser.
//
// Wołane z `.github/workflows/release.yml` (Task 3.3) dla każdego OS
// w matrix. Wynikowy JSON + każdy tracked plik ląduje w GitHub Release
// assets pod URL `https://github.com/Echelon-Team/SingularityMC/releases/download/v$VERSION/<filename>`.

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.6.3")

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant
import kotlin.streams.toList

// Script NIE używa @Serializable data classes — `.main.kts` CLI runtime
// nie ma kotlinx-serialization compiler plugin loadowanego, więc reflective
// `encodeToString(manifest)` z @Serializable data class wybucha
// `SerializationException: Serializer for class 'Manifest' is not found`
// (verified w CI run #24613106653). `buildJsonObject` + `buildJsonArray`
// DSL działa bez plugin — operuje na kotlinx-serialization-json primitives
// bez potrzeby compile-time-generated serializers.

if (args.size < 4) {
    System.err.println("Usage: generate-manifest.main.kts <launcherDir> <osSuffix> <version> <outputPath>")
    System.err.println("Example: generate-manifest.main.kts singularity-launcher/build/compose/binaries/main/app/SingularityMC windows 1.2.3 manifest-windows.json")
    kotlin.system.exitProcess(1)
}

val launcherDir = Paths.get(args[0]).toAbsolutePath().normalize()
val osSuffix = args[1]
val version = args[2]
val outputPath = Paths.get(args[3])

require(Files.isDirectory(launcherDir)) { "launcherDir nie istnieje lub nie jest katalogiem: $launcherDir" }
require(osSuffix in setOf("windows", "linux")) { "osSuffix musi być 'windows' lub 'linux', dostał: $osSuffix" }
require(version.isNotBlank()) { "version nie może być pusty" }

// Base URL dla GitHub Release assets. Rezerwowany prefix — rzeczywiste
// pliki uploadowane są z prefiksami `$osSuffix-` dla OS-specific,
// surowymi nazwami dla shared.
val repoBaseUrl = "https://github.com/Echelon-Team/SingularityMC/releases/download/v$version"

// Plain data carriers — bez @Serializable. Konwersja do JsonObject
// odbywa się w `fileEntry` / bloku budowania manifestu niżej.
data class FileEntry(
    val path: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

fun sha256(path: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

// OS-specific vs shared classification. Shared pliki (w `/app/`) są
// cross-platform JARs — jeden asset w GitHub Release starczy dla obu
// OSach. OS-specific (executables, `/runtime/` = bundled JRE) dostają
// `$osSuffix-` prefix w nazwie assetu, osobne per OS.
fun isOsSpecific(relPath: String): Boolean {
    return relPath.endsWith(".exe") ||
        relPath.contains("/runtime/") ||
        !relPath.endsWith(".jar") && !relPath.endsWith(".cfg") && !relPath.contains("/app/")
}

fun fileEntry(p: Path): FileEntry {
    // Rel path starts `launcher/...` (launcherDir.parent = `app`,
    // launcherDir = `app/SingularityMC`; relativize z parent daje
    // "SingularityMC/..." po czym przepisujemy na "launcher/..." niżej).
    val parent = launcherDir.parent
        ?: error("launcherDir nie ma parent — podaj pełną ścieżkę do app/<appName>")
    val relFromParent = parent.relativize(p).toString().replace('\\', '/')
    // Zamień pierwszą komponentę na `launcher` — auto-update state
    // machine zakłada install_dir/launcher/ dla plików launcher-a.
    val relPath = relFromParent.replaceFirst(Regex("^[^/]+"), "launcher")
    val fileName = p.fileName.toString()
    val assetName = if (isOsSpecific(relPath)) "$osSuffix-$fileName" else fileName
    return FileEntry(
        path = relPath,
        url = "$repoBaseUrl/$assetName",
        size = Files.size(p),
        sha256 = sha256(p),
    )
}

val files = Files.walk(launcherDir).use { stream ->
    stream.filter { Files.isRegularFile(it) }.toList()
}.map { fileEntry(it) }

val launcherExecutable = when (osSuffix) {
    "windows" -> "launcher/SingularityMC.exe"
    "linux" -> "launcher/bin/SingularityMC"  // Compose Desktop umieszcza Linux binary w bin/
    else -> error("Unknown osSuffix: $osSuffix")
}

// Changelog pulling: env var RELEASE_CHANGELOG (CI wstrzykuje z tag
// annotation / release notes), fallback na generic line.
val changelog = System.getenv("RELEASE_CHANGELOG") ?: "- Update to version $version"

// Bezpośrednio buduj JsonObject reprezentujący manifest. Pola zachowują
// camelCase match ze schematem `auto-update/src/manifest.rs::Manifest`
// (`#[serde(rename_all = "camelCase")]`). Kolejność pól wynikowego JSON
// ma być identyczna jak w Rust struct: version, os, releasedAt,
// minAutoUpdateVersion, launcherExecutable, changelog, files.
val manifestJson: JsonObject = buildJsonObject {
    put("version", version)
    put("os", osSuffix)
    put("releasedAt", Instant.now().toString())
    // Min auto-update version który potrafi zainstalować ten manifest.
    // Wartość MUSI matchować `auto-update/Cargo.toml::[package].version`
    // dla aktualnie wydawanych builds — inaczej freshly-built
    // auto-update binary zobaczy remote manifest jako "too old" i
    // wpadnie w FatalError na każdym userze. Bump oba w tym samym
    // commit.
    put("minAutoUpdateVersion", "1.0.0")
    put("launcherExecutable", launcherExecutable)
    put("changelog", changelog)
    put("files", buildJsonArray {
        files.forEach { e ->
            add(buildJsonObject {
                put("path", e.path)
                put("url", e.url)
                put("size", e.size)
                put("sha256", e.sha256)
            })
        }
    })
}

val json = Json { prettyPrint = true }
Files.writeString(outputPath, json.encodeToString(JsonObject.serializer(), manifestJson))

println("Wrote manifest to $outputPath (${files.size} files)")
