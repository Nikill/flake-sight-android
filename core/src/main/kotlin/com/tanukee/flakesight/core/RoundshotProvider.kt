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
 * The archive lookup of the iOS provider comes with the afternoon images.
 */
class RoundshotProvider(private val http: HttpClient = UrlConnectionHttpClient) : WebcamProvider {

    override suspend fun latestSnapshotReference(webcam: Webcam, size: SnapshotSize, zone: ZoneId): SnapshotReference {
        val latest = latestImage(http.fetchBody(structureURL(webcam)), zone) ?: throw WebcamError.LatestImageNotFound()
        val url = when (size) {
            SnapshotSize.THUMBNAIL -> latest.quarterURL
            SnapshotSize.FULL -> latest.halfURL
        }
        return SnapshotReference(webcam.id, url, latest.capturedAt)
    }

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
