package com.singularity.agent.mod

import com.singularity.common.model.LoaderType
import com.singularity.common.model.ModSide
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Parsuje fabric.mod.json do ModInfo.
 *
 * Format fabric.mod.json (schemaVersion 1, https://fabricmc.net/wiki/documentation:fabric_mod_json_spec):
 * - "id" (wymagane) — mod ID
 * - "version" (wymagane) — wersja moda
 * - "name" (opcjonalne) — wyświetlana nazwa (fallback: id)
 * - "description" (opcjonalne) — opis moda
 * - "authors" (opcjonalne) — stringi lub obiekty z polem "name"
 * - "environment" (opcjonalne) — "client", "server", "*" (default: "*")
 * - "entrypoints" (opcjonalne) — mapa typ→lista (stringi LUB obiekty z "value" + "adapter")
 * - "mixins" (opcjonalne) — lista (stringi LUB obiekty z "config" + opcjonalnym "environment")
 * - "depends" (opcjonalne) — mapa modId→versionRange (required)
 * - "suggests" (opcjonalne) — mapa modId→versionRange (optional)
 * - "breaks" (opcjonalne) — mapa modId→versionRange (incompatibilities; deferred do loader runtime enforcement)
 * - "conflicts" (opcjonalne) — mapa modId→versionRange (soft-warn incompatibilities; deferred do loader runtime enforcement)
 * - "recommends" (opcjonalne) — mapa modId→versionRange (soft optional; mapujemy jako suggests)
 *
 * Object-form entrypoint (Kotlin mods):
 * ```json
 * "entrypoints": {"main": [{"adapter": "kotlin", "value": "com.example.ModKt"}]}
 * ```
 *
 * Referencja: design spec sekcja 5A.2, 5A.7.
 */
object FabricMetadataParser {

    private val logger = LoggerFactory.getLogger(FabricMetadataParser::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parsuje surowy JSON fabric.mod.json do ModInfo.
     *
     * @param rawJson surowa zawartość pliku fabric.mod.json
     * @param jarPath ścieżka do JAR moda (zachowana w ModInfo)
     * @return ModInfo z zunifikowanymi metadanymi
     * @throws IllegalArgumentException jeśli brak wymaganych pól (id, version)
     */
    fun parse(rawJson: String, jarPath: Path): ModInfo {
        // Strip UTF-8 BOM (Windows editors like Notepad add it). kotlinx.serialization NIE
        // handluje BOM — parseToJsonElement throws SerializationException.
        val cleaned = rawJson.removePrefix("\uFEFF")
        val root = json.parseToJsonElement(cleaned).jsonObject

        // Normalize modId to lowercase — eliminuje false-positive "JEI" vs "jei" mismatch
        // w DependencyResolver i DuplicateDetector. Fabric spec mówi modId powinien być lowercase
        // ale real mods nie zawsze przestrzegają.
        val modId = (root["id"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("fabric.mod.json missing required field 'id'"))
            .lowercase()
        val version = root["version"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("fabric.mod.json missing required field 'version'")

        val name = root["name"]?.jsonPrimitive?.contentOrNull ?: modId
        val description = root["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val environment = root["environment"]?.jsonPrimitive?.contentOrNull
        val side = ModSide.fromFabricEnvironment(environment)

        // Authors — stringi LUB obiekty z polem "name"
        val authors = root["authors"]?.jsonArray?.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element["name"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        } ?: emptyList()

        // Entrypoints — mapa typ→lista. Element listy to STRING lub OBJECT {adapter, value}
        // Spłaszczamy wszystkie typy do pojedynczej listy class names (pole "value" z obiektu).
        val entryPoints = root["entrypoints"]?.jsonObject?.values?.flatMap { array ->
            array.jsonArray.mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.contentOrNull
                    is JsonObject -> element["value"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }
        } ?: emptyList()

        // Mixin configs — stringi LUB obiekty z polem "config"
        val mixinConfigs = root["mixins"]?.jsonArray?.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element["config"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        } ?: emptyList()

        // Dependencies: depends (required) + suggests/recommends (optional).
        // Real fabric.mod.json allows version range to be STRING ("*", ">=0.9") OR
        // ARRAY (["*", ">=0.9"]) — some Fabric API builds use array form. We handle both.
        val dependencies = mutableListOf<ModDependency>()

        root["depends"]?.jsonObject?.forEach { (depModId, versionElement) ->
            dependencies.add(
                ModDependency(
                    modId = depModId.lowercase(),
                    versionRange = extractVersionRange(versionElement),
                    required = true
                )
            )
        }

        root["suggests"]?.jsonObject?.forEach { (depModId, versionElement) ->
            dependencies.add(
                ModDependency(
                    modId = depModId.lowercase(),
                    versionRange = extractVersionRange(versionElement),
                    required = false
                )
            )
        }

        // recommends — soft optional (lighter than suggests in Fabric spec, but for our
        // purposes we treat it as optional — same as suggests).
        root["recommends"]?.jsonObject?.forEach { (depModId, versionElement) ->
            dependencies.add(
                ModDependency(
                    modId = depModId.lowercase(),
                    versionRange = extractVersionRange(versionElement),
                    required = false
                )
            )
        }

        // Fabric spec defines `breaks` (hard-fail if mod present) and `conflicts` (soft-warn if present).
        // Deferred do loader runtime enforcement — launcher discovers + presents, loader enforces.
        // See design spec sekcja 5A.3.

        logger.debug(
            "Parsed Fabric mod: {} v{} ({} deps, {} mixins, {} entrypoints)",
            modId, version, dependencies.size, mixinConfigs.size, entryPoints.size
        )

        return ModInfo(
            modId = modId,
            version = version,
            name = name,
            loaderType = LoaderType.FABRIC,
            dependencies = dependencies,
            entryPoints = entryPoints,
            mixinConfigs = mixinConfigs,
            authors = authors,
            description = description,
            side = side,
            jarPath = jarPath
        )
    }

    /**
     * Wyciąga versionRange z JsonElement. Handle:
     * - String form: `">=0.9"` → `">=0.9"`
     * - Array form: `[">=0.9", "<0.100"]` → `">=0.9, <0.100"`
     * - Object form (Fabric 0.5-era): `{"version":">=0.9","side":"both"}` → `">=0.9"`
     *   (extract `version` field przed fallback na toString)
     * - Fallback: `element.toString()` jako opaque string
     */
    private fun extractVersionRange(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.joinToString(", ") { el ->
            (el as? JsonPrimitive)?.contentOrNull ?: el.toString()
        }
        is JsonObject -> (element["version"] as? JsonPrimitive)?.contentOrNull
            ?: element.toString()
    }
}
