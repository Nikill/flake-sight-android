package com.tanukee.flakesight.core

import java.time.Instant

/** The resolution at which a snapshot is requested. Port of `SnapshotSize` in SkiPatrolKit. */
enum class SnapshotSize {
    /** Small image for lists, widgets and the watch. */
    THUMBNAIL,

    /** Largest image the provider offers. */
    FULL,
}

/** Where the latest image of a webcam can be downloaded, as resolved by a provider. */
data class SnapshotReference(
    val webcamID: String,
    val imageURL: String,
    /** Capture time when the provider exposes it without downloading the image. */
    val capturedAt: Instant? = null,
)

/** A downloaded webcam image. Port of `WebcamSnapshot` in SkiPatrolKit. */
class WebcamSnapshot(
    val webcamID: String,
    val imageURL: String,
    val imageData: ByteArray,
    /**
     * When the camera took the picture, if known: provider metadata, EXIF or,
     * failing both, the image file's HTTP `Last-Modified` time. Display this
     * rather than [fetchedAt]: some cameras stop updating off-season.
     */
    val capturedAt: Instant?,
    /** When Flake Sight downloaded it. */
    val fetchedAt: Instant,
) {
    override fun equals(other: Any?): Boolean = other is WebcamSnapshot &&
        webcamID == other.webcamID && imageURL == other.imageURL && capturedAt == other.capturedAt &&
        fetchedAt == other.fetchedAt && imageData.contentEquals(other.imageData)

    override fun hashCode(): Int = listOf(webcamID, imageURL, capturedAt, fetchedAt).hashCode() * 31 +
        imageData.contentHashCode()
}

/** What selecting a webcam can show. Port of `WebcamMediaKind` in SkiPatrolKit. */
enum class WebcamMediaKind { VIDEO, STILL_IMAGE }

/** Never assumes video: only webcams that declare a stream *and* have its URL count. */
val Webcam.mediaKind: WebcamMediaKind
    get() = if (hasLiveStream) WebcamMediaKind.VIDEO else WebcamMediaKind.STILL_IMAGE
