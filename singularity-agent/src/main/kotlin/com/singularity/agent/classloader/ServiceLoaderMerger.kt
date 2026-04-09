package com.singularity.agent.classloader

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * Merguje deklaracje SPI providers z WSZYSTKICH JARow (MC, mody, libs) w jeden rejestr.
 *
 * Uzywane przez AgentMain podczas bootstrap:
 * 1. ScanJar dla kazdego zarejestrowanego JAR'a
 * 2. Dla SingularityMixinService: getProviders("org.spongepowered.asm.service.IMixinService")
 *    potwierdza ze nasz service jest zarejestrowany (sanity check)
 * 3. Dla Mixin framework: getProviders() dla zadanego interface
 *
 * Pelna integracja z ClassLoader.getResources() defer do Sub 2c (wymaga multi-mod setup).
 *
 * Referencja: design spec sekcja 5A.4 (ServiceLoader.load()).
 */
class ServiceLoaderMerger {

    private val logger = LoggerFactory.getLogger(ServiceLoaderMerger::class.java)
    private val services = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Dodaje pojedynczy provider mapping. Deduplikacja przez ConcurrentHashMap.newKeySet().
     */
    fun addService(serviceInterface: String, providerClass: String) {
        services.getOrPut(serviceInterface) { ConcurrentHashMap.newKeySet() }.add(providerClass)
    }

    /**
     * Zwraca liste providers dla danego service interface. Pusta lista jesli brak.
     */
    fun getProviders(serviceInterface: String): List<String> =
        services[serviceInterface]?.toList() ?: emptyList()

    /**
     * Zwraca set wszystkich zarejestrowanych service interface names.
     */
    fun getAllServices(): Set<String> = services.keys.toSet()

    /**
     * Skanuje otwarty JarFile po entries w META-INF/services/, parsuje prowidery,
     * dodaje do registry.
     *
     * Format pliku META-INF/services/<interface>:
     * - Jedna nazwa klasy per linia
     * - Linie z # to komentarze (ignorowane)
     * - Puste linie ignorowane
     * - Trailing whitespace trimowany
     * - Obsluga CRLF i LF line endings
     *
     * JarFile pozostaje open — caller jest odpowiedzialny za .use{} lifecycle.
     */
    fun scanJar(jarFile: JarFile) {
        val entries = jarFile.entries()
        var foundServices = 0
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            if (!entry.name.startsWith("META-INF/services/")) continue

            val serviceInterface = entry.name.removePrefix("META-INF/services/")
            if (serviceInterface.isEmpty() || serviceInterface.contains('/')) continue

            val content = jarFile.getInputStream(entry).bufferedReader().use { it.readText() }
            var lineCount = 0
            content.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { providerClass ->
                    addService(serviceInterface, providerClass)
                    lineCount++
                }
            if (lineCount > 0) {
                foundServices++
                logger.debug("Found {} providers for {} in {}", lineCount, serviceInterface, jarFile.name)
            }
        }
        if (foundServices > 0) {
            logger.info("Scanned {}: {} service interfaces with providers", jarFile.name, foundServices)
        }
    }
}
