// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.overlays.account

import com.singularity.launcher.service.auth.AuthManager
import com.singularity.launcher.service.auth.MinecraftAccount
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

data class AccountOverlayState(
    val accounts: List<MinecraftAccount> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isAddDialogOpen: Boolean = false
)

/**
 * ViewModel dla AccountOverlay. Ładuje accounts w init, eksponuje handlery dla action
 * buttons w card (set default / delete) + add offline dialog.
 */
class AccountOverlayViewModel(
    private val authManager: AuthManager,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<AccountOverlayState>(AccountOverlayState(), dispatcher) {

    init {
        loadAccounts()
    }

    private fun loadAccounts() {
        updateState { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val accounts = authManager.listAccounts()
                updateState { it.copy(accounts = accounts, isLoading = false) }
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun openAddDialog() = updateState { it.copy(isAddDialogOpen = true, error = null) }
    fun closeAddDialog() = updateState { it.copy(isAddDialogOpen = false) }
    fun clearError() = updateState { it.copy(error = null) }

    fun createOfflineAccount(nick: String) {
        val trimmed = nick.trim()
        if (trimmed.isBlank()) {
            updateState { it.copy(error = "Nick cannot be blank") }
            return
        }
        if (trimmed.length > 16) {
            updateState { it.copy(error = "Nick too long (max 16 chars)") }
            return
        }
        viewModelScope.launch {
            try {
                authManager.createNonPremiumAccount(trimmed)
                loadAccounts()
                updateState { it.copy(isAddDialogOpen = false) }
            } catch (e: Exception) {
                updateState { it.copy(error = e.message) }
            }
        }
    }

    fun setAsDefault(id: String) {
        viewModelScope.launch {
            try {
                authManager.setActiveAccount(id)
                loadAccounts()
            } catch (e: Exception) {
                updateState { it.copy(error = e.message) }
            }
        }
    }

    fun deleteAccount(id: String) {
        viewModelScope.launch {
            try {
                authManager.deleteAccount(id)
                loadAccounts()
            } catch (e: Exception) {
                updateState { it.copy(error = e.message) }
            }
        }
    }
}
