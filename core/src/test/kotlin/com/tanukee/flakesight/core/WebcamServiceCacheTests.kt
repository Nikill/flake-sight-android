package com.tanukee.flakesight.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Port of `WebcamServiceCacheTests.swift`. Keep the two files in step (see IOS_SYNC.md).
 * `downsamplerLimitsLongestSide` belongs to the app, which decodes images.
 */
class WebcamServiceCacheTests {
    /**
     * Resolves every webcam to one stubbed image and counts resolutions. With a
     * [gate], each resolution waits until the test opens it, which lets tests
     * line up concurrent requests.
     */
    class CountingProvider(private val gate: CompletableDeferred<Unit>? = null) : WebcamProvider {
        private val count = AtomicInteger()
        val resolutions: Int get() = count.get()

        override suspend fun latestSnapshotReference(webcam: Webcam, size: SnapshotSize, zone: ZoneId): SnapshotReference {
            count.incrementAndGet()
            gate?.await()
            return SnapshotReference(webcam.id, IMAGE_URL, java.time.Instant.now())
        }
    }

    private val gmt: ZoneId = ZoneOffset.UTC
    private val thumbnail = SnapshotSize.THUMBNAIL
    private val provider = CountingProvider()
    private val clock = TestClock()
    private val webcam = testWebcam("test.cam")
    private val service = makeService(provider)

    private fun makeService(provider: CountingProvider, memoryBudget: Int = WebcamService.DEFAULT_MEMORY_BUDGET) =
        WebcamService(
            http = StubHttpClient(mapOf(IMAGE_URL to imageResponse())),
            registry = WebcamProviderRegistry(mapOf(WebcamProviderType.DIRECT_IMAGE to provider)),
            now = { clock.now },
            memoryBudget = memoryBudget,
        )

    // Throttle

    @Test
    fun reusesRecentSnapshot() = runBlocking<Unit> {
        service.latestSnapshot(webcam, thumbnail, gmt)
        clock.advance(Duration.ofSeconds(60))
        service.latestSnapshot(webcam, thumbnail, gmt)
        assertEquals(1, provider.resolutions)
        assertNotNull(service.cachedSnapshot(webcam, thumbnail))
        assertNull(service.cachedSnapshot(webcam, SnapshotSize.FULL))
    }

    @Test
    fun refetchesAfterMinimumInterval() = runBlocking<Unit> {
        service.latestSnapshot(webcam, thumbnail, gmt)
        clock.advance(WebcamService.MINIMUM_REFRESH_INTERVAL.plusSeconds(1))
        service.latestSnapshot(webcam, thumbnail, gmt)
        assertEquals(2, provider.resolutions)
    }

    @Test
    fun forceRefreshBypassesCache() = runBlocking<Unit> {
        service.latestSnapshot(webcam, thumbnail, gmt)
        service.latestSnapshot(webcam, thumbnail, gmt, forceRefresh = true)
        assertEquals(2, provider.resolutions)
    }

    // Memory budget

    /** Room for two images, not three. */
    private val twoImageBudget = testImage.size * 2 + testImage.size / 2
    private val a = testWebcam("test.a")
    private val b = testWebcam("test.b")
    private val c = testWebcam("test.c")

    @Test
    fun evictsLeastRecentlyUsedBeyondBudget() = runBlocking<Unit> {
        val service = makeService(provider, twoImageBudget)
        for (webcam in listOf(a, b, c)) service.latestSnapshot(webcam, thumbnail, gmt)

        assertNull(service.cachedSnapshot(a, thumbnail))
        assertNotNull(service.cachedSnapshot(b, thumbnail))
        assertNotNull(service.cachedSnapshot(c, thumbnail))
        assertEquals(testImage.size * 2, service.cachedByteCount)
    }

    @Test
    fun readingKeepsAnEntryAlive() = runBlocking<Unit> {
        val service = makeService(provider, twoImageBudget)
        service.latestSnapshot(a, thumbnail, gmt)
        service.latestSnapshot(b, thumbnail, gmt)
        service.cachedSnapshot(a, thumbnail)
        service.latestSnapshot(c, thumbnail, gmt)

        assertNotNull(service.cachedSnapshot(a, thumbnail))
        assertNull(service.cachedSnapshot(b, thumbnail))
    }

    @Test
    fun oversizedSnapshotIsStillCached() = runBlocking<Unit> {
        val service = makeService(provider, memoryBudget = 1)
        val snapshot = service.latestSnapshot(webcam, thumbnail, gmt)
        assertContentEquals(testImage, snapshot.imageData)
        assertEquals(snapshot, service.cachedSnapshot(webcam, thumbnail))
    }

    @Test
    fun evictedImageIsRedownloadedWithoutResolvingAgain() = runBlocking<Unit> {
        val service = makeService(provider, twoImageBudget)
        val first = service.latestSnapshot(a, thumbnail, gmt)
        service.latestSnapshot(b, thumbnail, gmt)
        service.latestSnapshot(c, thumbnail, gmt)
        assertNull(service.cachedSnapshot(a, thumbnail))

        clock.advance(Duration.ofSeconds(60))
        val again = service.latestSnapshot(a, thumbnail, gmt)
        assertEquals(3, provider.resolutions, "The fresh resolution of A is reused")
        assertEquals(first.imageURL, again.imageURL)
        assertTrue(again.fetchedAt > first.fetchedAt)
        assertNotNull(service.cachedSnapshot(a, thumbnail))
    }

    // Coalescing

    @Test
    fun concurrentRequestsShareOneDownload() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val provider = CountingProvider(gate)
        val service = makeService(provider)

        val first = async { service.latestSnapshot(webcam, thumbnail, gmt) }
        val second = async { service.latestSnapshot(webcam, thumbnail, gmt) }
        waitUntil { provider.resolutions == 1 }
        gate.complete(Unit)

        assertSame(first.await(), second.await())
        assertEquals(1, provider.resolutions)
        assertEquals(0, service.inFlightCount)
    }

    @Test
    fun forcedRefreshDoesNotJoinARunningDownload() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val provider = CountingProvider(gate)
        val service = makeService(provider)

        val normal = async { service.latestSnapshot(webcam, thumbnail, gmt) }
        waitUntil { provider.resolutions == 1 }
        val forced = async { service.latestSnapshot(webcam, thumbnail, gmt, forceRefresh = true) }
        waitUntil { provider.resolutions == 2 }
        gate.complete(Unit)

        normal.await()
        forced.await()
        assertEquals(2, provider.resolutions)
        assertEquals(0, service.inFlightCount)
    }

    @Test
    fun cancellingTheFirstCallerKeepsTheDownloadForOthers() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val provider = CountingProvider(gate)
        val service = makeService(provider)

        val owner = async { service.latestSnapshot(webcam, thumbnail, gmt) }
        waitUntil { provider.resolutions == 1 }
        val joiner = async { service.latestSnapshot(webcam, thumbnail, gmt) }
        waitUntil { service.inFlightCount == 1 }
        owner.cancel()
        gate.complete(Unit)

        assertContentEquals(testImage, joiner.await().imageData)
        assertFailsWith<CancellationException> { owner.await() }
        assertEquals(1, provider.resolutions)
        assertEquals(0, service.inFlightCount)
    }

    private companion object {
        const val IMAGE_URL = "stub://images/cache.png"
    }
}
