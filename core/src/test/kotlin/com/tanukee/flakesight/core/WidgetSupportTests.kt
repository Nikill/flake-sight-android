package com.tanukee.flakesight.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Port of `WidgetSupportTests.swift`. The crop tests stay with the app, which
 * crops with Android's decoder; `SharedWidgetState` and the Watch payload have
 * no Android counterpart (the widget runs in the app's own process and reads
 * the favourites file). Keep the two files in step.
 */
class WidgetSupportTests {
    private val repository = ResortRepository.bundled()

    @Test
    fun staleDateMatchesFreshnessBoundary() {
        val capturedAt = Instant.ofEpochSecond(1_800_000_000)
        val staleDate = SnapshotFreshness.staleDate(capturedAt)
        assertEquals(SnapshotFreshness.Recent(capturedAt), SnapshotFreshness.of(capturedAt, staleDate.minusSeconds(1)))
        assertEquals(SnapshotFreshness.Stale(capturedAt), SnapshotFreshness.of(capturedAt, staleDate))
    }

    @Test
    fun resolverPrefersConfiguredThenDefaultThenFirst() {
        val first = repository.resorts.first()
        val last = repository.resorts.last { it.webcams.isNotEmpty() }
        val configured = last.webcams.last()

        val chosen = WidgetWebcamResolver.resolve(configured.id, first.id, repository)
        assertEquals(configured.id, chosen?.second?.id)

        val byDefault = WidgetWebcamResolver.resolve("removed.webcam", last.id, repository)
        assertEquals(last.webcams.first().id, byDefault?.second?.id)

        val fallback = WidgetWebcamResolver.resolve(null, "unknown-resort", repository)
        assertEquals(first.webcams.first().id, fallback?.second?.id)

        // A resort catalogued without a webcam falls through too.
        val withoutWebcam = repository.resorts.first { it.webcams.isEmpty() }
        val instead = WidgetWebcamResolver.resolve(null, withoutWebcam.id, repository)
        assertEquals(first.webcams.first().id, instead?.second?.id)
    }

    @Test
    fun deepLinkRoundTrips() {
        val url = DeepLink.webcamURL("les-saisies.bisanne-1500")
        assertEquals("skipatrol://webcam/les-saisies.bisanne-1500", url)
        assertEquals("les-saisies.bisanne-1500", DeepLink.webcamID(url))
        assertNull(DeepLink.webcamID("https://example.com/webcam/x"))
        assertNull(DeepLink.webcamID("skipatrol://webcam/"))
    }
}
