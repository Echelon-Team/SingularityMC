package com.singularity.launcher.ui.navigation

/**
 * 5 sekcji widoku Settings (Screen.SETTINGS).
 *
 * Zgodnie z design spec sekcja 10:
 * - APPEARANCE: motyw (End/Aether) + język (pl/en)
 * - PERFORMANCE: hardware presets (LOW/MEDIUM/HIGH/ULTRA) + Resource Manager
 * - UPDATES: kanał (Stable/Beta) + auto-check + changelog
 * - INTEGRATIONS: Discord Rich Presence (on/off, granulacja)
 * - ADVANCED: domyślne JVM flags + ścieżka instalacji + logi debugowania
 *
 * `displayKey` — klucz do i18n dla nazwy sekcji w lewym sub-nav menu Settings screen.
 */
enum class SettingsSection(val displayKey: String) {
    APPEARANCE("settings.appearance"),
    PERFORMANCE("settings.performance"),
    UPDATES("settings.updates"),
    INTEGRATIONS("settings.integrations"),
    ADVANCED("settings.advanced")
}
