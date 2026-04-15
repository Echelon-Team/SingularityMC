package com.singularity.launcher.config

import kotlinx.serialization.Serializable

/**
 * Semantic wrapper for launcher config schema version.
 *
 * Used by [ConfigMigrator] to decide whether migration is needed.
 * Integer counter (v1, v2, v3…) — Flyway-style, unambiguous ordering.
 *
 * Wire format in JSON: `"configVersion": 1` (plain integer, transparent to serialization).
 *
 * Pre-versioning configs (missing field) should be treated as version 0 via JsonObject
 * pre-parse in [ConfigMigrator] — see [LauncherSettings.configVersion] KDoc warning.
 */
@Serializable
@JvmInline
value class ConfigVersion(val value: Int) : Comparable<ConfigVersion> {
    override fun compareTo(other: ConfigVersion): Int = value.compareTo(other.value)

    override fun toString(): String = "v$value"

    companion object {
        /** Current schema version. Bump when introducing breaking field changes. */
        val CURRENT: ConfigVersion = ConfigVersion(1)

        /** Sentinel for pre-versioning configs (missing field in JSON). */
        val PRE_VERSIONING: ConfigVersion = ConfigVersion(0)
    }
}
