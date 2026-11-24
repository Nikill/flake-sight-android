package com.tanukee.flakesight.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * When an image counts as its day's afternoon image: the one captured closest
 * to 16:00, resort time, and no further than 90 minutes from it. Port of
 * `AfternoonWindow`. Days are [LocalDate]s in the resort's time zone, which is
 * what `AfternoonDay` is on iOS.
 *
 * The tolerance keeps cameras that publish every 30 or 60 minutes, and a
 * background capture that runs at 15:10 instead of 15:45.
 */
object AfternoonWindow {
    const val TARGET_HOUR = 16
    val TOLERANCE: Duration = Duration.ofMinutes(90)

    /** Afternoon images are kept and offered for this many days. */
    const val KEPT_DAYS = 7

    /** Today joins the list this long after 16:00, once the 16:00 capture has had time to reach the provider's storage. */
    internal val PUBLICATION_DELAY: Duration = Duration.ofMinutes(10)

    /** The day [instant] falls on in [zone]. */
    fun day(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

    /** 16:00 on [day] in [zone]. */
    fun target(day: LocalDate, zone: ZoneId): Instant = day.atTime(TARGET_HOUR, 0).atZone(zone).toInstant()

    /** How far [capturedAt] is from 16:00 on its own day. */
    fun distance(capturedAt: Instant, zone: ZoneId): Duration =
        Duration.between(target(day(capturedAt, zone), zone), capturedAt).abs()

    /** Whether an image captured at [capturedAt] can be its day's afternoon image. An unknown capture time never can: the label would be a guess. */
    fun accepts(capturedAt: Instant?, zone: ZoneId): Boolean = capturedAt != null && distance(capturedAt, zone) <= TOLERANCE

    /** Whether [candidate] is closer to 16:00 than [existing], both on the same day. */
    fun isBetter(candidate: Instant, existing: Instant, zone: ZoneId): Boolean = distance(candidate, zone) < distance(existing, zone)

    /** The last [count] days with an afternoon behind them, newest first. Today is included only once its 16:00 capture can exist. */
    fun recentDays(now: Instant, zone: ZoneId, count: Int = KEPT_DAYS): List<LocalDate> {
        val today = day(now, zone)
        val todayIsOver = now >= target(today, zone) + PUBLICATION_DELAY
        val first = if (todayIsOver) 0L else 1L
        return (first until first + count).map { today.minusDays(it) }
    }
}

/**
 * The image a webcam showed closest to 16:00 on one day, so the slopes can be
 * judged at night or early in the morning. Port of `AfternoonSnapshot`.
 *
 * Always a dated picture of the past: the app labels it with its day and time
 * and never describes it as the webcam's current image.
 */
class AfternoonSnapshot(
    val webcamID: String,
    val day: LocalDate,
    val imageURL: String,
    val imageData: ByteArray,
    /** Known by construction: an image without a capture time is never kept. */
    val capturedAt: Instant,
) {
    override fun equals(other: Any?): Boolean = other is AfternoonSnapshot && webcamID == other.webcamID &&
        day == other.day && imageURL == other.imageURL && capturedAt == other.capturedAt && imageData.contentEquals(other.imageData)

    override fun hashCode(): Int = listOf(webcamID, day, imageURL, capturedAt).hashCode() * 31 + imageData.contentHashCode()
}

/**
 * Afternoon images captured on this device, one per webcam and day. Port of
 * `AfternoonSnapshotStore`.
 *
 * Only for webcams whose provider keeps no archive: their 16:00 image is gone
 * once the camera publishes the next one, so the app keeps it outside its
 * cache, where the system could purge it, and out of backups. Days and webcams
 * are read from file names, never from file dates, and pruning keeps the
 * directory bounded to [AfternoonWindow.KEPT_DAYS] per favourite.
 *
 * @param downscale The image to keep for [ByteArray] image data: itself when
 *   small enough, a smaller JPEG otherwise, `null` when it is not an image.
 *   Decoding is the app's (Android's decoder); the default keeps the data as is.
 */
class AfternoonSnapshotStore(
    val directory: File,
    private val downscale: (ByteArray) -> ByteArray? = { it },
) {
    /** What the image file does not carry itself. */
    @Serializable
    private data class Sidecar(
        val imageURL: String,
        @Serializable(with = InstantSerializer::class) val capturedAt: Instant,
    )

    /** The stored afternoon image of [webcamID] on [day], if any. */
    fun snapshot(webcamID: String, day: LocalDate): AfternoonSnapshot? {
        val (image, metadata) = files(webcamID, day)
        val sidecar = sidecar(metadata) ?: return null
        val imageData = runCatching { image.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return AfternoonSnapshot(webcamID, day, sidecar.imageURL, imageData, sidecar.capturedAt)
    }

    /** Days with a stored image for [webcamID], newest first. */
    fun days(webcamID: String): List<LocalDate> =
        folder(webcamID).list().orEmpty()
            .filter { it.endsWith(".json") }
            .mapNotNull { day(it.removeSuffix(".json")) }
            .sortedDescending()

    /** Keeps [snapshot] when it is its day's best afternoon image so far. Returns `true` when it was stored. */
    fun offer(snapshot: WebcamSnapshot, zone: ZoneId): Boolean {
        val capturedAt = snapshot.capturedAt?.takeIf { AfternoonWindow.accepts(it, zone) } ?: return false
        val (image, metadata) = files(snapshot.webcamID, AfternoonWindow.day(capturedAt, zone))
        val existing = sidecar(metadata)
        if (existing != null && !AfternoonWindow.isBetter(capturedAt, existing.capturedAt, zone)) return false
        val imageData = downscale(snapshot.imageData) ?: return false
        return runCatching {
            folder(snapshot.webcamID).mkdirs()
            image.writeBytesAtomically(imageData)
            // Written last: without it the image is ignored, never mislabelled.
            metadata.writeTextAtomically(json.encodeToString(Sidecar(snapshot.imageURL, capturedAt)))
        }.isSuccess
    }

    /** Deletes every day not in [days] and every webcam not in [webcamIDs]. */
    fun prune(days: Set<LocalDate>, webcamIDs: Set<String>) {
        for (webcamID in directory.list().orEmpty()) {
            val folder = folder(webcamID)
            if (webcamID !in webcamIDs) {
                folder.deleteRecursively()
                continue
            }
            for (name in folder.list().orEmpty()) {
                if (day(name.substringBefore('.')) !in days) File(folder, name).delete()
            }
        }
    }

    fun removeAll() {
        directory.deleteRecursively()
    }

    // IDs are slugs (`les-saisies.bisanne-1500`), safe as folder names.
    private fun folder(webcamID: String) = File(directory, webcamID)

    private fun files(webcamID: String, day: LocalDate) =
        File(folder(webcamID), "$day.image") to File(folder(webcamID), "$day.json")

    private fun sidecar(file: File): Sidecar? = runCatching { json.decodeFromString<Sidecar>(file.readText()) }.getOrNull()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** `"2026-10-29"` back to its day; anything else is not a day. */
        fun day(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()
    }
}

/**
 * Afternoon images of the last [AfternoonWindow.KEPT_DAYS] days: what each
 * webcam showed around 16:00, for when the live image is dark or old. Port of
 * `AfternoonSnapshotService`.
 *
 * Two sources, depending on the provider:
 * - Skaping and Roundshot keep every capture, so any webcam's afternoon image is
 *   looked up on their storage when asked for. Nothing is stored.
 * - Other providers only serve "now", so images downloaded near 16:00 for
 *   webcams of favourite resorts are kept on the device ([record]), and the app
 *   also asks for them in the background ([captureFavorites]).
 */
class AfternoonSnapshotService(
    private val store: AfternoonSnapshotStore,
    private val http: HttpClient = UrlConnectionHttpClient,
    registry: WebcamProviderRegistry? = null,
    private val now: () -> Instant = Instant::now,
    memoryBudget: Int = DEFAULT_MEMORY_BUDGET,
) {
    private data class ArchiveKey(val webcamID: String, val day: LocalDate)

    private val registry = registry ?: WebcamProviderRegistry.standard(http)
    private val lock = Any()
    private var favoriteResortIDs = emptySet<String>()
    private val archived = SnapshotCache<ArchiveKey>(memoryBudget)

    /** The resorts whose webcams get their afternoon images kept on the device. */
    fun setFavoriteResortIDs(ids: Iterable<String>) {
        val set = ids.toSet()
        synchronized(lock) { favoriteResortIDs = set }
    }

    /** Whether [webcam] can have afternoon images at all on this device. */
    fun hasAfternoons(webcam: Webcam): Boolean = keepsArchive(webcam) || isFavorite(webcam)

    // Recording

    /**
     * Keeps [snapshot] when it is the best afternoon image of its day so far.
     * Only for favourite resorts' webcams on providers without an archive; for
     * the others it does nothing. Pass every fresh download here.
     */
    fun record(snapshot: WebcamSnapshot, webcam: Webcam, zone: ZoneId) {
        if (keepsArchive(webcam) || !isFavorite(webcam)) return
        store.offer(snapshot, zone)
    }

    /**
     * Downloads the current image of every favourite webcam that has no archive,
     * so each keeps its afternoon image even when the app is closed. Meant for a
     * background capture near 16:00; also prunes.
     */
    suspend fun captureFavorites(resorts: List<Resort>, webcamService: WebcamService) {
        // A few at a time: a background job has limited time.
        val permits = Semaphore(4)
        coroutineScope {
            resorts.flatMap { resort -> resort.webcams.filterNot(::keepsArchive).map { it to resort } }
                .map { (webcam, resort) ->
                    async {
                        permits.withPermit {
                            // Unsupported providers and webcams without a still image simply fail.
                            runCatching { webcamService.latestSnapshot(webcam, resort, SnapshotSize.THUMBNAIL) }
                                .getOrNull()?.let { record(it, webcam, resort.timeZone) }
                        }
                    }
                }.awaitAll()
        }
        prune(resorts)
    }

    /** Drops images older than the kept days and those of webcams whose resort is no longer a favourite. */
    fun prune(favoriteResorts: List<Resort>) {
        val webcamIDs = favoriteResorts.flatMap { resort -> resort.webcams.map { it.id } }.toSet()
        // Resorts may sit in different time zones; keep any day recent in one of
        // them, and today, whose afternoon may be under way.
        val now = now()
        val days = favoriteResorts.map { it.timeZone }.distinct().flatMap { zone ->
            AfternoonWindow.recentDays(now, zone) + AfternoonWindow.day(now, zone)
        }.toSet()
        store.prune(days, webcamIDs)
    }

    // Reading

    /**
     * Days that may have an afternoon image of [webcam], newest first: every
     * recent day for archived providers, the stored days otherwise. An archived
     * day can still turn out empty (camera off).
     */
    fun availableDays(webcam: Webcam, resort: Resort): List<LocalDate> {
        val recent = AfternoonWindow.recentDays(now(), resort.timeZone)
        if (keepsArchive(webcam)) return recent
        val stored = store.days(webcam.id).toSet()
        return recent.filter { it in stored }
    }

    /** The afternoon image of [webcam] on [day], or `null` when there is none. */
    suspend fun snapshot(webcam: Webcam, resort: Resort, day: LocalDate): AfternoonSnapshot? {
        withContext(Dispatchers.IO) { store.snapshot(webcam.id, day) }?.let { return it }
        if (!keepsArchive(webcam)) return null

        val key = ArchiveKey(webcam.id, day)
        synchronized(lock) { archived[key] }?.let { return afternoon(it, day) }
        val reference = registry.provider(webcam.provider)
            .archivedSnapshotReference(webcam, AfternoonWindow.target(day, resort.timeZone), SnapshotSize.THUMBNAIL, resort.timeZone)
            ?: return null

        val response = withContext(Dispatchers.IO) { http.get(reference.imageURL, useCaches = true) }
        if (response.statusCode !in 200..299) throw WebcamError.BadStatusCode(response.statusCode)
        if (response.header("Content-Type")?.startsWith("image/") == false) throw WebcamError.NotAnImage()
        val snapshot = WebcamSnapshot(webcam.id, reference.imageURL, response.body, reference.capturedAt, now())
        synchronized(lock) { archived.insert(key, snapshot) }
        return afternoon(snapshot, day)
    }

    private fun isFavorite(webcam: Webcam) = synchronized(lock) { webcam.resortID in favoriteResortIDs }

    private fun keepsArchive(webcam: Webcam) = runCatching { registry.provider(webcam.provider).keepsArchive }.getOrDefault(false)

    private fun afternoon(snapshot: WebcamSnapshot, day: LocalDate): AfternoonSnapshot? =
        snapshot.capturedAt?.let { AfternoonSnapshot(snapshot.webcamID, day, snapshot.imageURL, snapshot.imageData, it) }

    companion object {
        /** Room for about thirty afternoon images looked up from archives. */
        const val DEFAULT_MEMORY_BUDGET = 8 * 1024 * 1024
    }
}
