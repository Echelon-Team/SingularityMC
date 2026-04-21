// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.service.news

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory cache for GitHub releases with TTL (time-to-live).
 *
 * Prevents [NewsRepository] from hitting the GitHub API on every ViewModel recomposition.
 * GitHub's 60-requests/hour unauthenticated rate limit is easily saturated by chatty UI
 * code; a reasonable TTL (e.g. 1-6 hours) makes launcher-lifetime-level caching effective.
 *
 * **Semantics:**
 * - First call to [get] after construction returns null (cache empty).
 * - After [put]: [get] returns cached list until age >= TTL, then null.
 * - Age comparison is **strict less-than**: `age < TTL` hits, `age >= TTL` expires.
 * - Empty-list put is a legitimate cached value (distinct from null "not yet cached").
 * - **Backward clock jump** (NTP correction, DST, manual clock change): age becomes negative
 *   → treated as EXPIRED (force refresh). Prevents indefinite staleness if clock drifted
 *   forward at put time then corrected backward.
 *
 * **Thread safety:** atomic via single [AtomicReference] holding an immutable [CacheEntry].
 * Reader sees either a complete consistent snapshot or null; no torn reads possible.
 * Lock-free on both read and write paths.
 *
 * **Injectable clock** for deterministic tests; defaults to `Clock.systemUTC()`.
 */
class NewsCache(
    private val ttl: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(!ttl.isNegative) { "ttl must be non-negative, got $ttl" }
    }

    private data class CacheEntry(val at: Instant, val value: List<ReleaseInfo>)

    private val entry = AtomicReference<CacheEntry?>(null)

    fun put(releases: List<ReleaseInfo>) {
        entry.set(CacheEntry(clock.instant(), releases))
    }

    fun get(): List<ReleaseInfo>? {
        val snapshot = entry.get() ?: return null
        val age = Duration.between(snapshot.at, clock.instant())
        // Negative age (clock went backward) treated as expired to avoid indefinite staleness.
        return if (!age.isNegative && age < ttl) snapshot.value else null
    }
}
