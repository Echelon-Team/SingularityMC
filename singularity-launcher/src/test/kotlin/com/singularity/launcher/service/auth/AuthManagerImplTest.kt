// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.service.auth

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AuthManagerImplTest {

    @TempDir
    lateinit var tempDir: Path

    private fun manager() = AuthManagerImpl(tempDir.resolve("accounts.json"))

    @Test
    fun `empty manager has no accounts`() = runTest {
        assertEquals(0, manager().listAccounts().size)
    }

    @Test
    fun `createNonPremiumAccount generates deterministic UUID from nick`() = runTest {
        val mgr = manager()
        val a = mgr.createNonPremiumAccount("Notch")

        // Same nick → same UUID
        val expectedUuid = java.util.UUID.nameUUIDFromBytes("OfflinePlayer:Notch".toByteArray())
            .toString().replace("-", "")

        assertEquals(expectedUuid, a.id)
        assertEquals(expectedUuid, a.profile.id)
        assertEquals("Notch", a.profile.name)
        assertFalse(a.isPremium)
        assertNull(a.mcToken)
        assertNull(a.refreshToken)
    }

    @Test
    fun `createNonPremiumAccount UUID is 32 chars (no dashes — Mojang format)`() = runTest {
        val mgr = manager()
        val a = mgr.createNonPremiumAccount("Player")
        assertEquals(32, a.id.length)
        assertFalse(a.id.contains("-"))
    }

    @Test
    fun `listAccounts returns created accounts`() = runTest {
        val mgr = manager()
        mgr.createNonPremiumAccount("A")
        mgr.createNonPremiumAccount("B")
        mgr.createNonPremiumAccount("C")

        assertEquals(3, mgr.listAccounts().size)
    }

    @Test
    fun `setActiveAccount marks one as default`() = runTest {
        val mgr = manager()
        val a = mgr.createNonPremiumAccount("Alpha")
        mgr.createNonPremiumAccount("Beta")

        mgr.setActiveAccount(a.id)
        val accounts = mgr.listAccounts()
        assertTrue(accounts.first { it.id == a.id }.isDefault)
        assertFalse(accounts.first { it.profile.name == "Beta" }.isDefault)
    }

    @Test
    fun `setActiveAccount clears previous default`() = runTest {
        val mgr = manager()
        val a = mgr.createNonPremiumAccount("A")
        val b = mgr.createNonPremiumAccount("B")

        mgr.setActiveAccount(a.id)
        mgr.setActiveAccount(b.id)

        val accounts = mgr.listAccounts()
        assertFalse(accounts.first { it.id == a.id }.isDefault)
        assertTrue(accounts.first { it.id == b.id }.isDefault)
    }

    @Test
    fun `getActiveAccount returns default account`() = runTest {
        val mgr = manager()
        val a = mgr.createNonPremiumAccount("A")
        mgr.setActiveAccount(a.id)

        val active = mgr.getActiveAccount()
        assertNotNull(active)
        assertEquals(a.id, active!!.id)
    }

    @Test
    fun `getActiveAccount returns null when no default`() = runTest {
        val mgr = manager()
        mgr.createNonPremiumAccount("NotDefault")
        assertNull(mgr.getActiveAccount())
    }

    @Test
    fun `deleteAccount removes account`() = runTest {
        val mgr = manager()
        val a = mgr.createNonPremiumAccount("ToDelete")
        mgr.deleteAccount(a.id)
        assertEquals(0, mgr.listAccounts().size)
    }

    @Test
    fun `roundtrip persists accounts across manager instances`() = runTest {
        val mgr1 = manager()
        mgr1.createNonPremiumAccount("Persistent")

        val mgr2 = AuthManagerImpl(tempDir.resolve("accounts.json"))
        val accounts = mgr2.listAccounts()
        assertEquals(1, accounts.size)
        assertEquals("Persistent", accounts[0].profile.name)
    }
}
