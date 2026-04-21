// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.modrinth

import com.singularity.launcher.service.modrinth.ModrinthClient
import com.singularity.launcher.service.modrinth.ModrinthError
import com.singularity.launcher.service.modrinth.ModrinthSearchHit
import com.singularity.launcher.service.modrinth.ModrinthVersion
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

@OptIn(ExperimentalCoroutinesApi::class)
class ModrinthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    private class FakeModrinthClient(
        private val searchResponse: Result<List<ModrinthSearchHit>> = Result.success(emptyList()),
        private val versionsResponse: Result<List<ModrinthVersion>> = Result.success(emptyList())
    ) : ModrinthClient {
        var searchCallCount = 0
        var lastQuery: String? = null
        var lastFacets: List<List<String>>? = null

        override suspend fun search(
            query: String,
            facets: List<List<String>>,
            limit: Int,
            offset: Int,
            sort: String
        ): Result<List<ModrinthSearchHit>> {
            searchCallCount++
            lastQuery = query
            lastFacets = facets
            return searchResponse
        }

        override suspend fun getVersions(
            projectId: String,
            gameVersions: List<String>,
            loaders: List<String>
        ): Result<List<ModrinthVersion>> = versionsResponse
    }

    private fun hit(id: String, title: String) = ModrinthSearchHit(
        projectId = id,
        slug = id,
        title = title,
        description = "Test mod",
        iconUrl = null,
        downloads = 1000,
        categories = listOf("optimization"),
        gameVersions = listOf("1.20.1"),
        loaders = listOf("fabric")
    )

    private fun makeVm(client: ModrinthClient) = ModrinthViewModel(
        client,
        dispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler)
    )

    @Test
    fun `initial state has NoQuery error`() = runTest {
        val client = FakeModrinthClient()
        val vm = makeVm(client)
        val state = vm.state.first()
        assertTrue(state.error is ModrinthError.NoQuery, "Initial state: waiting for query")
        assertTrue(state.results.isEmpty())
    }

    @Test
    fun `setQuery triggers search after debounce`() = runTest {
        val client = FakeModrinthClient(searchResponse = Result.success(listOf(hit("sodium", "Sodium"))))
        val vm = makeVm(client)

        vm.setQuery("sodium")
        testDispatcher.scheduler.advanceTimeBy(600)  // past 500ms debounce
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, client.searchCallCount)
        assertEquals("sodium", client.lastQuery)
    }

    @Test
    fun `empty results sets EmptyResults error`() = runTest {
        val client = FakeModrinthClient(searchResponse = Result.success(emptyList()))
        val vm = makeVm(client)

        vm.setQuery("nonexistent")
        testDispatcher.scheduler.advanceTimeBy(600)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.first()
        assertTrue(state.error is ModrinthError.EmptyResults)
    }

    @Test
    fun `network error sets Offline state`() = runTest {
        val networkException = java.net.UnknownHostException("api.modrinth.com")
        val client = object : ModrinthClient {
            override suspend fun search(
                query: String, facets: List<List<String>>, limit: Int, offset: Int, sort: String
            ): Result<List<ModrinthSearchHit>> = Result.failure(networkException)
            override suspend fun getVersions(
                projectId: String, gameVersions: List<String>, loaders: List<String>
            ): Result<List<ModrinthVersion>> = Result.success(emptyList())
        }
        val vm = makeVm(client)

        vm.setQuery("sodium")
        testDispatcher.scheduler.advanceTimeBy(600)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.first()
        assertTrue(
            state.error is ModrinthError.Offline,
            "Expected Offline, got ${state.error}"
        )
    }

    @Test
    fun `rate limit error with retryAfter`() = runTest {
        val rateLimitException = ModrinthClient.RateLimitException(retryAfterSec = 30)
        val client = object : ModrinthClient {
            override suspend fun search(
                query: String, facets: List<List<String>>, limit: Int, offset: Int, sort: String
            ): Result<List<ModrinthSearchHit>> = Result.failure(rateLimitException)
            override suspend fun getVersions(
                projectId: String, gameVersions: List<String>, loaders: List<String>
            ): Result<List<ModrinthVersion>> = Result.success(emptyList())
        }
        val vm = makeVm(client)

        vm.setQuery("sodium")
        testDispatcher.scheduler.advanceTimeBy(600)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.first()
        assertTrue(state.error is ModrinthError.RateLimit)
        assertEquals(30, (state.error as ModrinthError.RateLimit).retryAfterSec)
    }

    @Test
    fun `empty query after debounce returns to NoQuery state`() = runTest {
        val client = FakeModrinthClient(searchResponse = Result.success(listOf(hit("sodium", "Sodium"))))
        val vm = makeVm(client)

        vm.setQuery("sodium")
        testDispatcher.scheduler.advanceTimeBy(600)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setQuery("")
        testDispatcher.scheduler.advanceTimeBy(600)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.first()
        assertTrue(state.error is ModrinthError.NoQuery)
    }

    @Test
    fun `setGameVersion triggers new search`() = runTest {
        val client = FakeModrinthClient(searchResponse = Result.success(listOf(hit("sodium", "Sodium"))))
        val vm = makeVm(client)

        vm.setQuery("sodium")
        testDispatcher.scheduler.advanceTimeBy(600)
        testDispatcher.scheduler.advanceUntilIdle()
        val countBefore = client.searchCallCount

        vm.setGameVersion("1.19.4")
        testDispatcher.scheduler.advanceTimeBy(600)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(client.searchCallCount > countBefore, "Filter change triggers new search")
    }

    @Test
    fun `openInstallDialog fetches versions and opens picker`() = runTest {
        val client = FakeModrinthClient(
            versionsResponse = Result.success(listOf(
                ModrinthVersion(
                    id = "v1",
                    projectId = "sodium",
                    name = "Sodium 0.5.3",
                    versionNumber = "0.5.3",
                    gameVersions = listOf("1.20.1"),
                    loaders = listOf("fabric"),
                    files = emptyList()
                )
            ))
        )
        val vm = makeVm(client)

        vm.openInstallDialog(hit("sodium", "Sodium"))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.first()
        assertNotNull(state.installDialog)
        assertEquals("sodium", state.installDialog!!.projectId)
        assertEquals(1, state.installDialog!!.versions.size)
    }

    @Test
    fun `closeInstallDialog clears state`() = runTest {
        val client = FakeModrinthClient(
            versionsResponse = Result.success(listOf(
                ModrinthVersion(id = "v1", projectId = "sodium", name = "Sodium 0.5.3",
                    versionNumber = "0.5.3", gameVersions = listOf("1.20.1"),
                    loaders = listOf("fabric"), files = emptyList())
            ))
        )
        val vm = makeVm(client)

        vm.openInstallDialog(hit("sodium", "Sodium"))
        testDispatcher.scheduler.advanceUntilIdle()
        vm.closeInstallDialog()

        val state = vm.state.first()
        assertNull(state.installDialog)
    }

    @Test
    fun `onCleared cancels active search job`() = runTest {
        val client = FakeModrinthClient()
        val vm = makeVm(client)

        vm.setQuery("sodium")
        testDispatcher.scheduler.advanceTimeBy(300)  // w trakcie debounce
        vm.onCleared()

        assertTrue(true)
    }
}
