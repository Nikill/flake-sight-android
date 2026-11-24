package com.tanukee.flakesight.core

import java.time.Instant
import java.time.ZoneId

/**
 * Knows how to retrieve content for webcams hosted on one platform. Port of
 * `WebcamProvider` in SkiPatrolKit.
 *
 * Providers only *resolve* where the latest image or stream lives. Downloading,
 * caching and timestamp extraction are handled once by [WebcamService].
 */
interface WebcamProvider {
    /** Resolves the URL of the most recent image for [webcam]. */
    suspend fun latestSnapshotReference(webcam: Webcam, size: SnapshotSize, zone: ZoneId): SnapshotReference

    /** A playable stream URL, or `null` when the webcam has no usable live stream. */
    suspend fun liveStreamURL(webcam: Webcam): String? = if (webcam.hasLiveStream) webcam.streamURL else null

    /** Credit to display alongside the webcam's content. */
    fun attribution(webcam: Webcam): WebcamAttribution = webcam.attribution

    /** Whether past captures stay on the provider's storage, so [archivedSnapshotReference] can find them days later. */
    val keepsArchive: Boolean get() = false

    /**
     * Where the provider stored the capture closest to [target], within
     * [AfternoonWindow.TOLERANCE], or `null` when it has none near it.
     */
    suspend fun archivedSnapshotReference(webcam: Webcam, target: Instant, size: SnapshotSize, zone: ZoneId): SnapshotReference? = null
}

/**
 * Why a webcam's image could not be shown. Messages are English, for logs;
 * the app maps each case to a translated string.
 */
sealed class WebcamError(message: String) : Exception(message) {
    class NoSnapshotAvailable : WebcamError("This webcam has no image available.")
    class UnsupportedProvider(val type: WebcamProviderType) :
        WebcamError("${type.rawValue} webcams are not supported yet.")
    class LatestImageNotFound : WebcamError("The latest image could not be found on the provider's page.")
    class BadStatusCode(val code: Int) : WebcamError("The webcam server responded with an error ($code).")
    class NotAnImage : WebcamError("The webcam server did not return an image.")
}

/** Maps each [WebcamProviderType] to the provider that handles it. */
class WebcamProviderRegistry(private val providers: Map<WebcamProviderType, WebcamProvider>) {

    fun provider(type: WebcamProviderType): WebcamProvider =
        providers[type] ?: throw WebcamError.UnsupportedProvider(type)

    companion object {
        fun standard(http: HttpClient): WebcamProviderRegistry = WebcamProviderRegistry(
            mapOf(
                WebcamProviderType.WEBCAM_HD to DirectImageProvider,
                WebcamProviderType.DIRECT_IMAGE to DirectImageProvider,
                WebcamProviderType.SKAPING to SkapingProvider(http),
                WebcamProviderType.ROUNDSHOT to RoundshotProvider(http),
                WebcamProviderType.VIEWSURF to ViewsurfProvider(http),
            ),
        )
    }
}

/**
 * Webcams whose catalogue entry already contains stable image URLs: plain
 * JPEG endpoints and Webcam HD.
 */
object DirectImageProvider : WebcamProvider {
    override suspend fun latestSnapshotReference(webcam: Webcam, size: SnapshotSize, zone: ZoneId): SnapshotReference {
        val preferred = when (size) {
            SnapshotSize.THUMBNAIL -> webcam.thumbnailURL ?: webcam.snapshotURL
            SnapshotSize.FULL -> webcam.snapshotURL ?: webcam.thumbnailURL
        } ?: throw WebcamError.NoSnapshotAvailable()
        return SnapshotReference(webcam.id, preferred)
    }
}
