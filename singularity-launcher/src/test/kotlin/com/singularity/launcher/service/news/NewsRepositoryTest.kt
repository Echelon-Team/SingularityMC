package com.singularity.launcher.service.news

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

class NewsRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun mockClient(responder: MockRequestHandler): HttpClient {
        val engine = MockEngine(responder)
        return HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
    }

    private fun releaseJson(
        tag: String,
        prerelease: Boolean = false,
        publishedAt: String = "2026-04-14T10:00:00Z",
    ) = """
        {
            "tag_name":"$tag",
            "name":"Release $tag",
            "body":"- fix",
            "prerelease":$prerelease,
            "published_at":"$publishedAt",
            "html_url":"https://github.com/foo/bar/releases/tag/$tag"
        }
    """.trimIndent()

    @Test
    fun `fetchLatestReleases filters out pre-releases and returns last N stable`() = runBlocking {
        val jsonArray = """
            [
                ${releaseJson("v1.2.3")},
                ${releaseJson("v1.2.3-beta.1", prerelease = true)},
                ${releaseJson("v1.2.2")},
                ${releaseJson("v1.2.1")},
                ${releaseJson("v1.2.0")}
            ]
        """.trimIndent()

        val client = mockClient {
            respond(
                content = ByteReadChannel(jsonArray),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = NewsRepository(client, repoOwner = "foo", repoName = "bar")

        val releases = repo.fetchLatestReleases(limit = 3)

        assertEquals(3, releases.size)
        assertEquals("v1.2.3", releases[0].tagName)
        assertEquals("v1.2.2", releases[1].tagName)
        assertEquals("v1.2.1", releases[2].tagName)
        assertTrue(releases.all { !it.isPrerelease }, "no pre-releases in stable feed")
    }

    @Test
    fun `fetchLatestReleases returns fewer than limit when not enough stables exist`() = runBlocking {
        val jsonArray = """
            [
                ${releaseJson("v1.0.0-beta.1", prerelease = true)},
                ${releaseJson("v1.0.0")}
            ]
        """.trimIndent()

        val client = mockClient {
            respond(
                content = ByteReadChannel(jsonArray),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = NewsRepository(client, "foo", "bar")

        val releases = repo.fetchLatestReleases(limit = 3)

        assertEquals(1, releases.size)
        assertEquals("v1.0.0", releases[0].tagName)
    }

    @Test
    fun `fetchLatestReleases returns empty list on HTTP 5xx error`() = runBlocking {
        val client = mockClient {
            respond("Internal Server Error", HttpStatusCode.InternalServerError)
        }
        assertTrue(NewsRepository(client, "foo", "bar").fetchLatestReleases().isEmpty())
    }

    @Test
    fun `fetchLatestReleases returns empty list on HTTP 403 rate limit`() = runBlocking {
        val client = mockClient {
            respond("API rate limit exceeded", HttpStatusCode.Forbidden)
        }
        assertTrue(NewsRepository(client, "foo", "bar").fetchLatestReleases().isEmpty())
    }

    @Test
    fun `fetchLatestReleases returns empty list on network exception`() = runBlocking {
        val client = mockClient {
            throw java.net.UnknownHostException("api.github.com")
        }
        assertTrue(NewsRepository(client, "foo", "bar").fetchLatestReleases().isEmpty())
    }

    @Test
    fun `fetchLatestReleases returns empty list on malformed JSON body`() = runBlocking {
        val client = mockClient {
            respond(
                content = ByteReadChannel("not a json array"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        assertTrue(NewsRepository(client, "foo", "bar").fetchLatestReleases().isEmpty())
    }

    @Test
    fun `fetchLatestReleases returns empty list on HTML body with 200 (corporate proxy intercept)`() = runBlocking {
        val client = mockClient {
            respond(
                content = ByteReadChannel("<html>Proxy authentication required</html>"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        assertTrue(NewsRepository(client, "foo", "bar").fetchLatestReleases().isEmpty())
    }

    @Test
    fun `fetchLatestReleases handles empty response array gracefully`() = runBlocking {
        val client = mockClient {
            respond(
                content = ByteReadChannel("[]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        assertTrue(NewsRepository(client, "foo", "bar").fetchLatestReleases().isEmpty())
    }

    @Test
    fun `fetchLatestReleases uses exact URL path and required headers`() = runBlocking {
        var capturedUrl: String? = null
        var capturedAccept: String? = null
        var capturedUserAgent: String? = null
        var capturedApiVersion: String? = null
        val client = mockClient { request ->
            capturedUrl = request.url.toString()
            capturedAccept = request.headers[HttpHeaders.Accept]
            capturedUserAgent = request.headers[HttpHeaders.UserAgent]
            capturedApiVersion = request.headers["X-GitHub-Api-Version"]
            respond(
                content = ByteReadChannel("[]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = NewsRepository(client, repoOwner = "Echelon-Team", repoName = "SingularityMC")

        repo.fetchLatestReleases()

        assertEquals(
            "https://api.github.com/repos/Echelon-Team/SingularityMC/releases?per_page=20",
            capturedUrl,
            "URL pinned: per_page=20, exact path structure",
        )
        assertEquals("application/vnd.github+json", capturedAccept)
        assertEquals("SingularityMC-Launcher", capturedUserAgent, "GitHub API requires User-Agent")
        assertEquals("2022-11-28", capturedApiVersion, "API version pinned for forward compat")
    }

    @Test
    fun `fetchLatestReleases rethrows CancellationException (structured concurrency)`() = runBlocking {
        val client = mockClient {
            throw kotlinx.coroutines.CancellationException("test cancel")
        }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { NewsRepository(client, "foo", "bar").fetchLatestReleases() }
        }
    }

    // === ReleaseInfo helpers ===

    @Test
    fun `ReleaseInfo publishedAt deserializes as Instant directly (via InstantSerializer)`() = runBlocking {
        val jsonArray = "[${releaseJson("v1.2.3", publishedAt = "2026-04-14T10:00:00Z")}]"
        val client = mockClient {
            respond(
                content = ByteReadChannel(jsonArray),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val releases = NewsRepository(client, "foo", "bar").fetchLatestReleases()

        assertEquals(1, releases.size)
        assertEquals(Instant.parse("2026-04-14T10:00:00Z"), releases[0].publishedAt)
    }

    @Test
    fun `ReleaseInfo publishedLocalDate converts to system-zone LocalDate`() {
        val release = ReleaseInfo(
            tagName = "v1.2.3",
            name = "Release",
            changelog = "- fix",
            isPrerelease = false,
            publishedAt = Instant.parse("2026-04-14T10:00:00Z"),
            htmlUrl = "https://github.com/foo/bar",
        )
        val date = release.publishedLocalDate
        assertNotNull(date)
        // Date is timezone-dependent but must be 14th or 15th April 2026
        assertEquals(2026, date.year)
        assertEquals(4, date.monthValue)
        assertTrue(date.dayOfMonth in 14..15, "must fall within UTC/system zone bounds")
    }

    @Test
    fun `ReleaseInfo displayVersion strips leading v prefix when followed by digit`() {
        val release = ReleaseInfo(
            tagName = "v1.2.3",
            name = "Release",
            changelog = "",
            isPrerelease = false,
            publishedAt = Instant.now(),
            htmlUrl = "",
        )
        assertEquals("1.2.3", release.displayVersion)
    }

    @Test
    fun `ReleaseInfo displayVersion preserves tag without v prefix`() {
        val release = ReleaseInfo(
            tagName = "1.2.3",
            name = "Release",
            changelog = "",
            isPrerelease = false,
            publishedAt = Instant.now(),
            htmlUrl = "",
        )
        assertEquals("1.2.3", release.displayVersion)
    }

    @Test
    fun `ReleaseInfo displayVersion does NOT mangle tags where v is not version prefix`() {
        // "vanilla-1.0" contains leading "v" but not followed by digit — must not strip.
        val release = ReleaseInfo(
            tagName = "vanilla-1.0",
            name = "Release",
            changelog = "",
            isPrerelease = false,
            publishedAt = Instant.now(),
            htmlUrl = "",
        )
        assertEquals("vanilla-1.0", release.displayVersion, "no false-positive strip")
    }

    @Test
    fun `ReleaseInfo changelog field holds markdown body content`() {
        val release = ReleaseInfo(
            tagName = "v1.0",
            name = "Release",
            changelog = "- Fixed bug\n- Added feature",
            isPrerelease = false,
            publishedAt = Instant.now(),
            htmlUrl = "",
        )
        assertEquals("- Fixed bug\n- Added feature", release.changelog)
    }
}
