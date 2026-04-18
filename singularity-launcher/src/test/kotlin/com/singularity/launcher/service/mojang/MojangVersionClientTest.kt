package com.singularity.launcher.service.mojang

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MojangVersionClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun clientWithMock(mockEngine: MockEngine): HttpClient = HttpClient(mockEngine) {
        install(ContentNegotiation) { json(json) }
    }

    private val fakeManifestJson = """
        {
          "latest": { "release": "1.20.4", "snapshot": "24w10a" },
          "versions": [
            {
              "id": "1.20.4",
              "type": "release",
              "url": "https://piston-meta.mojang.com/v1/packages/abc/1.20.4.json",
              "time": "2024-01-01T00:00:00+00:00",
              "releaseTime": "2024-01-01T00:00:00+00:00",
              "sha1": "abcdef"
            },
            {
              "id": "24w10a",
              "type": "snapshot",
              "url": "https://piston-meta.mojang.com/v1/packages/def/24w10a.json",
              "time": "2024-02-01T00:00:00+00:00",
              "releaseTime": "2024-02-01T00:00:00+00:00",
              "sha1": "fedcba"
            }
          ]
        }
    """.trimIndent()

    private val fakeVersionDetails = """
        {
          "id": "1.20.4",
          "type": "release",
          "mainClass": "net.minecraft.client.main.Main",
          "minimumLauncherVersion": 21,
          "assetIndex": {
            "id": "12",
            "sha1": "indexsha",
            "size": 100,
            "totalSize": 1000,
            "url": "https://resources.download.minecraft.net/assets/indexes/12.json"
          },
          "libraries": [
            {
              "name": "com.mojang:brigadier:1.1.8",
              "downloads": {
                "artifact": {
                  "path": "com/mojang/brigadier/1.1.8/brigadier-1.1.8.jar",
                  "sha1": "libsha",
                  "size": 500,
                  "url": "https://libraries.minecraft.net/com/mojang/brigadier/1.1.8/brigadier-1.1.8.jar"
                }
              }
            }
          ],
          "downloads": {
            "client": {
              "sha1": "clientsha",
              "size": 10000000,
              "url": "https://piston-data.mojang.com/v1/objects/abc/client.jar"
            }
          }
        }
    """.trimIndent()

    @Test
    fun `fetchManifest parses version list`() = runTest {
        val engine = MockEngine {
            respond(fakeManifestJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = MojangVersionClient(clientWithMock(engine))
        val result = client.fetchManifest()
        assertTrue(result.isSuccess)
        val manifest = result.getOrNull()!!
        assertEquals("1.20.4", manifest.latest.release)
        assertEquals(2, manifest.versions.size)
        assertEquals("release", manifest.versions[0].type)
    }

    @Test
    fun `fetchManifest HTTP 500 returns failure`() = runTest {
        val engine = MockEngine {
            respond("", HttpStatusCode.InternalServerError)
        }
        val client = MojangVersionClient(clientWithMock(engine))
        val result = client.fetchManifest()
        assertTrue(result.isFailure)
    }

    @Test
    fun `fetchVersionDetails parses full version JSON`() = runTest {
        val engine = MockEngine {
            respond(fakeVersionDetails, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = MojangVersionClient(clientWithMock(engine))
        val mv = ManifestVersion(
            id = "1.20.4",
            type = "release",
            url = "https://piston-meta.mojang.com/v1/packages/abc/1.20.4.json",
            time = "2024-01-01T00:00:00+00:00",
            releaseTime = "2024-01-01T00:00:00+00:00",
            sha1 = "abcdef"
        )
        val result = client.fetchVersionDetails(mv)
        assertTrue(result.isSuccess)
        val details = result.getOrNull()!!
        assertEquals("net.minecraft.client.main.Main", details.mainClass)
        assertEquals("12", details.assetIndex.id)
        assertEquals(1, details.libraries.size)
        assertEquals("com.mojang:brigadier:1.1.8", details.libraries[0].name)
    }

    @Test
    fun `fetchVersionDetails by versionId — two-step flow`() = runTest {
        var callIdx = 0
        val engine = MockEngine { request ->
            callIdx++
            if (callIdx == 1) {
                respond(fakeManifestJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(fakeVersionDetails, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = MojangVersionClient(clientWithMock(engine))
        val result = client.fetchVersionDetails("1.20.4")
        assertTrue(result.isSuccess)
        assertEquals(2, callIdx, "Two HTTP calls: manifest + version details")
    }

    @Test
    fun `fetchVersionDetails unknown versionId returns failure`() = runTest {
        val engine = MockEngine {
            respond(fakeManifestJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = MojangVersionClient(clientWithMock(engine))
        val result = client.fetchVersionDetails("99.99.99")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not found"))
    }

    @Test
    fun `fetchReleaseVersions filters out snapshots`() = runTest {
        val engine = MockEngine {
            respond(fakeManifestJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = MojangVersionClient(clientWithMock(engine))
        val result = client.fetchReleaseVersions()
        assertTrue(result.isSuccess)
        val releases = result.getOrNull()!!
        assertEquals(1, releases.size)
        assertEquals("1.20.4", releases[0].id)
    }

    @Test
    fun `network error returns failure`() = runTest {
        val engine = MockEngine { throw java.io.IOException("No connection") }
        val client = MojangVersionClient(clientWithMock(engine))
        val result = client.fetchManifest()
        assertTrue(result.isFailure)
    }

    @Test
    fun `fetchVersionDetails with Library rules (OS-specific)`() = runTest {
        val osRulesJson = fakeVersionDetails.replace(
            "\"libraries\": [",
            """
            "libraries": [
              {
                "name": "org.lwjgl:lwjgl-natives-linux:3.3.1",
                "rules": [{"action": "allow", "os": {"name": "linux"}}],
                "downloads": {
                  "artifact": {
                    "path": "org/lwjgl/lwjgl-natives-linux/3.3.1/lwjgl-natives-linux-3.3.1.jar",
                    "sha1": "natsha",
                    "size": 200,
                    "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl-natives-linux/3.3.1/lwjgl-natives-linux-3.3.1.jar"
                  }
                }
              },
            """.trimIndent()
        )
        val engine = MockEngine {
            respond(osRulesJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = MojangVersionClient(clientWithMock(engine))
        val mv = ManifestVersion("1.20.4", "release", "https://x", "t", "t", "s")
        val result = client.fetchVersionDetails(mv)
        assertTrue(result.isSuccess)
        val details = result.getOrNull()!!
        assertEquals(2, details.libraries.size)
        assertNotNull(details.libraries[0].rules)
        assertEquals("allow", details.libraries[0].rules!![0].action)
    }

    // --- Offline mode gate (spec §4.11) ---

    @Test
    fun `fetchManifest returns empty manifest without hitting Mojang when OfflineMode enabled`() = runTest {
        var networkHit = false
        val engine = MockEngine { request ->
            networkHit = true
            respond("""{"latest":{"release":"x","snapshot":"y"},"versions":[]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = MojangVersionClient(clientWithMock(engine))

        com.singularity.launcher.config.OfflineMode.parseArgs(arrayOf("--offline"))
        try {
            val result = client.fetchManifest()
            assertTrue(result.isSuccess, "offline path returns Result.success with empty manifest, not failure")
            val manifest = result.getOrNull()!!
            assertEquals(emptyList<ManifestVersion>(), manifest.versions)
            // `latest` fields are ""/"" placeholders — UI treats empty
            // list as "no versions available" which is the gate signal.
            assertEquals("", manifest.latest.release)
            assertFalse(networkHit, "must NOT hit piston-meta.mojang.com when offline")
        } finally {
            com.singularity.launcher.config.OfflineMode.reset()
        }
    }

    @Test
    fun `fetchReleaseVersions returns empty list via empty manifest when OfflineMode enabled`() = runTest {
        // fetchReleaseVersions internally calls fetchManifest + filters —
        // must also honour the offline gate transitively.
        var networkHit = false
        val engine = MockEngine {
            networkHit = true
            respond("""{"latest":{"release":"x","snapshot":"y"},"versions":[]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = MojangVersionClient(clientWithMock(engine))

        com.singularity.launcher.config.OfflineMode.parseArgs(arrayOf("--offline"))
        try {
            val result = client.fetchReleaseVersions()
            assertTrue(result.isSuccess)
            assertEquals(emptyList<ManifestVersion>(), result.getOrNull())
            assertFalse(networkHit)
        } finally {
            com.singularity.launcher.config.OfflineMode.reset()
        }
    }
}
