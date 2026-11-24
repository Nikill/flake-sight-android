package com.tanukee.flakesight.core

import com.tanukee.flakesight.core.AfternoonWindowTests.Companion.paris
import com.tanukee.flakesight.core.AfternoonWindowTests.Companion.thursday4pm
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of `ArchiveProviderTests.swift`: finding past captures on Skaping and Roundshot storage. */
class ArchiveProviderTests {
    @Test
    fun onlyArchivedProvidersSayTheyKeepAnArchive() {
        val registry = WebcamProviderRegistry.standard(StubHttpClient(emptyMap()))
        assertTrue(registry.provider(WebcamProviderType.SKAPING).keepsArchive)
        assertTrue(registry.provider(WebcamProviderType.ROUNDSHOT).keepsArchive)
        assertFalse(registry.provider(WebcamProviderType.WEBCAM_HD).keepsArchive)
        assertFalse(registry.provider(WebcamProviderType.DIRECT_IMAGE).keepsArchive)
        assertFalse(registry.provider(WebcamProviderType.VIEWSURF).keepsArchive)
    }

    // Skaping

    @Test
    fun skapingArchiveURLFollowsTheStorageLayout() {
        val html = """<img src="https://skaping.s3.gra.io.cloud.ovh.net/les-saisies/le-manant/2026/10/30/large/08-30.jpg">"""
        val latest = assertNotNull(SkapingProvider.latestImage(html, "les-saisies/le-manant", paris))
        val base = SkapingProvider.storageBase(latest)
        assertEquals("https://skaping.s3.gra.io.cloud.ovh.net/les-saisies/le-manant/", base)

        assertEquals(
            "https://skaping.s3.gra.io.cloud.ovh.net/les-saisies/le-manant/2026/10/29/large/16-00.jpg",
            SkapingProvider.archivedImageURL(base, thursday4pm, SnapshotSize.THUMBNAIL, paris),
        )
        assertEquals(
            "https://skaping.s3.gra.io.cloud.ovh.net/les-saisies/le-manant/2026/10/29/16-00.jpg",
            SkapingProvider.archivedImageURL(base, thursday4pm, SnapshotSize.FULL, paris),
        )
    }

    // Roundshot

    @Test
    fun roundshotArchiveURLFollowsTheStorageLayout() {
        val json = """{"images": [${ProviderTests.roundshotImage(datetime = 1_793_347_200, time = "08-00-00")}]}"""
        val latest = assertNotNull(RoundshotProvider.latestImage(json.encodeToByteArray(), paris))
        val base = RoundshotProvider.storageBase(latest)

        assertEquals(
            "https://storage4.roundshot.com/543bc630698b92.10661132/2026-10-29/16-00-00/2026-10-29-16-00-00_quarter.jpg",
            RoundshotProvider.archivedImageURL(base, thursday4pm, SnapshotSize.THUMBNAIL, paris),
        )
        assertTrue(RoundshotProvider.archivedImageURL(base, thursday4pm, SnapshotSize.FULL, paris).endsWith("/2026-10-29-16-00-00_half.jpg"))
        assertFalse("2026-" in base, "The base stops above the day folders")
    }

    // Probing

    @Test
    fun candidatesStartAtTheTargetAndStayInTheWindow() {
        val times = ArchiveProbe.times(thursday4pm)
        assertEquals(thursday4pm, times.first())
        assertEquals(times.size, times.toSet().size)
        assertTrue(times.all { Duration.between(thursday4pm, it).abs() <= AfternoonWindow.TOLERANCE })
    }

    @Test
    fun probeStopsAtTheFirstExistingCapture() = runBlocking<Unit> {
        val missing = "https://archive.example/probe-stop/16-00.jpg"
        val found = "https://archive.example/probe-stop/15-30.jpg"
        val never = "https://archive.example/probe-stop/16-30.jpg"
        val http = StubHttpClient(mapOf(missing to status(403), found to imageResponse(), never to imageResponse()))
        val result = ArchiveProbe.firstExisting(
            listOf(missing to thursday4pm, found to thursday4pm.minusSeconds(1800), never to thursday4pm.plusSeconds(1800)),
            http,
        )
        assertEquals(found, result?.first)
        assertEquals(thursday4pm.minusSeconds(1800), result?.second)
        assertEquals(0, http.requestCount(never))
    }

    @Test
    fun probeFindsNothingWhenEveryCaptureIsMissing() = runBlocking<Unit> {
        val urls = listOf("16-00", "15-30").map { "https://archive.example/probe-none/$it.jpg" }
        val http = StubHttpClient(mapOf(urls[0] to status(404), urls[1] to status(403)))
        assertNull(ArchiveProbe.firstExisting(urls.map { it to thursday4pm }, http))
    }

    @Test
    fun probeReportsServerErrorsInsteadOfNoImage() = runBlocking<Unit> {
        val url = "https://archive.example/probe-error/16-00.jpg"
        val error = assertFailsWith<WebcamError.BadStatusCode> {
            ArchiveProbe.firstExisting(listOf(url to thursday4pm), StubHttpClient(mapOf(url to status(503))))
        }
        assertEquals(503, error.code)
    }

    private fun status(code: Int) = HttpResponse(code, emptyMap(), ByteArray(0))
}
