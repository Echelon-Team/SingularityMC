// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.service.mojang

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Version manifest response z `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`.
 * Lista wszystkich known MC versions (release + snapshot + old_beta + old_alpha).
 */
@Serializable
data class VersionManifest(
    val latest: LatestVersions,
    val versions: List<ManifestVersion>
)

@Serializable
data class LatestVersions(
    val release: String,
    val snapshot: String
)

@Serializable
data class ManifestVersion(
    val id: String,           // np. "1.20.1"
    val type: String,         // "release" | "snapshot" | "old_beta" | "old_alpha"
    val url: String,          // URL do per-version details JSON
    val time: String,
    val releaseTime: String,
    val sha1: String
)

/**
 * Version details z `url` field w ManifestVersion — zawiera asset index + libraries + main class.
 * Full MC version manifest (per-version).
 */
@Serializable
data class VersionDetails(
    val id: String,
    val type: String,
    val mainClass: String,     // np. "net.minecraft.client.main.Main"
    val minimumLauncherVersion: Int = 0,
    val assetIndex: AssetIndexRef,
    val libraries: List<Library>,
    val downloads: VersionDownloads,
    val arguments: Arguments? = null
)

@Serializable
data class AssetIndexRef(
    val id: String,            // np. "1.20" (wersja asset indexu, nie MC version)
    val sha1: String,
    val size: Long,
    val totalSize: Long,
    val url: String
)

@Serializable
data class Library(
    val name: String,          // maven coordinate "group:artifact:version"
    val downloads: LibraryDownloads = LibraryDownloads(),
    val rules: List<Rule>? = null  // OS-specific libraries
)

@Serializable
data class LibraryDownloads(
    val artifact: Artifact? = null,
    val classifiers: Map<String, Artifact>? = null
)

@Serializable
data class Artifact(
    val path: String,          // np. "com/mojang/blocklist/1.0.5/blocklist-1.0.5.jar"
    val sha1: String,
    val size: Long,
    val url: String
)

@Serializable
data class Rule(
    val action: String,        // "allow" | "disallow"
    val os: OsConstraint? = null
)

@Serializable
data class OsConstraint(
    val name: String? = null,  // "windows" | "linux" | "osx"
    val arch: String? = null
)

@Serializable
data class VersionDownloads(
    val client: DownloadRef,
    val server: DownloadRef? = null,
    @SerialName("client_mappings") val clientMappings: DownloadRef? = null,
    @SerialName("server_mappings") val serverMappings: DownloadRef? = null
)

@Serializable
data class DownloadRef(
    val sha1: String,
    val size: Long,
    val url: String
)

@Serializable
data class Arguments(
    val game: List<kotlinx.serialization.json.JsonElement>? = null,
    val jvm: List<kotlinx.serialization.json.JsonElement>? = null
)
