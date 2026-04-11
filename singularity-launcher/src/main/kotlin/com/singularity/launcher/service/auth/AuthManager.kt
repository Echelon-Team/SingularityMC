package com.singularity.launcher.service.auth

import kotlinx.serialization.Serializable

/**
 * Auth manager interface — kontraktu dla Task 23 AccountOverlay + McRunner (Task 30).
 *
 * **Sub 4 scope (P7 Mateusz):** Offline accounts tylko. Microsoft flow NIE zaimplementowany
 * — UI disabled z bannerem "Microsoft login zostanie dodane w przyszłej aktualizacji".
 * Interface nie ma `signInWithMicrosoft()` metody, więc brak `NotImplementedError`.
 *
 * **Implementation:** `AuthManagerImpl` (Task 28) z persistent `accounts.json` storage.
 */
interface AuthManager {

    /**
     * Lista wszystkich accounts (offline + premium jeśli Microsoft kiedyś dodane).
     */
    suspend fun listAccounts(): List<MinecraftAccount>

    /**
     * Aktywne (default) konto — returns first z isDefault=true, null gdy brak default.
     * McRunner używa tego jako credentials dla `--username` + `--uuid` args.
     */
    suspend fun getActiveAccount(): MinecraftAccount?

    /**
     * Dodaje offline (non-premium) account z deterministycznym UUID z nicka.
     *
     * **Deterministic UUID:** `UUID.nameUUIDFromBytes("OfflinePlayer:$nick".toByteArray())`
     * — same nick → same UUID. Pozwala user'owi migrować instancję między urządzeniami
     * zachowując inventory/stats.
     *
     * @throws IllegalArgumentException gdy nick jest blank lub > 16 chars
     */
    suspend fun createNonPremiumAccount(nick: String): MinecraftAccount

    /**
     * Ustawia account jako active (default). Clear previous default.
     */
    suspend fun setActiveAccount(id: String)

    /**
     * Usuwa account z persistencji. No-op gdy id nie istnieje.
     */
    suspend fun deleteAccount(id: String)
}

/**
 * Konto Minecraft (offline lub premium Microsoft — później).
 *
 * @param id UUID w Mojang format (32 chars bez myślników, lowercase)
 * @param profile basic profile info (id + name — same UUID + nick)
 * @param isPremium czy premium (Microsoft Auth sign-in) lub offline
 * @param mcToken Minecraft token dla premium (null dla offline)
 * @param refreshToken refresh token dla premium (null dla offline)
 * @param isDefault czy jest aktywnym default account
 */
@Serializable
data class MinecraftAccount(
    val id: String,
    val profile: MinecraftProfile,
    val isPremium: Boolean,
    val mcToken: String? = null,
    val refreshToken: String? = null,
    val isDefault: Boolean = false
)

/**
 * Minecraft profile — podstawowe info user'a (UUID + nick).
 */
@Serializable
data class MinecraftProfile(
    val id: String,
    val name: String
)
