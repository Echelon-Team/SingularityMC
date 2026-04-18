package com.singularity.launcher.ui.screens.home

import androidx.compose.foundation.background
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalUriHandler
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.news.ReleaseInfo
import com.singularity.launcher.ui.theme.LocalExtraPalette
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.singularity.launcher.ui.screens.home.HomeScreen")

/**
 * HomeScreen — pierwszy ekran launchera.
 *
 * **Layout (pixel-perfect z prototypu index.html:2005-2047):**
 * 1. home-continue Card — horizontal Row { gradient IconBox 56dp + Column(title+subtitle) + "▶ GRAJ" button }
 * 2. "Aktualności" Text (titleLarge)
 * 3. news-grid LazyVerticalGrid(GridCells.Adaptive(320.dp)) z NewsCard items — 3 latest
 *    stable GitHub releases per spec 4.12
 *
 * NewsCard: version badge + date + changelog (truncated markdown) + "Zobacz na GitHub" button.
 *
 * **News states** — exhaustive `when` over [ReleasesState]:
 * - [ReleasesState.Loading] → CircularProgressIndicator
 * - [ReleasesState.Offline] → "Tryb offline — aktualności niedostępne"
 * - [ReleasesState.Unavailable] → "Aktualności tymczasowo niedostępne" (dev wiring gap)
 * - [ReleasesState.FetchFailed] → "Nie udało się pobrać aktualności" (retry implicit on next load)
 * - [ReleasesState.Loaded] → grid of NewsCards, or "brak aktualności" if empty
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

        when (val rs = state.releasesState) {
            ReleasesState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            ReleasesState.Offline -> {
                Text(
                    text = i18n["home.news.offline"],
                    color = extra.textMuted,
                )
            }
            ReleasesState.Unavailable -> {
                Text(
                    text = i18n["home.news.unavailable"],
                    color = extra.textMuted,
                )
            }
            ReleasesState.FetchFailed -> {
                Text(
                    text = i18n["home.news.fetch_failed"],
                    color = extra.statusError,
                )
            }
            is ReleasesState.Loaded -> {
                if (rs.releases.isEmpty()) {
                    Text(
                        text = i18n["home.news.empty"],
                        color = extra.textMuted
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(rs.releases, key = { it.tagName }) { release ->
                            NewsCard(release)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Home continue card — horizontal row.
 */
@Composable
private fun HomeContinueCard(
    lastPlayed: LastPlayedInfo?,
    onContinueClick: () -> Unit
) {
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = extra.cardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon placeholder (gradient 56dp)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                val title = lastPlayed?.instanceName ?: i18n["home.continue.empty.title"]
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = extra.textPrimary
                )
                Spacer(Modifier.height(4.dp))
                val subtitle = if (lastPlayed != null) {
                    formatLastPlayedSubtitle(
                        i18n = i18n,
                        name = lastPlayed.instanceName,
                        version = lastPlayed.minecraftVersion,
                        type = lastPlayed.type,
                        lastPlayedMs = lastPlayed.lastPlayedTimestamp
                    )
                } else {
                    i18n["home.continue.empty.subtitle"]
                }
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = extra.textSecondary
                )
            }

            Spacer(Modifier.width(16.dp))

            Button(
                onClick = onContinueClick,
                enabled = lastPlayed != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(text = i18n["home.continue.play"], fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * NewsCard — renders a single GitHub [ReleaseInfo] in the Aktualności grid.
 *
 * Layout: version badge + "Stable" chip + date on top row,
 *         changelog markdown (truncated to 300 chars) in body,
 *         "Zobacz na GitHub" TextButton footer (opens in system browser via
 *         [LocalUriHandler]).
 */
@Composable
private fun NewsCard(release: ReleaseInfo) {
    val extra = LocalExtraPalette.current
    val i18n = LocalI18n.current
    val uriHandler = LocalUriHandler.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = extra.cardBg),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top row: version + stable tag + date
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = release.displayVersion,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = extra.textPrimary,
                )
                Spacer(Modifier.width(8.dp))
                StableBadge()
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatReleaseDate(release),
                    fontSize = 11.sp,
                    color = extra.textMuted,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Changelog body (markdown truncated; proper MD rendering = future enhancement).
            // TODO(markdown): when MD rendering lands, replace plain .take(300) with an
            //   AST-aware truncator — cutting mid-link or mid-bold will corrupt rendering.
            Text(
                text = if (release.changelog.length > 300) release.changelog.take(300) + "…" else release.changelog,
                fontSize = 12.sp,
                color = extra.textSecondary,
                maxLines = 6,
            )

            Spacer(Modifier.height(8.dp))

            // "Zobacz na GitHub" link — opens in system browser via Compose Multiplatform's
            // LocalUriHandler. Handles Linux xdg-open fallback and headless edge cases that
            // raw java.awt.Desktop.browse misses. openUri throws IllegalArgumentException
            // on unhandled URIs — we log and swallow so the UI stays responsive.
            TextButton(onClick = {
                runCatching { uriHandler.openUri(release.htmlUrl) }
                    .onFailure { logger.warn("Failed to open release URL: {}", release.htmlUrl, it) }
            }) {
                Text(
                    text = i18n["home.news.view_github"],
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun StableBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "Stable",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// Polish month names in genitive form (used in date-of-month phrases like "15 kwietnia 2026").
// Intentionally hardcoded rather than DateTimeFormatter.ofPattern("d LLLL yyyy", Locale("pl")):
// JDK's Polish locale data returns nominative "kwiecień" instead of expected genitive "kwietnia"
// on standalone `LLLL` pattern, and behavior regressed across recent JDK versions
// (JDK-4984277, adoptium/adoptium-support#953). Hardcoded array is intentional and reliable.
private val polishMonths = listOf(
    "stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca",
    "lipca", "sierpnia", "września", "października", "listopada", "grudnia",
)

/** "15 kwietnia 2026" format using Polish month names (display stays Polish regardless of locale). */
private fun formatReleaseDate(release: ReleaseInfo): String {
    val date = release.publishedLocalDate
    return "${date.dayOfMonth} ${polishMonths[date.monthValue - 1]} ${date.year}"
}
