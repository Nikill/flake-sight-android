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

    /** Port of `WebcamEntityQuery.entities(matching:)` and `suggestedEntities()`, which iOS leaves untested. */
    @Test
    fun widgetWebcamsMatchResortsAndWebcamNames() {
        val all = repository.widgetWebcams("")
        assertEquals(repository.resorts.filter { it.webcams.isNotEmpty() }.map { it.id }, all.map { it.first.id })
        assertEquals(repository.resorts.sumOf { it.webcams.size }, all.sumOf { it.second.size })

        // An alternate name lists every webcam of the resort.
        val septLaux = repository.widgetWebcams("Sept Laux")
        assertEquals(listOf("les-7-laux"), septLaux.map { it.first.id })
        assertEquals(repository.resort("les-7-laux")!!.webcams, septLaux.single().second)

        // A webcam name lists that webcam only.
        val saisies = repository.resort("les-saisies")!!
        val webcam = saisies.webcams.last()
        val byWebcam = repository.widgetWebcams(webcam.name).first { it.first.id == saisies.id }
        assertEquals(saisies.webcams.filter { it.name.searchContains(webcam.name) }, byWebcam.second)

        assertEquals(emptyList(), repository.widgetWebcams("zzz no such webcam"))
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
