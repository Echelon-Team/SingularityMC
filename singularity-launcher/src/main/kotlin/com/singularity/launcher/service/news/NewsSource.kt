package com.singularity.launcher.service.news

/**
 * Bundles [NewsRepository] + [NewsCache] together. Encoding the "both-or-neither"
 * invariant as a single optional dependency prevents the inconsistent half-wired state
 * (one null, other not) that two separate nullable constructor arguments previously
 * allowed — see HomeViewModel.loadReleases.
 *
 * Both members are required; absence of news capability is modeled by passing `null` for
 * the whole [NewsSource] in a ViewModel constructor, NOT by nulling individual members.
 */
data class NewsSource(
    val repository: NewsRepository,
    val cache: NewsCache,
)
