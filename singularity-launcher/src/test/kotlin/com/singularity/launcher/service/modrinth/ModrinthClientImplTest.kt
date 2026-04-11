package com.singularity.launcher.service.modrinth

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

class ModrinthClientImplTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun clientWithMock(mockEngine: MockEngine): HttpClient = HttpClient(mockEngine) {
        install(ContentNegotiation) { json(json) }
    }

    @Test
    fun `search builds correct URL with facets`() = runTest {
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = """{"hits":[],"offset":0,"limit":20,"total_hits":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ModrinthClientImpl(clientWithMock(engine))

        client.search(
            query = "sodium",
            facets = listOf(
                listOf("project_type:mod"),
                listOf("versions:1.20.1"),
                listOf("categories:fabric")
            )
        )

        assertNotNull(capturedUrl)
        // Verify facets 2D array format in URL
        assertTrue(capturedUrl!!.contains("facets="), "URL must have facets param")
        assertTrue(capturedUrl!!.contains("project_type"))
    }

    @Test
    fun `search encodes query UTF-8`() = runTest {
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond("""{"hits":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ModrinthClientImpl(clientWithMock(engine))

        client.search(query = "płkręty")

        assertNotNull(capturedUrl)
        assertTrue(
            capturedUrl!!.contains("%") || capturedUrl!!.contains("+"),
            "UTF-8 encoded polish chars in URL"
        )
    }

    @Test
    fun `search 200 returns parsed hits`() = runTest {
        val engine = MockEngine { request ->
            respond(
                content = """
                {
                  "hits": [
                    {
                      "project_id": "AANobbMI",
                      "slug": "sodium",
                      "title": "Sodium",
                      "description": "Optimization mod",
                      "icon_url": null,
                      "downloads": 10000000,
                      "categories": ["optimization"],
                      "versions": ["1.20.1"],
                      "loaders": ["fabric"]
                    }
                  ],
                  "offset": 0,
                  "limit": 20,
                  "total_hits": 1
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ModrinthClientImpl(clientWithMock(engine))
        val result = client.search("sodium")

        assertTrue(result.isSuccess)
        val hits = result.getOrNull()!!
        assertEquals(1, hits.size)
        assertEquals("AANobbMI", hits[0].projectId)
        assertEquals("Sodium", hits[0].title)
    }

    @Test
    fun `search 429 returns RateLimitException with Retry-After`() = runTest {
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf("Retry-After", "30")
            )
        }
        val client = ModrinthClientImpl(clientWithMock(engine))
        val result = client.search("x")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is ModrinthClient.RateLimitException)
        assertEquals(30, (error as ModrinthClient.RateLimitException).retryAfterSec)
    }

    @Test
    fun `search 500 returns generic error`() = runTest {
        val engine = MockEngine {
            respond("Internal Server Error", HttpStatusCode.InternalServerError)
        }
        val client = ModrinthClientImpl(clientWithMock(engine))
        val result = client.search("x")

        assertTrue(result.isFailure)
    }

    @Test
    fun `getVersions builds URL with game_versions and loaders`() = runTest {
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ModrinthClientImpl(clientWithMock(engine))
        client.getVersions("sodium", gameVersions = listOf("1.20.1"), loaders = listOf("fabric"))

        assertNotNull(capturedUrl)
        assertTrue(capturedUrl!!.contains("/project/sodium/version"))
        assertTrue(capturedUrl!!.contains("game_versions"))
        assertTrue(capturedUrl!!.contains("loaders"))
    }

    @Test
    fun `getVersions parses version response`() = runTest {
        val engine = MockEngine {
            respond(
                """
                [
                  {
                    "id": "V123",
                    "project_id": "sodium",
                    "name": "Sodium 0.5.3",
                    "version_number": "0.5.3",
                    "game_versions": ["1.20.1"],
                    "loaders": ["fabric"],
                    "files": [
                      {
                        "url": "https://cdn.modrinth.com/sodium.jar",
                        "filename": "sodium-0.5.3.jar",
                        "size": 512000,
                        "hashes": {"sha256": "abc123"}
                      }
                    ]
                  }
                ]
                """.trimIndent(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ModrinthClientImpl(clientWithMock(engine))
        val result = client.getVersions("sodium")

        assertTrue(result.isSuccess)
        val versions = result.getOrNull()!!
        assertEquals(1, versions.size)
        assertEquals("V123", versions[0].id)
        assertEquals("0.5.3", versions[0].versionNumber)
        assertEquals(1, versions[0].files.size)
    }

    @Test
    fun `User-Agent header sent on each request`() = runTest {
        var capturedUA: String? = null
        val engine = MockEngine { request ->
            capturedUA = request.headers[HttpHeaders.UserAgent]
            respond("""{"hits":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ModrinthClientImpl(clientWithMock(engine))
        client.search("test")

        assertNotNull(capturedUA)
        assertTrue(capturedUA!!.contains("SingularityMC"))
    }

    @Test
    fun `search empty query still sends request`() = runTest {
        var called = false
        val engine = MockEngine {
            called = true
            respond("""{"hits":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ModrinthClientImpl(clientWithMock(engine))
        client.search("")

        assertTrue(called)
    }

    @Test
    fun `search sort parameter included`() = runTest {
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond("""{"hits":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ModrinthClientImpl(clientWithMock(engine))
        client.search("x", sort = "downloads")

        assertTrue(capturedUrl!!.contains("index=downloads"))
    }

    @Test
    fun `network error returns failure`() = runTest {
        val engine = MockEngine { throw java.io.IOException("Connection refused") }
        val client = ModrinthClientImpl(clientWithMock(engine))
        val result = client.search("x")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.IOException ||
                   result.exceptionOrNull()?.cause is java.io.IOException)
    }

    @Test
    fun `facets URL format is 2D array`() = runTest {
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond("""{"hits":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ModrinthClientImpl(clientWithMock(engine))
        client.search("x", facets = listOf(
            listOf("project_type:mod"),
            listOf("versions:1.20.1")
        ))

        assertNotNull(capturedUrl)
        // Format: facets=[["project_type:mod"],["versions:1.20.1"]]
        // URL-encoded — decode first to verify
        val decoded = java.net.URLDecoder.decode(capturedUrl, "UTF-8")
        assertTrue(
            decoded.contains("[[\"project_type:mod\"],[\"versions:1.20.1\"]]"),
            "2D array format: $decoded"
        )
    }
}
