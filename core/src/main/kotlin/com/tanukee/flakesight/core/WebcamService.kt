package com.tanukee.flakesight.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Single entry point the app (and later widgets, Wear OS and Android Auto) uses
 * to get webcam content, whichever provider hosts the webcam. Port of
 * `WebcamService` in SkiPatrolKit.
 *
 * @param memoryBudget Bytes of image data kept in memory, least recently used
 *   dropped first; the HTTP cache still has them.
 * @param onDownload Told about every image downloaded from a provider (not
 *   about cache hits), e.g. to keep afternoon images.
 */
class WebcamService(
    private val http: HttpClient = UrlConnectionHttpClient,
    registry: WebcamProviderRegistry? = null,
    private val now: () -> Instant = Instant::now,
    memoryBudget: Int = DEFAULT_MEMORY_BUDGET,
    private val onDownload: ((WebcamSnapshot, Webcam, ZoneId) -> Unit)? = null,
) {
    private data class CacheKey(val webcamID: String, val size: SnapshotSize)

    /**
     * Where a webcam's latest image lives, and when the provider said so. Kept
     * apart from the image so the refresh throttle survives an eviction.
     */
    private class ResolvedReference(val reference: SnapshotReference, val resolvedAt: Instant)

    private val registry = registry ?: WebcamProviderRegistry.standard(http)
    private val lock = Any()
    private val references = mutableMapOf<CacheKey, ResolvedReference>()
    private val snapshots = SnapshotCache<CacheKey>(memoryBudget)

    /** Downloads under way, so concurrent requests for the same image share one. */
    private val inFlight = mutableMapOf<CacheKey, Deferred<WebcamSnapshot>>()

    /** Shared downloads outlive the callers that started them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The most recent snapshot still in memory, so a screen can show it while a fresher one loads. */
    fun cachedSnapshot(webcam: Webcam, size: SnapshotSize): WebcamSnapshot? =
        synchronized(lock) { snapshots[CacheKey(webcam.id, size)] }

    /**
     * Resolves and downloads the latest image of [webcam]. Concurrent calls for
     * the same webcam and size share one download; cancelling a caller never
     * cancels a download others wait for.
     *
     * @param forceRefresh Bypasses the memory and HTTP caches. Only for explicit
     *   user requests such as a Refresh button.
     */
    suspend fun latestSnapshot(
        webcam: Webcam,
        size: SnapshotSize,
        zone: ZoneId,
        forceRefresh: Boolean = false,
    ): WebcamSnapshot {
        val key = CacheKey(webcam.id, size)
        if (!forceRefresh) freshSnapshot(key)?.let { return it }

        // Join a load already under way, unless the user asked for a reload:
        // that one starts its own and later callers join it instead.
        val task = synchronized(lock) {
            inFlight[key]?.takeUnless { forceRefresh } ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    fetch(key, webcam, size, zone, forceRefresh)
                } finally {
                    val self = coroutineContext.job
                    synchronized(lock) { if (inFlight[key] === self) inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        task.start()
        return task.await()
    }

    /** Interprets provider timestamps in the resort's time zone. */
    suspend fun latestSnapshot(webcam: Webcam, resort: Resort, size: SnapshotSize, forceRefresh: Boolean = false) =
        latestSnapshot(webcam, size, resort.timeZone, forceRefresh)

    suspend fun liveStreamURL(webcam: Webcam): String? = registry.provider(webcam.provider).liveStreamURL(webcam)

    fun attribution(webcam: Webcam): WebcamAttribution =
        runCatching { registry.provider(webcam.provider).attribution(webcam) }.getOrDefault(webcam.attribution)

    private fun freshSnapshot(key: CacheKey): WebcamSnapshot? = synchronized(lock) {
        references[key]?.takeIf(::isFresh)?.let { snapshots[key] }
    }

    private fun isFresh(resolved: ResolvedReference) =
        Duration.between(resolved.resolvedAt, now()) < MINIMUM_REFRESH_INTERVAL

    private suspend fun fetch(
        key: CacheKey,
        webcam: Webcam,
        size: SnapshotSize,
        zone: ZoneId,
        forceRefresh: Boolean,
    ): WebcamSnapshot {
        // A fresh resolution whose image was evicted is reused as is.
        val known = if (forceRefresh) null else synchronized(lock) { references[key] }?.takeIf(::isFresh)
        val resolved = known ?: ResolvedReference(
            registry.provider(webcam.provider).latestSnapshotReference(webcam, size, zone),
            now(),
        )

        val url = resolved.reference.imageURL
        val response = withContext(Dispatchers.IO) { http.get(url, useCaches = !forceRefresh) }
        if (response.statusCode !in 200..299) throw WebcamError.BadStatusCode(response.statusCode)
        if (response.header("Content-Type")?.startsWith("image/") == false) throw WebcamError.NotAnImage()

        // Capture time, most to least precise: the provider's own metadata,
        // the camera's EXIF stamp, then the file's Last-Modified header (the
        // upload time, usually within minutes of the capture).
        val capturedAt = resolved.reference.capturedAt
            ?: Exif.captureDate(response.body, zone)
            ?: response.header("Last-Modified")?.let(::httpDate)
        val snapshot = WebcamSnapshot(webcam.id, url, response.body, capturedAt, fetchedAt = now())
        synchronized(lock) {
            references[key] = resolved
            snapshots.insert(key, snapshot)
        }
        onDownload?.invoke(snapshot, webcam, zone)
        return snapshot
    }

    // Testing hooks

    /** Downloads currently shared between callers. */
    internal val inFlightCount: Int get() = synchronized(lock) { inFlight.size }

    /** Bytes of image data held in memory. */
    internal val cachedByteCount: Int get() = synchronized(lock) { snapshots.totalBytes }

    companion object {
        /**
         * Snapshots younger than this are reused instead of contacting the provider
         * again. Cameras publish every ~10 minutes, and some provider pages are
         * `no-store`, so HTTP caching alone would not prevent repeated requests.
         */
        val MINIMUM_REFRESH_INTERVAL: Duration = Duration.ofMinutes(5)

        /** Roughly ten full panoramas, or hundreds of thumbnails. Widgets pass far less. */
        const val DEFAULT_MEMORY_BUDGET = 32 * 1024 * 1024

        private val httpDateFormats = listOf(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss z", Locale.US),
            DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US).withZone(ZoneOffset.UTC),
        )

        /**
         * Parses an HTTP date such as `Sat, 17 Oct 2026 08:30:00 GMT`, plus the two
         * obsolete formats RFC 9110 still requires recipients to accept.
         */
        internal fun httpDate(text: String): Instant? = httpDateFormats.firstNotNullOfOrNull { format ->
            runCatching { ZonedDateTime.parse(text, format).toInstant() }.getOrNull()
        }
    }
}

/**
 * Image data by key, least recently used first, within a byte budget. Full
 * panoramas weigh 1 to 5 MB each; eviction only costs a re-download, usually
 * served by the HTTP cache. Not thread-safe: [WebcamService] holds its lock.
 */
internal class SnapshotCache<K>(private val budget: Int) {
    private val entries = LinkedHashMap<K, WebcamSnapshot>(16, 0.75f, true)
    var totalBytes = 0
        private set

    /** The cached snapshot, marked as just used. */
    operator fun get(key: K): WebcamSnapshot? = entries[key]

    /** Stores [snapshot], dropping the least recently used until the budget is met. The new entry always stays. */
    fun insert(key: K, snapshot: WebcamSnapshot) {
        entries.remove(key)?.let { totalBytes -= it.imageData.size }
        entries[key] = snapshot
        totalBytes += snapshot.imageData.size
        val iterator = entries.values.iterator()
        while (totalBytes > budget && entries.size > 1) {
            totalBytes -= iterator.next().imageData.size
            iterator.remove()
        }
    }
}
