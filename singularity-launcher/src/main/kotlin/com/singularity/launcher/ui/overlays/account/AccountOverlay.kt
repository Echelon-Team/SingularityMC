// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.overlays.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.auth.AuthManager
import com.singularity.launcher.service.auth.MinecraftAccount
import com.singularity.launcher.ui.components.ModalDialog
import com.singularity.launcher.ui.components.ModalSize
import com.singularity.launcher.ui.components.TextBadge
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * AccountOverlay — popup nad dowolnym screen (D4 decyzja Mateusza — jedyny udokumentowany
 * wyjątek od pixel-perfect prototypu; prototyp ma peer view account, my robimy overlay).
 *
 * **Structure:**
 * 1. Sticky Microsoft banner (P1 Mateusz — "Logowanie Microsoft nie wprowadzone")
 * 2. LazyColumn account cards (Offline enabled z action buttons, Microsoft disabled)
 * 3. "+ Dodaj offline account" button (Microsoft add disabled — post Sub 5 patch)
 *
 * **Close behaviors:** klik scrim, Esc (via Dialog), X button w ModalDialog header.
 */
@Composable
fun AccountOverlay(
    authManager: AuthManager,
    onDismiss: () -> Unit
) {
    val vm = remember(authManager) { AccountOverlayViewModel(authManager) }
    DisposableEffect(vm) { onDispose { vm.onCleared() } }

    val state by vm.state.collectAsState()
    val i18n = LocalI18n.current
    val extra = LocalExtraPalette.current

    ModalDialog(
        title = i18n["account.title"],
        onDismiss = onDismiss,
        size = ModalSize.LARGE,
        actions = {
            Button(onClick = { vm.openAddDialog() }) {
                Text(i18n["action.add_account"])
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onDismiss) {
                Text(i18n["action.close"])
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Sticky Microsoft banner (P1 + P7)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(extra.statusWarning.copy(alpha = 0.15f))
                    .padding(12.dp)
            ) {
                Text(
                    text = i18n["account.banner.microsoft_disabled"],
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.statusWarning,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Account list
            when {
                state.isLoading -> Text(i18n["home.news.loading"], color = extra.textMuted)
                state.accounts.isEmpty() -> Text(
                    text = i18n["account.empty"],
                    color = extra.textMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(state.accounts, key = { it.id }) { account ->
                        AccountCard(
                            account = account,
                            onSetDefault = { vm.setAsDefault(account.id) },
                            onDelete = { vm.deleteAccount(account.id) }
                        )
                    }
                }
            }

            // Error message
            state.error?.let { err ->
                Text(
                    text = err,
                    color = extra.statusError,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    // Add offline dialog (nested)
    if (state.isAddDialogOpen) {
        AddOfflineAccountDialog(
            onCreate = vm::createOfflineAccount,
            onDismiss = vm::closeAddDialog,
            error = state.error
        )
    }
}

/**
 * Pojedyncza karta account — avatar + profile info + action buttons (Set default + Remove).
 * Microsoft accounts (isPremium) mają "TYLKO ODCZYT" badge i disabled actions.
 */
@Composable
private fun AccountCard(
    account: MinecraftAccount,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current
    val isMicrosoft = account.isPremium

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = extra.cardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar circle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(extra.sidebarActive),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = account.profile.name.firstOrNull()?.uppercase() ?: "?",
                    color = extra.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(Modifier.width(12.dp))

            // Profile info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = account.profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = extra.textPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    if (account.isDefault) {
                        TextBadge(
                            text = i18n["account.badge.default"],
                            backgroundColor = extra.statusSuccess,
                            textColor = extra.statusSuccess
                        )
                    }
                    if (isMicrosoft) {
                        Spacer(Modifier.width(4.dp))
                        TextBadge(
                            text = "TYLKO ODCZYT",
                            backgroundColor = extra.textMuted,
                            textColor = extra.textMuted
                        )
                    }
                }
                Text(
                    text = if (isMicrosoft) i18n["account.type.premium"] else i18n["account.type.offline"],
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.textMuted
                )
            }

            // Action buttons
            if (!isMicrosoft) {
                if (!account.isDefault) {
                    TextButton(onClick = onSetDefault) {
                        Text(i18n["account.action.set_default"])
                    }
                }
                TextButton(onClick = onDelete) {
                    Text(
                        text = i18n["account.action.remove"],
                        color = extra.statusError
                    )
                }
            }
        }
    }
}
