package com.singularity.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.singularity.launcher.config.I18n
import com.singularity.launcher.config.LauncherSettingsStore
import com.singularity.launcher.config.LocalI18n
import com.singularity.launcher.service.AgentJarResolver
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.service.InstanceManagerImpl
import com.singularity.launcher.service.LaunchFlowCoordinator
import com.singularity.launcher.service.LibraryDownloader
import com.singularity.launcher.service.ServerManager
import com.singularity.launcher.service.ServerManagerImpl
import com.singularity.launcher.service.auth.AuthManager
import com.singularity.launcher.service.auth.AuthManagerImpl
import com.singularity.launcher.service.ipc.IpcClient
import com.singularity.launcher.service.ipc.IpcClientReal
import org.slf4j.LoggerFactory
import com.singularity.launcher.integration.AutoUpdater
import com.singularity.launcher.integration.DiscordRpcManager
import com.singularity.launcher.onboarding.HardwareDetector
import com.singularity.launcher.onboarding.OnboardingViewModel
import com.singularity.launcher.onboarding.OnboardingWizard
import com.singularity.launcher.service.java.JavaManager
import com.singularity.launcher.service.java.JavaManagerImpl
import com.singularity.launcher.service.modrinth.ModrinthClient
import com.singularity.launcher.service.modrinth.ModrinthClientImpl
import com.singularity.launcher.service.mojang.MojangVersionClient
import com.singularity.launcher.ui.background.ThemeTransitionBackground
import com.singularity.launcher.ui.components.SingularitySidebar
import com.singularity.launcher.ui.di.LocalAuthManager
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
import com.singularity.launcher.ui.theme.SingularityTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Główny container aplikacji — wire'uje wszystkie serwisy, CompositionLocal provider'y,
 * nawigację i routing do peer screens/drill-down panels/overlay.
 *
 * **Settings reactivity (2026-04-11 fix):** `launcherSettings` jest w `mutableStateOf`,
 * więc zmiany w SettingsScreen (theme/language) trigger'ują recomposition całego drzewa.
 * `saveSettings = { store.save(it); launcherSettings = it }` w SettingsScreen call.
 *
 * **Brak Surface nad tłem (2026-04-11 fix):** wcześniej był `Surface(color = background.copy(alpha=0.85f))`
 * który przykrywał `ThemeTransitionBackground`. Screens teraz renderują się bezpośrednio
 * w `Row` nad tłem — każdy screen sam dba o swoje tło (Card, inne Surface'y).
 *
 * **Lifecycle:** HttpClient + appScope + navigator cleaned up w DisposableEffect.
 */
private val logger = LoggerFactory.getLogger("com.singularity.launcher.ui.App")

@Composable
fun App() {
    val home = System.getProperty("user.home")
    val launcherHome = Path.of(home, ".singularitymc")

    // Settings store + reactive state — zmiany w SettingsScreen trigger'ują recomposition
    val settingsStore = remember { LauncherSettingsStore.default() }
    var launcherSettings by remember { mutableStateOf(settingsStore.load()) }

    // I18n — keyed na language, recomputed gdy settings.language zmieni się
    val i18n = remember(launcherSettings.language) {
        I18n.loadFromResources(defaultLanguage = launcherSettings.language)
    }

    // Application scope (cancelled in onDispose)
    val appScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Swing) }

    // Ktor HttpClient (shared for Modrinth, Mojang, Java downloads, LaunchFlow)
    val httpClient = remember {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
        }
    }

    // Services (singletons — constructed once, remember)
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
    val ipcClient: IpcClient = remember {
        IpcClientReal(launcherHome.resolve("instances"), appScope)
    }

    // Discord Rich Presence
    val discordRpc = remember { DiscordRpcManager(clientId = "SINGULARITYMC_DISCORD_APP_ID") }
    DisposableEffect(discordRpc) {
        discordRpc.initialize()
        discordRpc.updatePresence(DiscordRpcManager.PresenceState(isPlaying = false))
        onDispose { discordRpc.shutdown() }
    }

    // Auto-updater (check on startup)
    val autoUpdater = remember { AutoUpdater(httpClient, currentVersion = "1.0.0") }
    var updateAvailable by remember { mutableStateOf<AutoUpdater.UpdateAvailable?>(null) }
    LaunchedEffect(Unit) {
        updateAvailable = autoUpdater.checkForUpdates()
    }

    // Onboarding (first-time setup)
    var showOnboarding by remember { mutableStateOf(launcherSettings.lastActiveAccountId == null) }

    // Launch flow coordinator — real MC launch (Home PLAY button + InstancePanel onLaunch)
    val libraryDownloader = remember {
        LibraryDownloader(
            httpClient = httpClient,
            librariesDir = launcherHome.resolve("libraries"),
            versionsDir = launcherHome.resolve("versions")
        )
    }
    val agentJarResolver = remember { AgentJarResolver(launcherHome.resolve("agent-cache")) }
    val launchFlowCoordinator = remember {
        LaunchFlowCoordinator(
            authManager = authManager,
            javaManager = javaManager,
            mojangClient = mojangClient,
            libraryDownloader = libraryDownloader,
            sharedAssetsDir = launcherHome.resolve("assets"),
            sharedLibrariesDir = launcherHome.resolve("libraries"),
            sharedVersionsDir = launcherHome.resolve("versions"),
            agentJarResolver = agentJarResolver
        )
    }

    // Navigator (ViewModel)
    val navigator = remember { NavigationViewModel() }
    val scope = rememberCoroutineScope()

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            navigator.onCleared()
            discordRpc.shutdown()
            try { httpClient.close() } catch (e: Exception) { /* ignore */ }
            try { appScope.coroutineContext[Job]?.cancel() } catch (e: Exception) { /* ignore */ }
        }
    }

    // Onboarding wizard (first launch)
    if (showOnboarding) {
        SingularityTheme(themeMode = launcherSettings.theme) {
            val hardwareDetector = remember { HardwareDetector() }
            val onboardingVm = remember { OnboardingViewModel(hardwareDetector) }
            OnboardingWizard(
                viewModel = onboardingVm,
                onComplete = {
                    // Persist — save a default offline account so onboarding doesn't re-show
                    val updated = launcherSettings.copy(lastActiveAccountId = "offline-default")
                    settingsStore.save(updated)
                    launcherSettings = updated
                    showOnboarding = false
                }
            )
        }
        return
    }

    SingularityTheme(themeMode = launcherSettings.theme) {
        CompositionLocalProvider(
            LocalNavigator provides navigator,
            LocalI18n provides i18n,
            LocalMojangClient provides mojangClient,
            LocalAuthManager provides authManager
        ) {
            val navState by navigator.state.collectAsState()

            // Background rendered FIRST — screens nad tłem BEZ opaque overlay
            Box(modifier = Modifier.fillMaxSize()) {
                ThemeTransitionBackground(theme = launcherSettings.theme)

                // Screens direct on top of background
                Row(modifier = Modifier.fillMaxSize()) {
                    SingularitySidebar()

                    Box(modifier = Modifier.fillMaxSize()) {
                        when (navState.currentScreen) {
                            Screen.HOME -> {
                                val homeVm = remember(instanceManager) { HomeViewModel(instanceManager) }
                                DisposableEffect(homeVm) { onDispose { homeVm.onCleared() } }
                                HomeScreen(
                                    viewModel = homeVm,
                                    onLaunchInstance = { instanceId ->
                                        scope.launch {
                                            val instance = instanceManager.getById(instanceId)
                                            if (instance != null) {
                                                launchFlowCoordinator.launch(instance, instance.config)
                                                    .collect { event ->
                                                logger.info("Launch progress: {}", event)
                                            }
                                            }
                                        }
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
                                loadSettings = { launcherSettings },
                                saveSettings = { newSettings ->
                                    settingsStore.save(newSettings)
                                    launcherSettings = newSettings  // Reactive update
                                }
                            )
                            Screen.INSTANCE_PANEL -> {
                                val id = navState.instanceContext
                                if (id != null) {
                                    InstancePanel(
                                        instanceId = id,
                                        instanceManager = instanceManager,
                                        onLaunch = { launchId ->
                                            scope.launch {
                                                val instance = instanceManager.getById(launchId)
                                                if (instance != null) {
                                                    launchFlowCoordinator.launch(instance, instance.config)
                                                        .collect { event ->
                                                logger.info("Launch progress: {}", event)
                                            }
                                                }
                                            }
                                        }
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
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$panelName has no context",
            color = MaterialTheme.colorScheme.error
        )
    }
}
