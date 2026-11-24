package com.tanukee.flakesight.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Port of `FreshnessOrderingTests.swift`. Keep the two files in step (see IOS_SYNC.md). */
class FreshnessOrderingTests {
    private val now = Instant.ofEpochSecond(1_800_000_000)

    private fun webcam(id: String) = testWebcam("r.$id", name = id)

    @Test
    fun recentFirstThenUnknownThenStaleKeepingCatalogueOrder() {
        val webcams = listOf("stale1", "recent1", "loading", "stale2", "noTime", "recent2").map(::webcam)
        val freshness = mapOf(
            "r.stale1" to SnapshotFreshness.Stale(now - Duration.ofDays(3)),
            "r.recent1" to SnapshotFreshness.Recent(now.minusSeconds(600)),
            "r.loading" to null,
            "r.stale2" to SnapshotFreshness.Stale(now - Duration.ofDays(30)),
            "r.noTime" to SnapshotFreshness.Unknown,
            "r.recent2" to SnapshotFreshness.Recent(now.minusSeconds(60)),
        )
        val sorted = webcams.sortedByFreshness { freshness[it.id] }
        assertEquals(listOf("recent1", "recent2", "loading", "noTime", "stale1", "stale2"), sorted.map { it.name })
    }

    @Test
    fun nothingLoadedKeepsCatalogueOrder() {
        val webcams = listOf("a", "b", "c").map(::webcam)
        assertEquals(listOf("a", "b", "c"), webcams.sortedByFreshness { null }.map { it.name })
    }

    /** Android only: the 24-hour line between recent and stale is the capture time's, never the download's. */
    @Test
    fun imagesOlderThanADayAreStale() {
        val justRecent = now - SnapshotFreshness.STALE_INTERVAL + Duration.ofSeconds(1)
        assertEquals(SnapshotFreshness.Recent(justRecent), SnapshotFreshness.of(justRecent, now))
        val day = now - SnapshotFreshness.STALE_INTERVAL
        assertEquals(SnapshotFreshness.Stale(day), SnapshotFreshness.of(day, now))
        assertEquals(SnapshotFreshness.Unknown, SnapshotFreshness.of(null, now))
        assertEquals(now, SnapshotFreshness.staleDate(day))
    }
}
