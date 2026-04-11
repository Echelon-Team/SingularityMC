package com.singularity.launcher.service

import com.singularity.common.model.InstanceConfig
import com.singularity.common.model.InstanceType
import com.singularity.common.model.LoaderType
import com.singularity.launcher.service.auth.AuthManager
import com.singularity.launcher.service.auth.MinecraftAccount
import com.singularity.launcher.service.auth.MinecraftProfile
import com.singularity.launcher.service.java.JavaManager
import com.singularity.launcher.service.mojang.Library
import com.singularity.launcher.service.mojang.LibraryDownloads
import com.singularity.launcher.service.mojang.MojangVersionClient
import com.singularity.launcher.service.mojang.OsConstraint
import com.singularity.launcher.service.mojang.Rule
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LaunchFlowCoordinatorTest {

    @TempDir lateinit var tempDir: Path

    private class FakeAuthManager(private val active: MinecraftAccount?) : AuthManager {
        override suspend fun listAccounts(): List<MinecraftAccount> = listOfNotNull(active)
        override suspend fun getActiveAccount(): MinecraftAccount? = active
        override suspend fun createNonPremiumAccount(nick: String): MinecraftAccount = error("not used")
        override suspend fun setActiveAccount(id: String) {}
        override suspend fun deleteAccount(id: String) {}
    }

    private class FakeJavaManager(private val path: Path) : JavaManager {
        override suspend fun ensureJava(mcVersion: String, onProgress: (Int) -> Unit): Path = path
        override fun getJavaFor(mcVersion: String): Int = 17
        override fun isInstalled(javaMajorVersion: Int): Boolean = true
        override fun listInstalledVersions(): List<Int> = listOf(17)
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun defaultAccount() = MinecraftAccount(
        id = "aaaabbbbccccddddeeee111122223333",
        profile = MinecraftProfile(id = "aaaabbbbccccddddeeee111122223333", name = "Steve"),
        isPremium = false
    )

    private fun mkCoord(
        authActive: MinecraftAccount?,
        mojangResponder: MockEngine.() -> Unit
    ): LaunchFlowCoordinator {
        val httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) {
            install(ContentNegotiation) { json(json) }
        }
        val mojangEngine = MockEngine { req ->
            // Captured by outer lambda
            respond("", HttpStatusCode.OK)
        }
        // We need a separate mojang client — build inline
        val mojangClient = MojangVersionClient(
            HttpClient(MockEngine {
                mojangResponder(this as MockEngine)
                respond("", HttpStatusCode.OK)
            }) {
                install(ContentNegotiation) { json(json) }
            }
        )
        val downloader = LibraryDownloader(
            HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            tempDir.resolve("libs"),
            tempDir.resolve("versions")
        )
        val agentResolver = AgentJarResolver(tempDir.resolve("agent-cache"))
        return LaunchFlowCoordinator(
            authManager = FakeAuthManager(active = authActive),
            javaManager = FakeJavaManager(tempDir.resolve("java/bin/java")),
            mojangClient = mojangClient,
            libraryDownloader = downloader,
            sharedAssetsDir = tempDir.resolve("assets"),
            sharedLibrariesDir = tempDir.resolve("libs"),
            sharedVersionsDir = tempDir.resolve("versions"),
            agentJarResolver = agentResolver
        )
    }

    private fun instanceOf(version: String = "1.20.4") = InstanceManager.Instance(
        id = "test",
        rootDir = tempDir.resolve("instance"),
        config = InstanceConfig("Test", version, InstanceType.VANILLA, LoaderType.NONE),
        lastPlayedAt = null,
        modCount = 0
    )

    @Test
    fun `launch emits Error when no active account`() = runTest {
        // Build a minimal coordinator (mojang won't be called because no account aborts early)
        val mojangClient = MojangVersionClient(
            HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) {
                install(ContentNegotiation) { json(json) }
            }
        )
        val downloader = LibraryDownloader(
            HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            tempDir.resolve("libs"),
            tempDir.resolve("versions")
        )
        val coord = LaunchFlowCoordinator(
            authManager = FakeAuthManager(active = null),
            javaManager = FakeJavaManager(tempDir.resolve("java/bin/java")),
            mojangClient = mojangClient,
            libraryDownloader = downloader,
            sharedAssetsDir = tempDir.resolve("assets"),
            sharedLibrariesDir = tempDir.resolve("libs"),
            sharedVersionsDir = tempDir.resolve("versions"),
            agentJarResolver = AgentJarResolver(tempDir.resolve("agent-cache"))
        )
        val instance = instanceOf()
        val events = coord.launch(instance, instance.config).toList()
        val lastError = events.filterIsInstance<LaunchFlowCoordinator.LaunchProgress.Error>().lastOrNull()
        assertNotNull(lastError, "Must emit Error event")
        assertTrue(lastError!!.message.contains("Brak"))
    }

    @Test
    fun `launch emits Error when Mojang manifest fetch fails`() = runTest {
        val mojangClient = MojangVersionClient(
            HttpClient(MockEngine { respond("", HttpStatusCode.InternalServerError) }) {
                install(ContentNegotiation) { json(json) }
            }
        )
        val downloader = LibraryDownloader(
            HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            tempDir.resolve("libs"),
            tempDir.resolve("versions")
        )
        val coord = LaunchFlowCoordinator(
            authManager = FakeAuthManager(active = defaultAccount()),
            javaManager = FakeJavaManager(tempDir.resolve("java/bin/java")),
            mojangClient = mojangClient,
            libraryDownloader = downloader,
            sharedAssetsDir = tempDir.resolve("assets"),
            sharedLibrariesDir = tempDir.resolve("libs"),
            sharedVersionsDir = tempDir.resolve("versions"),
            agentJarResolver = AgentJarResolver(tempDir.resolve("agent-cache"))
        )
        val instance = instanceOf()
        val events = coord.launch(instance, instance.config).toList()
        val error = events.filterIsInstance<LaunchFlowCoordinator.LaunchProgress.Error>().lastOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.contains("manifest") || error.message.contains("Mojang"))
    }

    @Test
    fun `launch emits Error when version not found in manifest`() = runTest {
        val emptyManifest = """{"latest":{"release":"1.0","snapshot":"1.0"},"versions":[]}"""
        val mojangClient = MojangVersionClient(
            HttpClient(MockEngine {
                respond(
                    emptyManifest,
                    HttpStatusCode.OK,
                    io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")
                )
            }) {
                install(ContentNegotiation) { json(json) }
            }
        )
        val downloader = LibraryDownloader(
            HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            tempDir.resolve("libs"),
            tempDir.resolve("versions")
        )
        val coord = LaunchFlowCoordinator(
            authManager = FakeAuthManager(active = defaultAccount()),
            javaManager = FakeJavaManager(tempDir.resolve("java/bin/java")),
            mojangClient = mojangClient,
            libraryDownloader = downloader,
            sharedAssetsDir = tempDir.resolve("assets"),
            sharedLibrariesDir = tempDir.resolve("libs"),
            sharedVersionsDir = tempDir.resolve("versions"),
            agentJarResolver = AgentJarResolver(tempDir.resolve("agent-cache"))
        )
        val instance = instanceOf()
        val events = coord.launch(instance, instance.config).toList()
        assertTrue(events.any { it is LaunchFlowCoordinator.LaunchProgress.Error })
    }

    @Test
    fun `LibraryDownloader isApplicable returns true for lib without rules`() {
        val downloader = LibraryDownloader(
            HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            tempDir,
            tempDir
        )
        val lib = Library(
            name = "test:lib:1.0",
            downloads = LibraryDownloads()
        )
        assertTrue(downloader.isApplicable(lib))
    }

    @Test
    fun `AgentJarResolver throws when agent jar missing from resources`() {
        val resolver = AgentJarResolver(tempDir.resolve("cache"))
        // W unit testach agent jar nie jest dostępny z resources (copyAgentJar gradle task nie uruchomiony).
        // Expected: IllegalStateException. Jeśli jednak jar istnieje (integration), akceptujemy jako pass.
        try {
            val result = resolver.resolveAgentJar()
            // Integration test z buildem — jar istnieje
            assertTrue(java.nio.file.Files.exists(result))
        } catch (e: IllegalStateException) {
            // Unit test bez build (typowe IDE run) — expected
            assertTrue(e.message!!.contains("singularity-agent.jar"))
        }
    }

    @Test
    fun `LibraryDownloader isApplicable filters OS-specific library`() {
        val downloader = LibraryDownloader(
            HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            tempDir,
            tempDir
        )
        val lib = Library(
            name = "natives:linux",
            downloads = LibraryDownloads(),
            rules = listOf(
                Rule(
                    action = "allow",
                    os = OsConstraint(name = "linux")
                )
            )
        )
        val result = downloader.isApplicable(lib)
        val currentOs = System.getProperty("os.name").lowercase()
        if (currentOs.contains("linux")) {
            assertTrue(result)
        } else {
            assertFalse(result)
        }
    }
}
