// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.service.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Implementation `AuthManager` — Sub 4 scope: pełne offline accounts, Microsoft stub.
 *
 * **Microsoft Auth scope:** NIE zaimplementowany w Sub 4 ani Sub 5 — post Sub 5 patch
 * (P7 Mateusz). Interface nie ma `signInWithMicrosoft()` metody, więc nie trzeba
 * throw NotImplementedError. UI (Task 23) disabled Microsoft button z bannerem.
 *
 * **Deterministic UUID:** z `UUID.nameUUIDFromBytes("OfflinePlayer:$nick".toByteArray())`
 * — same nick na różnych urządzeniach zawsze daje ten sam UUID (pozwala user'owi zachować
 * inventory/stats gdy migruje instancję).
 *
 * **UUID format:** Mojang format bez myślników (32 chars). Used w `--uuid` arg przy launch
 * (McRunner Task 30).
 */
class AuthManagerImpl(
    private val accountsFile: Path
) : AuthManager {

    companion object {
        fun default(): AuthManagerImpl {
            val home = System.getProperty("user.home")
            val file = Path.of(home, ".singularitymc", "accounts.json")
            return AuthManagerImpl(file)
        }
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class AccountsData(val accounts: List<MinecraftAccount> = emptyList())

    private fun loadData(): AccountsData {
        if (!Files.exists(accountsFile)) return AccountsData()
        return try {
            val content = Files.readString(accountsFile)
            json.decodeFromString(AccountsData.serializer(), content)
        } catch (e: Exception) {
            System.err.println("Warning: corrupted accounts.json — starting fresh: ${e.message}")
            AccountsData()
        }
    }

    private fun saveData(data: AccountsData) {
        Files.createDirectories(accountsFile.parent)
        val tmp = accountsFile.resolveSibling("${accountsFile.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(AccountsData.serializer(), data))
        Files.move(tmp, accountsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override suspend fun listAccounts(): List<MinecraftAccount> = loadData().accounts

    override suspend fun getActiveAccount(): MinecraftAccount? =
        loadData().accounts.find { it.isDefault }

    override suspend fun createNonPremiumAccount(nick: String): MinecraftAccount {
        val trimmedNick = nick.trim()
        require(trimmedNick.isNotBlank()) { "Nick cannot be blank" }
        require(trimmedNick.length <= 16) { "Nick max 16 characters" }

        // Deterministic UUID — same nick → same UUID
        val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$trimmedNick".toByteArray())
        val mojangFormat = uuid.toString().replace("-", "")

        val account = MinecraftAccount(
            id = mojangFormat,
            profile = MinecraftProfile(
                id = mojangFormat,
                name = trimmedNick
            ),
            isPremium = false,
            mcToken = null,
            refreshToken = null,
            isDefault = false
        )

        val current = loadData()
        // Check duplicate (same UUID)
        if (current.accounts.any { it.id == mojangFormat }) {
            return current.accounts.first { it.id == mojangFormat }  // Return existing
        }

        val updated = AccountsData(accounts = current.accounts + account)
        saveData(updated)
        return account
    }

    override suspend fun setActiveAccount(id: String) {
        val current = loadData()
        val updated = AccountsData(
            accounts = current.accounts.map { it.copy(isDefault = it.id == id) }
        )
        saveData(updated)
    }

    override suspend fun deleteAccount(id: String) {
        val current = loadData()
        val updated = AccountsData(accounts = current.accounts.filter { it.id != id })
        saveData(updated)
    }
}
