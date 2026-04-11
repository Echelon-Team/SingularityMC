package com.singularity.launcher.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.theme.LocalExtraPalette
import java.time.Instant
import java.time.ZoneId

/**
 * HomeScreen — pierwszy ekran launchera.
 *
 * **Layout (pixel-perfect z prototypu index.html:2005-2047):**
 * 1. home-continue Card — horizontal Row { gradient IconBox 56dp + Column(title+subtitle) + "▶ GRAJ" button }
 *    (NIE headline "SingularityMC" na górze — dubluje sidebar logo z Task 3)
 * 2. "Aktualności" Text (titleLarge)
 * 3. news-grid LazyVerticalGrid(GridCells.Adaptive(320.dp)) z NewsCard items
 *
 * NewsCard: banner placeholder 100dp + Column(title + description + date)
 *
 * **Dependencies (forward ref):**
 * - `HomeViewModel(InstanceManager)` — Task 26 dostarcza InstanceManager
 * - `LocalNavigator` — Task 1 NavigationViewModel
 * - `LocalI18n` — Task 6 I18n
 * - `LocalExtraPalette` — Task 2 theme
 *
 * ViewModel lifecycle przez `DisposableEffect(viewModel) { onDispose { viewModel.onCleared() } }`
 * — fix C3 performance-v1 leak (wire w Task 32 App.kt przez `rememberViewModel` helper).
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onLaunchInstance: (String) -> Unit
) {
    DisposableEffect(viewModel) {
        onDispose { viewModel.onCleared() }
    }

    val state by viewModel.state.collectAsState()
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        // === home-continue Card ===
        HomeContinueCard(
            lastPlayed = state.lastPlayedInstance,
            onContinueClick = { viewModel.onContinueClick(onLaunchInstance) }
        )

        Spacer(Modifier.height(32.dp))

        // === Aktualności ===
        Text(
            text = i18n["home.news.title"],
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = extra.textPrimary
        )

        Spacer(Modifier.height(16.dp))

        when {
            state.isLoadingNews -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            state.newsError != null -> {
                Text(
                    text = "${i18n["home.news.error"]}: ${state.newsError}",
                    color = extra.statusError
                )
            }
            state.news.isEmpty() -> {
                Text(
                    text = i18n["home.news.empty"],
                    color = extra.textMuted
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(state.news, key = { it.id }) { news ->
                        NewsCard(news)
                    }
                }
            }
        }
    }
}

/**
 * Home continue card — horizontal row (NIE duży button).
 *
 * Layout (index.html:2007-2018):
 * Card(clickable) { Row { gradient IconBox + Column(title+subtitle) + GRAJ button } }
 * Gdy brak lastPlayed: empty state z sugestią utworzenia instancji.
 */
@Composable
private fun HomeContinueCard(
    lastPlayed: LastPlayedInfo?,
    onContinueClick: () -> Unit
) {
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current

    if (lastPlayed != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onContinueClick() },
            colors = CardDefaults.cardColors(containerColor = extra.cardBg)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gradient icon box
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF2F195F), Color(0xFF472147))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = extra.textPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.width(16.dp))

                // Title + subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = i18n["home.continue.title"],
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = extra.textPrimary
                    )
                    Text(
                        text = formatLastPlayedSubtitle(
                            name = lastPlayed.instanceName,
                            version = lastPlayed.minecraftVersion,
                            type = lastPlayed.type,
                            lastPlayedMs = lastPlayed.lastPlayedTimestamp
                        ),
                        fontSize = 13.sp,
                        color = extra.textMuted
                    )
                }

                Spacer(Modifier.width(16.dp))

                // PLAY button — primary gradient (prototyp button-play 135deg)
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(extra.playGradientStart, extra.playGradientEnd)
                            )
                        )
                        .clickable(onClick = onContinueClick)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = i18n["action.play"],
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    } else {
        // Empty state — brak ostatnio granej instancji
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = extra.cardBg)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = i18n["home.continue.title"],
                    style = MaterialTheme.typography.titleMedium,
                    color = extra.textPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = i18n["home.continue.none"],
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.textMuted
                )
            }
        }
    }
}

/**
 * NewsCard — banner placeholder 100dp + body (title + description + date).
 * Prototyp index.html:2023-2030.
 */
@Composable
private fun NewsCard(news: NewsItem) {
    val extra = LocalExtraPalette.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = extra.cardBg)
    ) {
        Column {
            // Banner placeholder (lub future: AsyncImage z news.imageUrl)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "banner",
                    fontSize = 11.sp,
                    color = extra.textDisabled
                )
            }

            // Body
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = news.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = extra.textPrimary,
                    maxLines = 2
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = news.description,
                    fontSize = 12.sp,
                    color = extra.textSecondary,
                    maxLines = 3
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formatPublishedDate(news.publishedAt),
                    fontSize = 11.sp,
                    color = extra.textMuted
                )
            }
        }
    }
}

/**
 * Format ISO-8601 publishedAt jako "DD month YYYY" (np. "20 marca 2026").
 */
private fun formatPublishedDate(iso: String): String = try {
    val instant = Instant.parse(iso)
    val localDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    val polishMonths = listOf(
        "stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca",
        "lipca", "sierpnia", "września", "października", "listopada", "grudnia"
    )
    "${localDate.dayOfMonth} ${polishMonths[localDate.monthValue - 1]} ${localDate.year}"
} catch (e: Exception) {
    iso  // fallback: raw ISO
}
