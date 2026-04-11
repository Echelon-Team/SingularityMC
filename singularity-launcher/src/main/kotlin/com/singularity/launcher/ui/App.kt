package com.singularity.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.singularity.launcher.config.I18n
import com.singularity.launcher.config.LauncherSettings
import com.singularity.launcher.config.LauncherSettingsStore
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.service.InstanceManagerImpl
import com.singularity.launcher.service.ServerManager
import com.singularity.launcher.service.ServerManagerImpl
import com.singularity.launcher.service.auth.AuthManager
import com.singularity.launcher.service.auth.AuthManagerImpl
import com.singularity.launcher.service.ipc.IpcClient
import com.singularity.launcher.service.ipc.IpcClientMock
import com.singularity.launcher.service.java.JavaManager
import com.singularity.launcher.service.java.JavaManagerImpl
import com.singularity.launcher.service.modrinth.ModrinthClient
import com.singularity.launcher.service.modrinth.ModrinthClientImpl
import com.singularity.launcher.service.mojang.MojangVersionClient
import com.singularity.launcher.ui.background.ThemeTransitionBackground
import com.singularity.launcher.ui.components.SingularitySidebar
import com.singularity.launcher.ui.di.LocalMojangClient
import com.singularity.launcher.ui.navigation.LocalNavigator
import com.singularity.launcher.ui.navigation.NavigationViewModel
import com.singularity.launcher.ui.navigation.Screen
import com.singularity.launcher.ui.overlays.account.AccountOverlay
import com.singularity.launcher.ui.screens.diagnostics.DiagnosticsScreen
import com.singularity.launcher.ui.screens.home.HomeScreen
import com.singularity.launcher.ui.screens.home.HomeViewModel
import com.singularity.launcher.ui.screens.instances.InstancePanel
import com.singularity.launcher.ui.screens.instances.InstancesScreen
import com.singularity.launcher.ui.screens.modrinth.ModrinthScreen
import com.singularity.launcher.ui.screens.screenshots.ScreenshotsScreen
import com.singularity.launcher.ui.screens.servers.ServersScreen
import com.singularity.launcher.ui.screens.servers.panel.ServerPanel
import com.singularity.launcher.ui.screens.settings.SettingsScreen
import com.singularity.launcher.ui.screens.skins.SkinsScreen
import com.singularity.launcher.ui.theme.LocalExtraPalette
import com.singularity.launcher.ui.theme.SingularityTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Główny container aplikacji — wire'uje wszystkie serwisy, CompositionLocal provider'y,
 * nawigację i routing do peer screens/drill-down panels/overlay.
 *
 * **Services (singletons dla całej aplikacji):**
 * - InstanceManagerImpl: `~/.singularitymc/instances/`
 * - ServerManagerImpl: `~/.singularitymc/servers/` + ServerRunner
 * - AuthManagerImpl: `~/.singularitymc/accounts.json`
 * - JavaManagerImpl: `~/.singularitymc/java/`
 * - ModrinthClientImpl: Ktor HttpClient
 * - MojangVersionClient: Ktor HttpClient
 * - IpcClientMock: sinewave generator (Sub 5 real wire)
 * - LauncherSettingsStore: `~/.singularitymc/launcher.json`
 *
 * **Lifecycle:** wszystkie services i scopes są tworzone w `remember { }` — cleanup przez
 * DisposableEffect onDispose (cancel HttpClient + IpcClientMock scope + close serwisy).
 */
@Composable
fun App() {
    val home = System.getProperty("user.home")
    val launcherHome = Path.of(home, ".singularitymc")

    // Settings store (persistent)
    val settingsStore = remember { LauncherSettingsStore.default() }
    val launcherSettings = remember { settingsStore.load() }

    // I18n (load from resources)
    val i18n = remember(launcherSettings.language) {
        I18n.loadFromResources(defaultLanguage = launcherSettings.language)
    }

    // Application scope (cancelled in onDispose)
    val appScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Swing) }

    // Ktor HttpClient (shared for Modrinth, Mojang, Java downloads)
    val httpClient = remember {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
        }
    }

    // Services (singletons — constructed once)
    val instanceManager: InstanceManager = remember {
        InstanceManagerImpl(launcherHome.resolve("instances"))
    }
    val mojangClient = remember { MojangVersionClient(httpClient) }
    val javaManager: JavaManager = remember {
        JavaManagerImpl(launcherHome.resolve("java"), httpClient)
    }
    val serverManager: ServerManager = remember {
        ServerManagerImpl(
            serversRoot = launcherHome.resolve("servers"),
            mojangClient = mojangClient,
            downloadHttpClient = httpClient,
            javaManager = javaManager
        )
    }
    val authManager: AuthManager = remember { AuthManagerImpl.default() }
    val modrinthClient: ModrinthClient = remember { ModrinthClientImpl(httpClient) }
    val ipcClient: IpcClient = remember { IpcClientMock(appScope) }

    // Navigator (ViewModel)
    val navigator = remember { NavigationViewModel() }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            navigator.onCleared()
            try { httpClient.close() } catch (e: Exception) { /* ignore */ }
            try { appScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() } catch (e: Exception) { /* ignore */ }
        }
    }

    SingularityTheme(themeMode = launcherSettings.theme) {
        CompositionLocalProvider(
            LocalNavigator provides navigator,
            LocalI18n provides i18n,
            LocalMojangClient provides mojangClient
        ) {
            val navState by navigator.state.collectAsState()

            Box(modifier = Modifier.fillMaxSize()) {
                // Background (theme-aware)
                ThemeTransitionBackground(theme = launcherSettings.theme)

                // Surface containing sidebar + content
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f)
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        SingularitySidebar()

                        Box(modifier = Modifier.fillMaxSize()) {
                            // Route to peer screen / drill-down panel
                            when (navState.currentScreen) {
                                Screen.HOME -> {
                                    val homeVm = remember(instanceManager) { HomeViewModel(instanceManager) }
                                    DisposableEffect(homeVm) { onDispose { homeVm.onCleared() } }
                                    HomeScreen(
                                        viewModel = homeVm,
                                        onLaunchInstance = { instanceId ->
                                            // Launch flow — in Sub 4 navigate to InstancePanel (real launch Sub 5)
                                            navigator.openInstancePanel(instanceId)
                                        }
                                    )
                                }
                                Screen.INSTANCES -> InstancesScreen(instanceManager = instanceManager)
                                Screen.MODRINTH -> ModrinthScreen(modrinthClient = modrinthClient)
                                Screen.SERVERS -> ServersScreen(serverManager = serverManager)
                                Screen.SKINS -> SkinsScreen(
                                    skinsDir = launcherHome.resolve("skins"),
                                    isPremiumProvider = { false }  // offline-only dla Sub 4
                                )
                                Screen.SCREENSHOTS -> ScreenshotsScreen(instanceManager = instanceManager)
                                Screen.DIAGNOSTICS -> DiagnosticsScreen(
                                    ipcClient = ipcClient,
                                    instanceManager = instanceManager
                                )
                                Screen.SETTINGS -> SettingsScreen(
                                    loadSettings = { settingsStore.load() },
                                    saveSettings = { settingsStore.save(it) }
                                )
                                Screen.INSTANCE_PANEL -> {
                                    val id = navState.instanceContext
                                    if (id != null) {
                                        InstancePanel(
                                            instanceId = id,
                                            instanceManager = instanceManager,
                                            onLaunch = { /* Sub 5 launch flow */ }
                                        )
                                    } else {
                                        MissingContextPlaceholder("INSTANCE_PANEL")
                                    }
                                }
                                Screen.SERVER_PANEL -> {
                                    val id = navState.serverContext
                                    if (id != null) {
                                        ServerPanel(
                                            serverId = id,
                                            serverManager = serverManager
                                        )
                                    } else {
                                        MissingContextPlaceholder("SERVER_PANEL")
                                    }
                                }
                            }
                        }
                    }
                }

                // Account overlay (popup over current screen)
                if (navState.accountOverlayOpen) {
                    AccountOverlay(
                        authManager = authManager,
                        onDismiss = { navigator.toggleAccountOverlay() }
                    )
                }
            }
        }
    }
}

@Composable
private fun MissingContextPlaceholder(panelName: String) {
    val extra = LocalExtraPalette.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$panelName has no context",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
