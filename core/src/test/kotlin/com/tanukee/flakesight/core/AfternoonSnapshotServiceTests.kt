package com.tanukee.flakesight.core

import com.tanukee.flakesight.core.AfternoonWindowTests.Companion.paris
import com.tanukee.flakesight.core.AfternoonWindowTests.Companion.thursday
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of `AfternoonSnapshotServiceTests.swift`. Keep the two files in step. */
class AfternoonSnapshotServiceTests {
    /** Serves "now" only, with the current clock time as capture time. */
    private inner class LiveOnlyProvider : WebcamProvider {
        override suspend fun latestSnapshotReference(webcam: Webcam, size: SnapshotSize, zone: ZoneId) =
            SnapshotReference(webcam.id, LIVE_URL, clock.now)
    }

    /** Keeps every capture: each day's image exists unless listed in [missingDays]. */
    private class ArchivedProvider(private val missingDays: Set<LocalDate> = emptySet()) : WebcamProvider {
        val lookups = AtomicInteger()

        override val keepsArchive: Boolean get() = true

        override suspend fun latestSnapshotReference(webcam: Webcam, size: SnapshotSize, zone: ZoneId): SnapshotReference =
            throw WebcamError.LatestImageNotFound()

        override suspend fun archivedSnapshotReference(webcam: Webcam, target: Instant, size: SnapshotSize, zone: ZoneId): SnapshotReference? {
            lookups.incrementAndGet()
            if (AfternoonWindow.day(target, zone) in missingDays) return null
            return SnapshotReference(webcam.id, ARCHIVED_URL, target.minusSeconds(600))
        }
    }

    private companion object {
        const val LIVE_URL = "https://example.com/live.png"
        const val ARCHIVED_URL = "https://example.com/archived.png"

        fun webcam(id: String, provider: WebcamProviderType) = testWebcam(id).copy(provider = provider)

        fun resort(id: String, webcams: List<Webcam>) = Resort(
            id = id, name = id, country = "FR", region = "Test", latitude = 45.7, longitude = 6.5,
            timeZoneIdentifier = "Europe/Paris", websiteURL = "https://example.com", summitAltitude = 2500, webcams = webcams,
        )

        val favorite = resort("fav", listOf(webcam("fav.direct", WebcamProviderType.DIRECT_IMAGE), webcam("fav.skaping", WebcamProviderType.SKAPING)))
        val other = resort("other", listOf(webcam("other.direct", WebcamProviderType.DIRECT_IMAGE)))
    }

    private val clock = TestClock(AfternoonWindowTests.paris(30, 8))
    private val store = AfternoonSnapshotStore(Files.createTempDirectory("AfternoonSnapshotServiceTests").toFile())
    private val http = StubHttpClient(mapOf(LIVE_URL to imageResponse(), ARCHIVED_URL to imageResponse()))

    private fun makeService(archive: ArchivedProvider = ArchivedProvider()): Pair<AfternoonSnapshotService, WebcamService> {
        val registry = WebcamProviderRegistry(
            mapOf(WebcamProviderType.DIRECT_IMAGE to LiveOnlyProvider(), WebcamProviderType.SKAPING to archive),
        )
        val service = AfternoonSnapshotService(store, http, registry, now = { clock.now })
        service.setFavoriteResortIDs(listOf("fav"))
        val webcams = WebcamService(http, registry, now = { clock.now }, onDownload = { snapshot, webcam, zone ->
            service.record(snapshot, webcam, zone)
        })
        return service to webcams
    }

    @AfterTest
    fun cleanUp() = store.removeAll()

    // Recording

    @Test
    fun downloadsNearSixteenHundredAreKeptForFavouritesOnly() = runBlocking<Unit> {
        val (service, webcams) = makeService()
        clock.now = AfternoonWindowTests.paris(29, 15, 50)

        webcams.latestSnapshot(favorite.webcams[0], favorite, SnapshotSize.FULL)
        webcams.latestSnapshot(other.webcams[0], other, SnapshotSize.FULL)

        assertEquals(listOf(thursday), store.days("fav.direct"))
        assertTrue(store.days("other.direct").isEmpty(), "Not a favourite")

        clock.now = AfternoonWindowTests.paris(30, 8)
        assertEquals(listOf(thursday), service.availableDays(favorite.webcams[0], favorite))
        val stored = assertNotNull(service.snapshot(favorite.webcams[0], favorite, thursday))
        assertEquals(AfternoonWindowTests.paris(29, 15, 50), stored.capturedAt)
    }

    @Test
    fun morningDownloadsAreNotKept() = runBlocking<Unit> {
        val (_, webcams) = makeService()
        webcams.latestSnapshot(favorite.webcams[0], favorite, SnapshotSize.FULL)
        assertTrue(store.days("fav.direct").isEmpty())
    }

    @Test
    fun archivedWebcamsAreNeverStored() {
        val (service, _) = makeService()
        val snapshot = WebcamSnapshot("fav.skaping", "https://example.com/a.jpg", testImage, AfternoonWindowTests.thursday4pm, Instant.now())
        service.record(snapshot, favorite.webcams[1], paris)
        assertTrue(store.days("fav.skaping").isEmpty())
    }

    @Test
    fun captureFavoritesFetchesOnlyWebcamsWithoutArchive() = runBlocking<Unit> {
        val archive = ArchivedProvider()
        val (service, webcams) = makeService(archive)
        clock.now = AfternoonWindowTests.paris(29, 15, 45)

        service.captureFavorites(listOf(favorite), webcams)

        assertEquals(listOf(thursday), store.days("fav.direct"), "Kept although pruned before 16:10")
        assertTrue(store.days("fav.skaping").isEmpty())
        assertEquals(0, archive.lookups.get())
    }

    @Test
    fun pruneForgetsOldDaysAndFormerFavourites() {
        val (service, _) = makeService()
        for ((id, day) in listOf("fav.direct" to 29, "fav.direct" to 20, "other.direct" to 29)) {
            store.offer(WebcamSnapshot(id, "https://example.com/$id.jpg", testImage, AfternoonWindowTests.paris(day, 16), Instant.now()), paris)
        }

        service.prune(listOf(favorite))

        assertEquals(listOf(thursday), store.days("fav.direct"))
        assertTrue(store.days("other.direct").isEmpty())
    }

    // Archives

    @Test
    fun archivedWebcamsOfferEveryRecentDay() {
        val (service, _) = makeService()
        assertEquals(AfternoonWindow.recentDays(clock.now, paris), service.availableDays(favorite.webcams[1], favorite))
        assertTrue(service.hasAfternoons(favorite.webcams[1]))
    }

    @Test
    fun archivedImagesAreLookedUpOnceAndLabelledWithTheirCaptureTime() = runBlocking<Unit> {
        val archive = ArchivedProvider()
        val (service, _) = makeService(archive)
        val webcam = favorite.webcams[1]

        val first = assertNotNull(service.snapshot(webcam, favorite, thursday))
        assertEquals(thursday, first.day)
        assertEquals(AfternoonWindowTests.paris(29, 15, 50), first.capturedAt)
        assertTrue(first.imageData.contentEquals(testImage))

        service.snapshot(webcam, favorite, thursday)
        assertEquals(1, archive.lookups.get(), "Kept in memory for the session")
    }

    @Test
    fun aDayWithoutArchivedImageIsNil() = runBlocking<Unit> {
        val (service, _) = makeService(ArchivedProvider(missingDays = setOf(thursday)))
        assertNull(service.snapshot(favorite.webcams[1], favorite, thursday))
    }

    @Test
    fun nonFavouritesWithoutArchiveHaveNoAfternoons() {
        val (service, _) = makeService()
        assertFalse(service.hasAfternoons(other.webcams[0]))
        assertTrue(service.hasAfternoons(favorite.webcams[0]))
    }
}
