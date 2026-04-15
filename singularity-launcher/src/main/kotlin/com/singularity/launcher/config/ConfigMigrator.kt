package com.singularity.launcher.config

import com.singularity.launcher.ui.theme.ThemeMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/**
 * Migrates legacy [LauncherSettings] JSON to current schema using the **Droga 1** pattern:
 * read raw JSON, create fresh [LauncherSettings] with explicit field mappings from old structure.
 *
 * Why Droga 1:
 * - New fields get defaults from the data class automatically
 * - Invalid values (unknown enum, bad type) fall back to defaults instead of crashing
 * - Single place to reason about migration rules
 * - For user JSON config (pure-function transform), Droga 1 is simpler than stepwise chains
 *   used by DB schema tools like Flyway/Liquibase
 *
 * **Workflow in [LauncherSettingsStore] (Task 1.4):**
 * 1. Read raw JSON from disk
 * 2. Call [detectVersion] to peek `configVersion` without full deserialization
 * 3. If version < [ConfigVersion.CURRENT]: backup old file, call [migrate], save migrated result
 * 4. If version == [ConfigVersion.CURRENT]: normal decode (kotlinx handles it)
 * 5. If version > [ConfigVersion.CURRENT]: normal decode with `ignoreUnknownKeys=true` (forward compat)
 *
 * **Enum parsing assumption:** [readEnum] uses `raw.uppercase()` + `valueOf(...)`, which assumes
 * enum constants are UPPERCASE identifiers (SCREAMING_SNAKE_CASE). All current enums follow this
 * convention; if a future enum deviates, add per-enum parsing logic instead.
 */
object ConfigMigrator {

    /**
     * Peek `configVersion` from raw JSON without full deserialization.
     *
     * Returns [ConfigVersion.PRE_VERSIONING] (v0) for:
     * - Missing `configVersion` field (legacy configs from before versioning existed)
     * - Malformed JSON (caller may then see [migrate] throw too — both signal "start fresh")
     * - Non-integer `configVersion` value (schema corruption)
     * - Negative `configVersion` value (corruption — caller will treat as migrate needed)
     * - Integer overflow (JSON number > Int.MAX — handled by `intOrNull` returning null)
     *
     * Contract is best-effort: any ambiguity collapses to PRE_VERSIONING, triggering full
     * migration. Asymmetric with [migrate] which fails fast on unparseable JSON.
     */
    fun detectVersion(rawJson: String): ConfigVersion {
        return try {
            val obj = Json.parseToJsonElement(rawJson).jsonObject
            val primitive = obj["configVersion"]?.jsonPrimitive ?: return ConfigVersion.PRE_VERSIONING
            // Reject string-wrapped values like "1" — schema corruption.
            if (primitive.isString) return ConfigVersion.PRE_VERSIONING
            val rawInt = primitive.intOrNull ?: return ConfigVersion.PRE_VERSIONING
            // Negative version numbers are corruption signals.
            if (rawInt < 0) return ConfigVersion.PRE_VERSIONING
            ConfigVersion(rawInt)
        } catch (e: Exception) {
            ConfigVersion.PRE_VERSIONING
        }
    }

    /**
     * Migrate legacy JSON to a current-schema [LauncherSettings] object.
     *
     * Does NOT perform IO — caller is responsible for backup-and-save. Throws
     * [kotlinx.serialization.SerializationException] (or subclass) if JSON is unparseable
     * or the root element is not an object (intentional: caller decides between "use defaults"
     * and "show user error").
     */
    fun migrate(oldJson: String): LauncherSettings {
        val old = Json.parseToJsonElement(oldJson).jsonObject

        return LauncherSettings(
            configVersion = ConfigVersion.CURRENT,
            theme = readEnum(old, "theme", ThemeMode.END) { ThemeMode.valueOf(it) },
            language = readString(old, "language") ?: "pl",
            lastActiveAccountId = readString(old, "lastActiveAccountId"),
            lastActiveInstanceId = readString(old, "lastActiveInstanceId"),
            windowX = readInt(old, "windowX") ?: -1,
            windowY = readInt(old, "windowY") ?: -1,
            windowWidth = readInt(old, "windowWidth") ?: 1280,
            windowHeight = readInt(old, "windowHeight") ?: 800,
            updateChannel = readEnum(old, "updateChannel", UpdateChannel.STABLE) { UpdateChannel.valueOf(it) },
            autoCheckUpdates = readBoolean(old, "autoCheckUpdates") ?: true,
            jvmExtraArgs = readString(old, "jvmExtraArgs") ?: "",
            debugLogsEnabled = readBoolean(old, "debugLogsEnabled") ?: false,
            discordRpcEnabled = readBoolean(old, "discordRpcEnabled") ?: false,
            telemetryEnabled = readBoolean(old, "telemetryEnabled") ?: false,
            instancesDir = readString(old, "instancesDir")
                ?.takeIf { it.isNotBlank() }  // defensive: blank path → null (avoids Path.of("") trap + LauncherSettings init{} require)
                ?.let { Path.of(it) },
            autoStartWithSystem = readBoolean(old, "autoStartWithSystem") ?: false,
        )
    }

    // === Helpers ===

    private fun readString(obj: JsonObject, key: String): String? =
        obj[key]?.jsonPrimitive?.contentOrNull

    private fun readInt(obj: JsonObject, key: String): Int? =
        obj[key]?.jsonPrimitive?.intOrNull

    private fun readBoolean(obj: JsonObject, key: String): Boolean? =
        obj[key]?.jsonPrimitive?.booleanOrNull

    /**
     * Reads enum value case-insensitively; returns default if field missing or value invalid.
     * Assumes enum constants are UPPERCASE identifiers (see object-level KDoc).
     */
    private inline fun <reified E : Enum<E>> readEnum(
        obj: JsonObject,
        key: String,
        default: E,
        parser: (String) -> E,
    ): E {
        val raw = readString(obj, key) ?: return default
        return runCatching { parser(raw.uppercase()) }.getOrDefault(default)
    }
}
