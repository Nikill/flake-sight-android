package com.tanukee.flakesight.core

import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Skaping (skaping.com) webcams. Port of `SkapingProvider` in SkiPatrolKit.
 *
 * Each capture is stored under `…/<storage path>/YYYY/MM/DD/[large/|mini/]HH-MM.jpg`
 * (resort local time). There is no stable "latest" URL, so the provider reads
 * the public embed page and picks the newest image it references, of any size.
 * The storage path is `Webcam.providerIdentifier` when the page path differs.
 *
 * The archive lookup of the iOS provider comes with the afternoon images.
 */
class SkapingProvider(private val http: HttpClient = UrlConnectionHttpClient) : WebcamProvider {

    override suspend fun latestSnapshotReference(webcam: Webcam, size: SnapshotSize, zone: ZoneId): SnapshotReference {
        val html = http.fetchBody(webcam.sourcePageURL).decodeToString()
        val latest = latestImage(html, cameraPath(webcam), zone) ?: throw WebcamError.LatestImageNotFound()
        val url = when (size) {
            SnapshotSize.THUMBNAIL -> latest.largeURL
            SnapshotSize.FULL -> latest.fullURL
        }
        return SnapshotReference(webcam.id, url, latest.capturedAt)
    }

    internal data class LatestImage(
        /** ~4000 px wide, suitable for phone display (~80 KB). */
        val largeURL: String,
        /** Original panorama, ~12000 px wide (~1.5 MB). */
        val fullURL: String,
        val capturedAt: Instant,
    )

    internal companion object {
        /** `providerIdentifier` when set, otherwise the page path, e.g. `"les-saisies/le-manant"`. */
        fun cameraPath(webcam: Webcam): String =
            webcam.providerIdentifier ?: URI(webcam.sourcePageURL).rawPath.orEmpty().trim('/')

        /** The most recent capture of [cameraPath] referenced in the embed page HTML. */
        fun latestImage(html: String, cameraPath: String, zone: ZoneId): LatestImage? {
            // The page contains both plain and JSON-escaped (`\/`) URLs; normalise first.
            val text = html.replace("\\/", "/")
            val pattern = Regex(
                """https?://[A-Za-z0-9.\-]+/""" + Regex.escape(cameraPath) +
                    """/(\d{4})/(\d{2})/(\d{2})/(?:large/|mini/)?(\d{2})-(\d{2})\.jpg""",
            )
            val (capturedAt, matched) = pattern.findAll(text).mapNotNull { match ->
                val (year, month, day, hour, minute) = match.destructured.toList().map(String::toInt)
                runCatching { LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant() }
                    .getOrNull()?.let { it to match.value }
            }.maxByOrNull { it.first } ?: return null

            // Split ".../YYYY/MM/DD/[large/|mini/]HH-MM.jpg" into day folder and file name.
            val path = matched.replace("/large/", "/").replace("/mini/", "/")
            val base = path.substringBeforeLast('/')
            val file = path.substringAfterLast('/')
            return LatestImage(largeURL = "$base/large/$file", fullURL = "$base/$file", capturedAt = capturedAt)
        }
    }
}
