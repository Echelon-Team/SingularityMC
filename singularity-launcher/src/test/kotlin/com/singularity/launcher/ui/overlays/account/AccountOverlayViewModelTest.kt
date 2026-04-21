// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.overlays.account

import com.singularity.launcher.service.auth.AuthManager
import com.singularity.launcher.service.auth.MinecraftAccount
import com.singularity.launcher.service.auth.MinecraftProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class AccountOverlayViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    private class FakeAuthManager(initial: List<MinecraftAccount> = emptyList()) : AuthManager {
        private val _accounts = initial.toMutableList()
        override suspend fun listAccounts() = _accounts.toList()
        override suspend fun getActiveAccount(): MinecraftAccount? = _accounts.find { it.isDefault }
        override suspend fun createNonPremiumAccount(nick: String): MinecraftAccount {
            val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$nick".toByteArray())
            val account = MinecraftAccount(
                id = uuid.toString().replace("-", ""),
                profile = MinecraftProfile(id = uuid.toString().replace("-", ""), name = nick),
                isPremium = false
            )
            _accounts.add(account)
            return account
        }
        override suspend fun setActiveAccount(id: String) {
            val updated = _accounts.map { it.copy(isDefault = it.id == id) }
            _accounts.clear()
            _accounts.addAll(updated)
        }
        override suspend fun deleteAccount(id: String) {
            _accounts.removeAll { it.id == id }
        }
    }

    private fun makeVm(mgr: AuthManager) = AccountOverlayViewModel(
        mgr,
        UnconfinedTestDispatcher(testDispatcher.scheduler)
    )

    @Test
    fun `initial state loads accounts`() = runTest {
        val mgr = FakeAuthManager()
        mgr.createNonPremiumAccount("Steve")
        mgr.createNonPremiumAccount("Alex")
        val vm = makeVm(mgr)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, vm.state.first().accounts.size)
    }

    @Test
    fun `createOfflineAccount adds account`() = runTest {
        val mgr = FakeAuthManager()
        val vm = makeVm(mgr)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.createOfflineAccount("Notch")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.first()
        assertEquals(1, state.accounts.size)
        assertEquals("Notch", state.accounts[0].profile.name)
        assertFalse(state.accounts[0].isPremium)
    }

    @Test
    fun `createOfflineAccount rejects blank nick`() = runTest {
        val mgr = FakeAuthManager()
        val vm = makeVm(mgr)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.createOfflineAccount("   ")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, vm.state.first().accounts.size)
        assertNotNull(vm.state.first().error)
    }

    @Test
    fun `setAsDefault updates active account`() = runTest {
        val mgr = FakeAuthManager()
        mgr.createNonPremiumAccount("A")
        mgr.createNonPremiumAccount("B")
        val vm = makeVm(mgr)
        testDispatcher.scheduler.advanceUntilIdle()

        val accountB = vm.state.first().accounts.first { it.profile.name == "B" }
        vm.setAsDefault(accountB.id)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.first().accounts.first { it.id == accountB.id }.isDefault)
    }

    @Test
    fun `deleteAccount removes account`() = runTest {
        val mgr = FakeAuthManager()
        mgr.createNonPremiumAccount("ToDelete")
        val vm = makeVm(mgr)
        testDispatcher.scheduler.advanceUntilIdle()

        val account = vm.state.first().accounts.first()
        vm.deleteAccount(account.id)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, vm.state.first().accounts.size)
    }

    @Test
    fun `openAddDialog shows dialog`() = runTest {
        val mgr = FakeAuthManager()
        val vm = makeVm(mgr)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.openAddDialog()
        assertTrue(vm.state.first().isAddDialogOpen)
        vm.closeAddDialog()
        assertFalse(vm.state.first().isAddDialogOpen)
    }

    @Test
    fun `clearError nullifies error`() = runTest {
        val mgr = FakeAuthManager()
        val vm = makeVm(mgr)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.createOfflineAccount("")  // trigger error
        testDispatcher.scheduler.advanceUntilIdle()
        vm.clearError()
        assertNull(vm.state.first().error)
    }

    @Test
    fun `initial accounts are empty`() = runTest {
        val mgr = FakeAuthManager()
        val vm = makeVm(mgr)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, vm.state.first().accounts.size)
    }

    @Test
    fun `onCleared does not throw`() = runTest {
        val mgr = FakeAuthManager()
        val vm = makeVm(mgr)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.onCleared()
        assertTrue(true)
    }
}
