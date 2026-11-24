package com.tanukee.flakesight.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Roundshot (roundshot.com) webcams. Port of `RoundshotProvider` in SkiPatrolKit.
 *
 * The public page reads `structure.json` next to itself, which lists the recent
 * captures with their time and the panorama in several sizes. Capture times
 * are the resort's wall-clock time written as if it were UTC.
 *
 * Every capture stays on storage, so past afternoon images are found by their
 * time-stamped name ([archivedSnapshotReference]).
 */
class RoundshotProvider(private val http: HttpClient = UrlConnectionHttpClient) : WebcamProvider {

    override suspend fun latestSnapshotReference(webcam: Webcam, size: SnapshotSize, zone: ZoneId): SnapshotReference {
        val latest = latest(webcam, zone)
        val url = when (size) {
            SnapshotSize.THUMBNAIL -> latest.quarterURL
            SnapshotSize.FULL -> latest.halfURL
        }
        return SnapshotReference(webcam.id, url, latest.capturedAt)
    }

    override val keepsArchive: Boolean get() = true

    override suspend fun archivedSnapshotReference(webcam: Webcam, target: Instant, size: SnapshotSize, zone: ZoneId): SnapshotReference? {
        val base = storageBase(latest(webcam, zone))
        val candidates = ArchiveProbe.times(target).map { archivedImageURL(base, it, size, zone) to it }
        val (url, capturedAt) = ArchiveProbe.firstExisting(candidates, http) ?: return null
        return SnapshotReference(webcam.id, url, capturedAt)
    }

    private suspend fun latest(webcam: Webcam, zone: ZoneId): LatestImage =
        latestImage(http.fetchBody(structureURL(webcam)), zone) ?: throw WebcamError.LatestImageNotFound()

    internal data class LatestImage(
        /** ~3900 px wide (~200 KB): cards, widgets and the watch. */
        val quarterURL: String,
        /** ~7800 px wide (~1.4 MB): more than the full-screen view decodes. */
        val halfURL: String,
        val capturedAt: Instant,
    )

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** `structure.json` beside the camera's page, the way the page loads it. */
        fun structureURL(webcam: Webcam): String = webcam.sourcePageURL.trimEnd('/') + "/structure.json"

        /** The newest finished capture in a `structure.json` answer. */
        fun latestImage(data: ByteArray, zone: ZoneId): LatestImage? {
            val structure = runCatching { json.decodeFromString<Structure>(data.decodeToString()) }.getOrNull() ?: return null
            val newest = structure.images.filter { it.status == "finished" }.maxByOrNull { it.datetime } ?: return null
            val quarter = newest.structure.quarter?.urlFull ?: return null
            val half = newest.structure.half?.urlFull ?: return null
            // Read the wall-clock time back in UTC, then place it in the resort.
            val wallClock = LocalDateTime.ofEpochSecond(newest.datetime.toLong(), 0, ZoneOffset.UTC)
            return LatestImage(quarter, half, wallClock.atZone(zone).toInstant())
        }

        /** The camera's folder on storage: the quarter image's URL without `YYYY-MM-DD/HH-MM-SS/<file>`. */
        fun storageBase(latest: LatestImage): String =
            (1..3).fold(latest.quarterURL) { url, _ -> url.substringBeforeLast('/') } + "/"

        /** `<base>/YYYY-MM-DD/HH-MM-00/YYYY-MM-DD-HH-MM-00_quarter.jpg` (or `_half`) for a capture at [capturedAt], resort time. */
        fun archivedImageURL(base: String, capturedAt: Instant, size: SnapshotSize, zone: ZoneId): String {
            val day = ArchiveProbe.stamp(capturedAt, zone, "yyyy-MM-dd")
            val time = ArchiveProbe.stamp(capturedAt, zone, "HH-mm") + "-00"
            val suffix = if (size == SnapshotSize.THUMBNAIL) "quarter" else "half"
            return "$base$day/$time/$day-${time}_$suffix.jpg"
        }
    }

    @Serializable
    private class Structure(val images: List<Image>)

    @Serializable
    private class Image(
        val status: String,
        /** Seconds since 1970 of the resort's wall-clock time read as UTC. */
        val datetime: Double,
        val structure: Sizes,
    )

    @Serializable
    private class Sizes(val quarter: Size? = null, val half: Size? = null)

    @Serializable
    private class Size(@SerialName("url_full") val urlFull: String)
}
