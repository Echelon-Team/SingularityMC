// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class I18nTest {

    private fun fakeI18n() = I18n(
        strings = mapOf(
            "pl" to mapOf(
                "nav.home" to "Home",
                "nav.instances" to "Instancje",
                "action.save" to "Zapisz",
                "action.cancel" to "Anuluj"
            ),
            "en" to mapOf(
                "nav.home" to "Home",
                "nav.instances" to "Instances",
                "action.save" to "Save",
                "action.cancel" to "Cancel",
                "nav.modrinth" to "Modrinth"  // tylko en
            )
        ),
        currentLanguage = "pl"
    )

    @Test
    fun `get returns value for current language`() {
        val i18n = fakeI18n()
        assertEquals("Instancje", i18n["nav.instances"])
    }

    @Test
    fun `get falls back to en when key missing in current language`() {
        val i18n = fakeI18n()
        assertEquals("Modrinth", i18n["nav.modrinth"], "Should fallback to en")
    }

    @Test
    fun `get returns key itself when missing in both pl and en`() {
        val i18n = fakeI18n()
        assertEquals("unknown.key", i18n["unknown.key"],
            "Fallback final: return key literal so developer sees which key is missing")
    }

    @Test
    fun `switchLanguage changes current language`() {
        val i18n = fakeI18n()
        assertEquals("Zapisz", i18n["action.save"])
        val en = i18n.switchLanguage("en")
        assertEquals("Save", en["action.save"])
    }

    @Test
    fun `switchLanguage preserves strings map (immutable)`() {
        val i18n = fakeI18n()
        val en = i18n.switchLanguage("en")
        // Original is unchanged
        assertEquals("Zapisz", i18n["action.save"])
        assertEquals("Save", en["action.save"])
    }

    @Test
    fun `availableLanguages returns all language keys`() {
        val i18n = fakeI18n()
        val langs = i18n.availableLanguages
        assertEquals(setOf("pl", "en"), langs)
    }

    @Test
    fun `loadFromResources reads JSON and parses correctly`() {
        // Integration test — weryfikuje że JSON parsing działa
        val json = """
            {
              "nav.home": "Home",
              "nav.instances": "Instancje",
              "action.save": "Zapisz"
            }
        """.trimIndent()
        val parsed = I18n.parseJson(json)
        assertEquals("Home", parsed["nav.home"])
        assertEquals("Instancje", parsed["nav.instances"])
        assertEquals("Zapisz", parsed["action.save"])
    }
}
