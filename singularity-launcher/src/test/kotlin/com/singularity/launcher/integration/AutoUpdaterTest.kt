package com.singularity.launcher.integration

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AutoUpdaterTest {

    private fun mockClient(responseBody: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = responseBody,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    @Test
    fun `checkForUpdates returns update when newer version available`() = runBlocking {
        val client = mockClient("""[
            {
                "tag_name": "v2.0.0",
                "name": "Release 2.0.0",
                "body": "New features",
                "prerelease": false,
                "html_url": "https://github.com/Echelon-Team/SingularityMC/releases/tag/v2.0.0",
                "assets": [{"name": "SingularityMC-windows.exe", "browser_download_url": "https://example.com/dl", "size": 1024}]
            }
        ]""")

        val updater = AutoUpdater(client, currentVersion = "1.0.0")
        val result = updater.checkForUpdates()

        assertNotNull(result)
        assertEquals("v2.0.0", result!!.version)
        assertEquals("New features", result.changelog)
        assertTrue(result.downloadUrl!!.contains("example.com"))
    }

    @Test
    fun `checkForUpdates returns null when current is latest`() = runBlocking {
        val client = mockClient("""[
            {
                "tag_name": "v1.0.0",
                "name": "Release 1.0.0",
                "body": "Initial",
                "prerelease": false,
                "html_url": "https://github.com/example",
                "assets": []
            }
        ]""")

        val updater = AutoUpdater(client, currentVersion = "1.0.0")
        val result = updater.checkForUpdates()
        assertNull(result)
    }

    @Test
    fun `checkForUpdates skips prerelease in STABLE channel`() = runBlocking {
        val client = mockClient("""[
            {
                "tag_name": "v2.0.0-beta.1",
                "name": "Beta",
                "body": "Beta stuff",
                "prerelease": true,
                "html_url": "https://github.com/example",
                "assets": []
            },
            {
                "tag_name": "v1.0.0",
                "name": "Stable",
                "body": "Stable",
                "prerelease": false,
                "html_url": "https://github.com/example",
                "assets": []
            }
        ]""")

        val updater = AutoUpdater(client, currentVersion = "1.0.0", channel = "STABLE")
        val result = updater.checkForUpdates()
        assertNull(result, "Should skip prerelease in STABLE, leaving 1.0.0 = current")
    }

    @Test
    fun `checkForUpdates includes prerelease in BETA channel`() = runBlocking {
        val client = mockClient("""[
            {
                "tag_name": "v2.0.0-beta.1",
                "name": "Beta",
                "body": "Beta stuff",
                "prerelease": true,
                "html_url": "https://github.com/example",
                "assets": []
            }
        ]""")

        val updater = AutoUpdater(client, currentVersion = "1.0.0", channel = "BETA")
        val result = updater.checkForUpdates()
        assertNotNull(result)
        assertEquals("v2.0.0-beta.1", result!!.version)
    }

    @Test
    fun `checkForUpdates returns null on empty releases`() = runBlocking {
        val client = mockClient("[]")
        val updater = AutoUpdater(client, currentVersion = "1.0.0")
        val result = updater.checkForUpdates()
        assertNull(result)
    }

    @Test
    fun `checkForUpdates handles network error gracefully`() = runBlocking {
        val client = mockClient("Server Error", HttpStatusCode.InternalServerError)
        val updater = AutoUpdater(client, currentVersion = "1.0.0")
        val result = updater.checkForUpdates()
        assertNull(result, "Should return null on error, not throw")
    }

    @Test
    fun `compareVersions works correctly`() {
        val updater = AutoUpdater(HttpClient(MockEngine) {
            engine { addHandler { respond("[]") } }
        }, currentVersion = "1.0.0")

        assertEquals(1, updater.compareVersions("2.0.0", "1.0.0"))
        assertEquals(-1, updater.compareVersions("1.0.0", "2.0.0"))
        assertEquals(0, updater.compareVersions("1.0.0", "1.0.0"))
        assertEquals(1, updater.compareVersions("1.1.0", "1.0.0"))
        assertEquals(1, updater.compareVersions("1.0.1", "1.0.0"))
        assertEquals(-1, updater.compareVersions("0.9.9", "1.0.0"))
    }

    @Test
    fun `compareVersions handles different length versions`() {
        val updater = AutoUpdater(HttpClient(MockEngine) {
            engine { addHandler { respond("[]") } }
        }, currentVersion = "1.0.0")

        assertEquals(1, updater.compareVersions("1.0.0.1", "1.0.0"))
        assertEquals(-1, updater.compareVersions("1.0", "1.0.0.1"))
    }
}
