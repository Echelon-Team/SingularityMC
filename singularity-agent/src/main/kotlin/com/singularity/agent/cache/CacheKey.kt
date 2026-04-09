package com.singularity.agent.cache

import java.security.MessageDigest

/**
 * Helper do obliczania cache directory key zgodnie z AD2 schema.
 *
 * Cel: oddzielenie cache entries per (agentVer, moduleVer, jarHash). Update agenta
 * lub modulu compat lub modu → nowy dirKey → stary cache nie jest hit, nowy cache
 * jest budowany od zera. Stary dirKey zostanie usuniety przez cleanup(activeDirKeys).
 *
 * Klucz: SHA-256(agentVer + "|" + moduleVer + "|" + jarHash).take(8) jako hex = 16 chars.
 * 64-bit entropia — dla realistic use case (dziesiatki-setki JARow x wersji modulu x
 * wersji agenta w calym cyklu zycia instancji) collision probability ~2.7e-12.
 *
 * Performance: ThreadLocal<MessageDigest> zeby uniknac alokacji per-class w hot path
 * (30k+ klas podczas MC startup × 230us per getInstance = 7s waste bez ThreadLocal).
 *
 * Referencja: AD2 w plan v2.3, design-compliance raport sekcja A.4.
 */
object CacheKey {

    private val SHA256_THREADLOCAL = ThreadLocal.withInitial {
        MessageDigest.getInstance("SHA-256")
    }

    /**
     * Computuje directory key (16 hex chars) dla TransformCache.
     * Uzywany jako pierwszy argument do `transformCache.put(dirKey, className, bytes)`.
     */
    fun dirKey(agentVersion: String, moduleVersion: String, jarHash: String): String {
        val input = "$agentVersion|$moduleVersion|$jarHash"
        val digest = SHA256_THREADLOCAL.get()
        digest.reset()
        digest.update(input.toByteArray(Charsets.UTF_8))
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}
