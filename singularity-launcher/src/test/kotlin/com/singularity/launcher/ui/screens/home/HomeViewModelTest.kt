package com.singularity.launcher.ui.screens.home

import com.singularity.common.model.InstanceConfig
import com.singularity.common.model.InstanceType
import com.singularity.common.model.LoaderType
import com.singularity.launcher.config.I18n
import com.singularity.launcher.config.OfflineMode
import com.singularity.launcher.service.InstanceManager
import com.singularity.launcher.service.news.NewsCache
import com.singularity.launcher.service.news.NewsRepository
import com.singularity.launcher.service.news.NewsSource
import com.singularity.launcher.service.news.ReleaseInfo
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() {
        Dispatchers.setMain(testDispatcher)
        OfflineMode.reset()
    }
    @AfterEach fun teardown() {
        Dispatchers.resetMain()
        OfflineMode.reset()
    }

    private class FakeInstanceManager(
        private val lastPlayed: InstanceManager.Instance? = null
    ) : InstanceManager {
        override suspend fun getLastPlayed(): InstanceManager.Instance? = lastPlayed
        override suspend fun getAll(): List<InstanceManager.Instance> = emptyList()
        override suspend fun getById(id: String): InstanceManager.Instance? = null
        override suspend fun create(config: InstanceConfig): InstanceManager.Instance = error("not used")
        override suspend fun update(instance: InstanceManager.Instance) {}
        override suspend fun delete(id: String) {}
    }

    private fun fakeInstance(name: String, lastPlayedAt: Long) = InstanceManager.Instance(
        id = "test-$name",
        rootDir = Path.of("/tmp/$name"),
        config = InstanceConfig(
            name = name,
            minecraftVersion = "1.20.1",
            type = InstanceType.ENHANCED,
            loader = LoaderType.NONE,
            ramMb = 4096,
            threads = 4,
            jvmArgs = ""
        ),
        lastPlayedAt = lastPlayedAt,
        modCount = 10
    )

    private fun fakeRelease(tag: String) = ReleaseInfo(
        tagName = tag,
        name = "Release $tag",
        changelog = "- fix",
        isPrerelease = false,
        publishedAt = Instant.parse("2026-04-14T10:00:00Z"),
        htmlUrl = "https://github.com/foo/bar",
    )

    /**
     * Test double for NewsRepository. Overrides suspend fetchLatestReleases to bypass
     * Ktor's internal dispatcher (doesn't respect runTest scheduler reliably).
     */
    private class SpyNewsRepository(
        private val fakeReleases: List<ReleaseInfo>,
    ) : NewsRepository(
        httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotImplemented) }),
        repoOwner = "test",
        repoName = "test",
    ) {
        val fetchCount = AtomicInteger(0)
        override suspend fun fetchLatestReleases(limit: Int): List<ReleaseInfo> {
            fetchCount.incrementAndGet()
            return fakeReleases
        }
    }

    @Test
    fun `initial state has no lastPlayed`() = runTest {
        val vm = HomeViewModel(FakeInstanceManager(), UnconfinedTestDispatcher(testDispatcher.scheduler))
        val state = vm.state.first()
        assertNull(state.lastPlayedInstance)
    }

    @Test
    fun `loadLastPlayed updates state with instance from InstanceManager`() = runTest {
        val now = System.currentTimeMillis()
        val mgr = FakeInstanceManager(lastPlayed = fakeInstance("Survival", lastPlayedAt = now - 3600000L))
        val vm = HomeViewModel(mgr, UnconfinedTestDispatcher(testDispatcher.scheduler))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.first()
        assertNotNull(state.lastPlayedInstance)
        assertEquals("Survival", state.lastPlayedInstance!!.instanceName)
        assertEquals("test-Survival", state.lastPlayedInstance.instanceId)
        assertEquals(InstanceType.ENHANCED, state.lastPlayedInstance.type)
        assertEquals("1.20.1", state.lastPlayedInstance.minecraftVersion)
    }

    @Test
    fun `loadLastPlayed with no instances has null lastPlayedInstance`() = runTest {
        val vm = HomeViewModel(FakeInstanceManager(lastPlayed = null), UnconfinedTestDispatcher(testDispatcher.scheduler))
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.state.first().lastPlayedInstance)
    }

    @Test
    fun `onContinueClick calls onLaunch with instance id`() = runTest {
        val mgr = FakeInstanceManager(lastPlayed = fakeInstance("Creative", System.currentTimeMillis()))
        val vm = HomeViewModel(mgr, UnconfinedTestDispatcher(testDispatcher.scheduler))
        testDispatcher.scheduler.advanceUntilIdle()

        var launchedId: String? = null
        vm.onContinueClick { id -> launchedId = id }
        assertEquals("test-Creative", launchedId)
    }

    @Test
    fun `onContinueClick is no-op when no lastPlayed`() = runTest {
        val vm = HomeViewModel(FakeInstanceManager(lastPlayed = null), UnconfinedTestDispatcher(testDispatcher.scheduler))
        testDispatcher.scheduler.advanceUntilIdle()

        var launchedId: String? = null
        vm.onContinueClick { id -> launchedId = id }
        assertNull(launchedId, "Should not call onLaunch when no lastPlayed")
    }

    @Test
    fun `formatLastPlayedSubtitle formats in polish by default`() {
        val i18n = I18n.loadFromResources(defaultLanguage = "pl")
        val subtitle = formatLastPlayedSubtitle(
            i18n = i18n,
            name = "Survival World",
            version = "1.20.1",
            type = InstanceType.ENHANCED,
            lastPlayedMs = System.currentTimeMillis() - 7200000L
        )
        assertTrue(subtitle.contains("Survival World"))
        assertTrue(subtitle.contains("1.20.1"))
        assertTrue(subtitle.contains("Enhanced"))
        assertTrue(subtitle.contains("temu"), "expected polish time-ago phrase, got: $subtitle")
    }

    @Test
    fun `formatLastPlayedSubtitle formats in english when lang switched`() {
        val i18n = I18n.loadFromResources(defaultLanguage = "en")
        val subtitle = formatLastPlayedSubtitle(
            i18n = i18n,
            name = "Hardcore Base",
            version = "1.20.1",
            type = InstanceType.VANILLA,
            lastPlayedMs = System.currentTimeMillis() - 7200000L
        )
        assertTrue(subtitle.contains("Hardcore Base"))
        assertTrue(subtitle.contains("Vanilla"))
        assertTrue(subtitle.contains("ago"), "expected english time-ago phrase, got: $subtitle")
        assertTrue(subtitle.contains("played"), "expected english 'played' word, got: $subtitle")
    }

    // === loadReleases tests (Task 1.8) ===

    @Test
    fun `loadReleases sets Unavailable when newsSource is null (legacy ctor)`() = runTest {
        val vm = HomeViewModel(FakeInstanceManager(), UnconfinedTestDispatcher(testDispatcher.scheduler))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ReleasesState.Unavailable, vm.state.first().releasesState)
    }

    @Test
    fun `loadReleases uses cache when available (no repo fetch)`() = runTest {
        val sample = listOf(fakeRelease("v1.2.3"))
        val cache = NewsCache(Duration.ofHours(6))
        cache.put(sample)
        val repo = SpyNewsRepository(fakeReleases = listOf(fakeRelease("v1.2.3")))

        val vm = HomeViewModel(
            FakeInstanceManager(),
            UnconfinedTestDispatcher(testDispatcher.scheduler),
            newsSource = NewsSource(repo, cache),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ReleasesState.Loaded(sample), vm.state.first().releasesState)
        assertEquals(0, repo.fetchCount.get(), "cache hit must not trigger repo fetch")
    }

    @Test
    fun `loadReleases fetches from repo on cache miss and populates cache`() = runTest {
        val cache = NewsCache(Duration.ofHours(6))
        val fetched = listOf(fakeRelease("v2.0.0"), fakeRelease("v1.9.0"))
        val repo = SpyNewsRepository(fakeReleases = fetched)

        val vm = HomeViewModel(
            FakeInstanceManager(),
            UnconfinedTestDispatcher(testDispatcher.scheduler),
            newsSource = NewsSource(repo, cache),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ReleasesState.Loaded(fetched), vm.state.first().releasesState)
        assertEquals(1, repo.fetchCount.get())
        assertEquals(fetched, cache.get(), "cache populated after successful fetch")
    }

    @Test
    fun `loadReleases does not cache empty fetch result and sets FetchFailed`() = runTest {
        val cache = NewsCache(Duration.ofHours(6))
        val repo = SpyNewsRepository(fakeReleases = emptyList())

        val vm = HomeViewModel(
            FakeInstanceManager(),
            UnconfinedTestDispatcher(testDispatcher.scheduler),
            newsSource = NewsSource(repo, cache),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(cache.get(), "empty fetch should NOT poison cache — next load retries repo")
        assertEquals(ReleasesState.FetchFailed, vm.state.first().releasesState)
    }

    @Test
    fun `loadReleases skips when OfflineMode enabled and sets Offline`() = runTest {
        OfflineMode.parseArgs(arrayOf("--offline"))
        val cache = NewsCache(Duration.ofHours(6))
        val repo = SpyNewsRepository(fakeReleases = listOf(fakeRelease("v1.0")))

        val vm = HomeViewModel(
            FakeInstanceManager(),
            UnconfinedTestDispatcher(testDispatcher.scheduler),
            newsSource = NewsSource(repo, cache),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ReleasesState.Offline, vm.state.first().releasesState)
        assertEquals(0, repo.fetchCount.get(), "offline mode must not fetch")
    }

    @Test
    fun `loadReleases recovers gracefully when repo throws unexpectedly (defense-in-depth)`() = runTest {
        val cache = NewsCache(Duration.ofHours(6))
        val throwingRepo = object : NewsRepository(
            httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotImplemented) }),
            repoOwner = "test",
            repoName = "test",
        ) {
            override suspend fun fetchLatestReleases(limit: Int): List<ReleaseInfo> {
                throw RuntimeException("contract violation")
            }
        }

        val vm = HomeViewModel(
            FakeInstanceManager(),
            UnconfinedTestDispatcher(testDispatcher.scheduler),
            newsSource = NewsSource(throwingRepo, cache),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            ReleasesState.FetchFailed,
            vm.state.first().releasesState,
            "exception treated as fetch failure",
        )
    }

    @Test
    fun `releasesState is Loading before fetch completes then Loaded after`() = runTest {
        val standardDispatcher = StandardTestDispatcher(testDispatcher.scheduler)
        val fetched = listOf(fakeRelease("v1.0"))
        val repo = SpyNewsRepository(fakeReleases = fetched)
        val cache = NewsCache(Duration.ofHours(6))

        // Constructor runs init{} → loadReleases() synchronously emits Loading (cache miss
        // path) via updateState (sync StateFlow.value=...). Then viewModelScope.launch
        // submits the fetch to the scheduler, which is NOT yet advanced.
        val vm = HomeViewModel(
            FakeInstanceManager(),
            standardDispatcher,
            newsSource = NewsSource(repo, cache),
        )

        // Capture state BEFORE advancing scheduler — fetch coroutine is pending.
        assertEquals(
            ReleasesState.Loading,
            vm.state.first().releasesState,
            "Loading emitted immediately after construction (before scheduler advances)",
        )

        // Now let the launched fetch complete.
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            ReleasesState.Loaded(fetched),
            vm.state.first().releasesState,
            "Loaded after fetch completes",
        )
        assertEquals(1, repo.fetchCount.get())
    }

    @Test
    fun `loadLastPlayed swallows exception and leaves lastPlayedInstance null`() = runTest {
        val throwingMgr = object : InstanceManager {
            override suspend fun getLastPlayed(): InstanceManager.Instance? =
                throw RuntimeException("disk I/O failure")
            override suspend fun getAll(): List<InstanceManager.Instance> = emptyList()
            override suspend fun getById(id: String): InstanceManager.Instance? = null
            override suspend fun create(config: InstanceConfig): InstanceManager.Instance = error("not used")
            override suspend fun update(instance: InstanceManager.Instance) {}
            override suspend fun delete(id: String) {}
        }
        val vm = HomeViewModel(throwingMgr, UnconfinedTestDispatcher(testDispatcher.scheduler))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(
            vm.state.first().lastPlayedInstance,
            "unexpected exception in loadLastPlayed must not crash VM",
        )
    }

    @Test
    fun `loadReleases returns Loaded with empty list when cache holds empty`() = runTest {
        // Edge case: cache pre-populated with empty list (e.g. repo has no stable releases
        // yet, prior successful fetch returned []). HomeScreen has a dedicated UI branch
        // rendering "home.news.empty" for this state — pin the reachability.
        val cache = NewsCache(Duration.ofHours(6))
        cache.put(emptyList())
        val repo = SpyNewsRepository(fakeReleases = listOf(fakeRelease("v1.0")))

        val vm = HomeViewModel(
            FakeInstanceManager(),
            UnconfinedTestDispatcher(testDispatcher.scheduler),
            newsSource = NewsSource(repo, cache),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            ReleasesState.Loaded(emptyList()),
            vm.state.first().releasesState,
            "empty cache entry is a valid cache hit — must produce Loaded(empty), not trigger FetchFailed",
        )
        assertEquals(0, repo.fetchCount.get(), "empty cache hit still counts as hit — no repo fetch")
    }

    @Test
    fun `loadReleases rethrows CancellationException instead of swallowing into FetchFailed`() = runTest {
        // Pins structured-concurrency contract: prior review caught that
        // `catch (e: Exception)` alone on JVM would swallow CancellationException
        // (CancellationException extends IllegalStateException → Exception), silently
        // converting scope-cancellation into a FetchFailed UI state. The rethrow-first
        // pattern `catch (CancellationException) { throw e }` must stay; this test fails
        // if someone "cleans up" that rethrow thinking it's redundant.
        val cache = NewsCache(Duration.ofHours(6))
        val cancellingRepo = object : NewsRepository(
            httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotImplemented) }),
            repoOwner = "test",
            repoName = "test",
        ) {
            override suspend fun fetchLatestReleases(limit: Int): List<ReleaseInfo> {
                throw kotlinx.coroutines.CancellationException("simulated scope cancellation")
            }
        }

        val vm = HomeViewModel(
            FakeInstanceManager(),
            UnconfinedTestDispatcher(testDispatcher.scheduler),
            newsSource = NewsSource(cancellingRepo, cache),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // If the catch swallowed CancellationException, it would fall through to `emptyList()`
        // → FetchFailed. Rethrow-first pattern leaves state at the pre-fetch Loading emission.
        val finalState = vm.state.first().releasesState
        assertNotEquals(
            ReleasesState.FetchFailed,
            finalState,
            "CancellationException must not be swallowed into FetchFailed — got $finalState",
        )
        assertEquals(
            ReleasesState.Loading,
            finalState,
            "state stays at Loading (emitted before fetch coroutine) when CancellationException propagates",
        )
    }
}
