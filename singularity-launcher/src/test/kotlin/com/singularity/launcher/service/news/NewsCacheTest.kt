package com.singularity.launcher.service.news

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class NewsCacheTest {

    private fun release(tag: String) = ReleaseInfo(
        tagName = tag,
        name = "Release $tag",
        changelog = "- fix",
        isPrerelease = false,
        publishedAt = Instant.parse("2026-04-14T10:00:00Z"),
        htmlUrl = "https://github.com/foo/bar",
    )

    @Test
    fun `empty cache returns null`() {
        val cache = NewsCache(ttl = Duration.ofHours(6))
        assertNull(cache.get())
    }

    @Test
    fun `put then get within TTL returns cached list`() {
        val cache = NewsCache(ttl = Duration.ofHours(6))
        val releases = listOf(release("v1.2.3"), release("v1.2.2"))
        cache.put(releases)
        assertEquals(releases, cache.get())
    }

    @Test
    fun `get after TTL returns null`() {
        val clock = MutableClock(Instant.parse("2026-04-14T10:00:00Z"))
        val cache = NewsCache(ttl = Duration.ofHours(6), clock = clock)

        cache.put(listOf(release("v1.2.3")))
        assertNotNull(cache.get())

        clock.advance(Duration.ofHours(7))
        assertNull(cache.get(), "cache entry expired after TTL")
    }

    @Test
    fun `get exactly at TTL boundary returns null (exclusive)`() {
        val clock = MutableClock(Instant.parse("2026-04-14T10:00:00Z"))
        val cache = NewsCache(ttl = Duration.ofHours(6), clock = clock)

        cache.put(listOf(release("v1.2.3")))
        clock.advance(Duration.ofHours(6))
        // Age == TTL: treated as expired (age < TTL is hit)
        assertNull(cache.get(), "age equal to TTL is treated as expired")
    }

    @Test
    fun `get just before TTL boundary returns cached (inclusive)`() {
        val clock = MutableClock(Instant.parse("2026-04-14T10:00:00Z"))
        val cache = NewsCache(ttl = Duration.ofHours(6), clock = clock)

        cache.put(listOf(release("v1.2.3")))
        clock.advance(Duration.ofHours(6).minusSeconds(1))
        assertNotNull(cache.get(), "1 second before TTL still hits")
    }

    @Test
    fun `put replaces previous cached value`() {
        val cache = NewsCache(ttl = Duration.ofHours(6))
        cache.put(listOf(release("v1.0")))
        cache.put(listOf(release("v2.0")))
        assertEquals(listOf(release("v2.0")), cache.get())
    }

    @Test
    fun `put resets TTL window (fresh cache from new put)`() {
        val clock = MutableClock(Instant.parse("2026-04-14T10:00:00Z"))
        val cache = NewsCache(ttl = Duration.ofHours(6), clock = clock)

        cache.put(listOf(release("v1.0")))
        clock.advance(Duration.ofHours(5))
        cache.put(listOf(release("v2.0")))  // reset window
        clock.advance(Duration.ofHours(5))  // total 10h since first put, but 5h since second

        assertEquals(listOf(release("v2.0")), cache.get(), "second put resets TTL, still valid")
    }

    @Test
    fun `empty list is valid cached value (distinct from null not-yet-cached)`() {
        val cache = NewsCache(ttl = Duration.ofHours(6))
        cache.put(emptyList())
        assertEquals(emptyList<ReleaseInfo>(), cache.get())
    }

    @Test
    fun `zero TTL means every get returns null (effectively no cache)`() {
        val cache = NewsCache(ttl = Duration.ZERO)
        cache.put(listOf(release("v1.0")))
        assertNull(cache.get(), "TTL=0 means entries expire immediately")
    }

    @Test
    fun `negative TTL rejected at construction (fail-fast)`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            NewsCache(ttl = Duration.ofHours(-1))
        }
        assertTrue(ex.message!!.contains("ttl"), "error mentions ttl field")
    }

    @Test
    fun `backward clock jump treated as expired (avoids indefinite staleness)`() {
        val clock = MutableClock(Instant.parse("2026-04-14T10:00:00Z"))
        val cache = NewsCache(ttl = Duration.ofHours(6), clock = clock)

        cache.put(listOf(release("v1.0")))
        // NTP correction jumps clock backward by 1 hour
        clock.advance(Duration.ofHours(-1))

        assertNull(
            cache.get(),
            "negative age (clock went backward) must be treated as expired to avoid indefinite freshness",
        )
    }

    @Test
    fun `snapshot atomicity — reader sees either full entry or null, never partial`() {
        // This test documents the AtomicReference<CacheEntry?> contract via a roundtrip.
        // True concurrent race testing would require stress testing; here we verify that
        // sequential put → get returns the exact same list reference we put (snapshot stable).
        val cache = NewsCache(ttl = Duration.ofHours(6))
        val originalList = listOf(release("v1.0"), release("v2.0"))
        cache.put(originalList)
        val fetched = cache.get()
        assertNotNull(fetched)
        assertEquals(originalList, fetched, "snapshot preserves exact list")
    }

    private class MutableClock(private var instant: Instant) : Clock() {
        override fun instant(): Instant = instant
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        fun advance(d: Duration) { instant = instant.plus(d) }
    }
}
