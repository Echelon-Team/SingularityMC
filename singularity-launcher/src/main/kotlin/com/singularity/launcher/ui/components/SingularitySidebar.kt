package com.singularity.launcher.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.ui.di.LocalAuthManager
import com.singularity.launcher.ui.navigation.LocalNavigator
import com.singularity.launcher.ui.navigation.Screen
import com.singularity.launcher.ui.theme.LocalExtraPalette

/**
 * Collapsible sidebar — pixel-perfect port z prototypu (index.html:1947-1998).
 *
 * Wymiary: 60dp collapsed, 240dp expanded (NIE 56/220 — potwierdzone index.html:303, 320).
 * Transition 250ms FastOutSlowInEasing (matcha CSS `transition: width 0.25s cubic-bezier(0.4, 0, 0.2, 1)`).
 *
 * Pin state machine (SidebarPinState): AUTO (hover-to-expand) ↔ PINNED_EXPANDED ↔ PINNED_COLLAPSED
 * cycled przez single/double click na sidebar background.
 *
 * Render: `LocalNavigator` dla nawigacji (zero prop drilling), `LocalExtraPalette` dla kolorów
 * per theme. Ikony z `resources/icons/{name}.svg` przez `painterResource` (custom line-style SVG
 * z prototypu, nie Material Icons — P4 decyzja Mateusza + S11 v3 design review).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SingularitySidebar(
    modifier: Modifier = Modifier
) {
    val navigator = LocalNavigator.current
    val navState by navigator.state.collectAsState()
    val extra = LocalExtraPalette.current

    // Pin state — persistent across recompositions
    var pinState by remember { mutableStateOf(SidebarPinState.AUTO) }

    // Hover detection dla AUTO mode
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val expanded = pinState.isExpanded(isHovered)
    val width by animateDpAsState(
        targetValue = if (expanded) 240.dp else 60.dp,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "sidebar-width"
    )

    // Screens w sidebarze — filter dla inSidebar=true (drill-down INSTANCE_PANEL/SERVER_PANEL NIE są tu)
    val topSidebarScreens = remember {
        Screen.entries.filter { it.inSidebar && it != Screen.SETTINGS }
    }

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .shadow(
                elevation = if (expanded) 4.dp else 0.dp,
                shape = RectangleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.3f),
                spotColor = Color.Black.copy(alpha = 0.3f)
            ),
        color = extra.sidebarBg,
        shape = RectangleShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    // border-right (index.html:306)
                    drawRect(
                        color = extra.sidebarBorder,
                        topLeft = Offset(size.width - 1f, 0f),
                        size = Size(1f, size.height)
                    )
                }
                .hoverable(interactionSource)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { pinState = pinState.cycleOnClick() },
                    onDoubleClick = { pinState = pinState.toggleOnDoubleClick() }
                )
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Header: gradient logo "S" + title (gdy expanded)
            SidebarHeader(expanded = expanded)

            Spacer(Modifier.height(16.dp))

            // Top nav items (7)
            for (screen in topSidebarScreens) {
                SidebarItem(
                    screen = screen,
                    isActive = screen == navState.currentScreen.sidebarIndicator,
                    expanded = expanded,
                    onClick = { navigator.navigateTo(screen) }
                )
            }

            Spacer(Modifier.weight(1f))

            // Separator (index.html:428-434 — border-top: 1px solid sidebar-border)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .height(1.dp)
                    .background(extra.sidebarBorder)
            )

            // Bottom: Settings + Avatar
            SidebarItem(
                screen = Screen.SETTINGS,
                isActive = navState.currentScreen == Screen.SETTINGS,
                expanded = expanded,
                onClick = { navigator.openSettings() }
            )

            SidebarAvatar(
                expanded = expanded,
                onClick = { navigator.toggleAccountOverlay() }
            )
        }
    }
}

/**
 * Header z gradient logo "S" + "SingularityMC" text (expanded).
 * Gradient matcha button-play z prototypu (index.html:333-346): primary → tertiary 135deg.
 */
@Composable
private fun SidebarHeader(expanded: Boolean) {
    val extra = LocalExtraPalette.current

    // Same delayed fade pattern as SidebarItem
    val titleAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (expanded) 150 else 100,
            delayMillis = if (expanded) 200 else 0,
            easing = LinearEasing
        ),
        label = "sidebar_header_title_alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)  // prototyp: sidebar-header min-height 56px
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Gradient box 24x24 z literą "S"
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(extra.playGradientStart, extra.playGradientEnd),
                        start = Offset(0f, 0f),
                        end = Offset.Infinite  // 135deg approximation
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "S",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // Same pattern as SidebarItem — text always in composition dla stabilnego layoutu
        Spacer(Modifier.width(12.dp))
        Text(
            text = "SingularityMC",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.alpha(titleAlpha),
            fontWeight = FontWeight.Bold,
            color = extra.textPrimary
        )
    }
}

/**
 * Pojedynczy nav-item w sidebarze. Uses LocalNavigator dla nawigacji, TooltipArea dla
 * collapsed state (prototyp index.html:489-517). 3 stany hover: default/hover/active
 * z właściwymi kolorami (prototyp index.html:381-393).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarItem(
    screen: Screen,
    isActive: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val extra = LocalExtraPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // 3 stany kolorów (prototyp: text-muted default, text-secondary hover, text-primary active)
    val textColor = when {
        isActive -> extra.textPrimary
        isHovered -> extra.textSecondary
        else -> extra.textMuted
    }
    val iconTint = textColor
    val bgColor = when {
        isActive -> extra.sidebarActive
        isHovered -> extra.sidebarHover
        else -> Color.Transparent
    }

    val i18n = LocalI18n.current
    val screenLabel = i18n[screen.displayKey]

    // Text fade-in delay: czeka aż sidebar width animation skończy się (250ms) przed fade-in
    // Przy collapse: natychmiast fade-out żeby text nie był widoczny podczas shrinking width
    val textAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (expanded) 150 else 100,
            delayMillis = if (expanded) 200 else 0,  // Wait ~200ms dla width animation
            easing = LinearEasing
        ),
        label = "sidebar_item_text_alpha"
    )

    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
                .hoverable(interactionSource)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .heightIn(min = 40.dp)  // prototyp: nav-item min-height 40px
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource("icons/${screen.iconKey}.svg"),
                contentDescription = screenLabel,
                tint = iconTint,
                modifier = Modifier.size(18.dp)  // prototyp: nav-icon svg 18x18
            )
            // Text ALWAYS w composition z alpha fade — stabilny layout, icon nie skacze.
            // `if (textAlpha > 0f)` usuwa text z composition → Row re-layouts → icon jump
            // podczas collapse animation. Parent Surface width animation robi clipping
            // sam, text poza bounds nie jest widoczny.
            Spacer(Modifier.width(16.dp))
            Text(
                text = screenLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                modifier = Modifier.alpha(textAlpha)
            )
        }
    }

    // TooltipArea tylko gdy collapsed (prototyp: tooltip pokazuje się TYLKO gdy collapsed)
    if (expanded) {
        content()
    } else {
        TooltipArea(
            tooltip = {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = screenLabel,
                        color = extra.textPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        ) {
            content()
        }
    }
}

/**
 * Avatar konta — klik otwiera AccountOverlay (decyzja D4 Mateusza — Account to overlay, nie peer view).
 * TooltipArea z nickiem gdy collapsed (prototyp index.html:489-517).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarAvatar(
    expanded: Boolean,
    onClick: () -> Unit
) {
    val extra = LocalExtraPalette.current
    val authManager = LocalAuthManager.current
    val i18n = LocalI18n.current

    // Load active account nick asynchronously
    var accountNick by remember { mutableStateOf(i18n["account.guest"]) }
    LaunchedEffect(authManager) {
        try {
            accountNick = authManager.getActiveAccount()?.profile?.name ?: i18n["account.guest"]
        } catch (e: Exception) {
            accountNick = i18n["account.guest"]
        }
    }

    val content: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(extra.sidebarHover)
                .combinedClickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = accountNick.firstOrNull()?.uppercase() ?: "G",
                color = extra.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    if (expanded) {
        content()
    } else {
        TooltipArea(
            tooltip = {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = accountNick,
                        color = extra.textPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        ) {
            content()
        }
    }
}
