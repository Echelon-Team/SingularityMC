package com.singularity.launcher.service.runner

import com.singularity.common.model.InstanceConfig
import com.singularity.launcher.service.auth.MinecraftAccount
import java.nio.file.Path

/**
 * Wszystko co potrzebne do uruchomienia MC process.
 *
 * **javaPath** — z JavaManager (Task 29 ensureJava).
 * **gameDir** — `<instance>/minecraft/` (per-instance isolated MC dir).
 * **assetsDir** — `~/.singularitymc/shared/assets` (shared across instances).
 * **agentJarPath** — path do `singularity-agent.jar` (Enhanced only). Null dla Vanilla.
 * **classpath** — list of MC + libraries JARs (assembled z assets manifest w Task 32).
 */
data class LaunchContext(
    val config: InstanceConfig,
    val instanceDir: Path,
    val account: MinecraftAccount,
    val javaPath: Path,
    val gameDir: Path,
    val assetsDir: Path,
    val assetIndex: String,
    val agentJarPath: Path? = null,  // Enhanced only
    val classpath: List<Path> = emptyList(),
    val extraJvmArgs: List<String> = emptyList()
)

/**
 * Convert Mojang UUID (32 chars bez myślników) → dashed format 8-4-4-4-12.
 * Wymagane dla MC `--uuid` arg.
 *
 * Idempotent — jeśli input już ma dashes, zwraca bez zmian.
 */
fun String.toUuidWithDashes(): String {
    if (length != 32) return this  // already dashed lub invalid
    return "${substring(0, 8)}-${substring(8, 12)}-${substring(12, 16)}-${substring(16, 20)}-${substring(20, 32)}"
}
