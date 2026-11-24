package com.tanukee.flakesight.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.time.Instant

/** Picks the webcam a widget shows. Port of `WidgetWebcamResolver`. */
object WidgetWebcamResolver {
    /**
     * In order: the webcam chosen in the widget's settings, the first webcam of
     * the default resort, then the first webcam of the first catalogue resort.
     * Unknown IDs (e.g. a webcam removed from the catalogue) fall through.
     */
    fun resolve(configuredWebcamID: String?, defaultResortID: String?, repository: ResortRepository): Pair<Resort, Webcam>? {
        configuredWebcamID?.let(repository::webcam)?.let { return it }
        defaultResortID?.let(repository::resort)?.let { resort -> resort.webcams.firstOrNull()?.let { return resort to it } }
        return repository.resorts.firstNotNullOfOrNull { resort -> resort.webcams.firstOrNull()?.let { resort to it } }
    }
}

/** `skipatrol://webcam/<id>`, what a widget opens. Port of `DeepLink`. */
object DeepLink {
    private const val SCHEME = "skipatrol"
    private const val WEBCAM_HOST = "webcam"

    fun webcamURL(webcamID: String): String = "$SCHEME://$WEBCAM_HOST/$webcamID"

    /** The webcam a link opens, `null` for any other link. */
    fun webcamID(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME || uri.host != WEBCAM_HOST) return null
        return uri.path?.removePrefix("/")?.takeIf { it.isNotEmpty() && '/' !in it }
    }
}

/**
 * The last image successfully downloaded for a webcam, kept on disk so a widget
 * can keep showing it when a refresh fails. Port of `LastSnapshotStore`.
 *
 * Every image keeps its real capture time, so a recovered image is still
 * flagged as stale when it is; the store never makes an old picture current.
 */
class LastSnapshotStore(val directory: File) {
    /** What the image file does not carry itself. */
    @Serializable
    private data class Sidecar(
        val imageURL: String,
        @Serializable(with = InstantSerializer::class) val capturedAt: Instant? = null,
        @Serializable(with = InstantSerializer::class) val fetchedAt: Instant,
    )

    /** The stored snapshot, or `null` when there is none or it cannot be read. */
    fun snapshot(webcam: Webcam, size: SnapshotSize): WebcamSnapshot? = runCatching {
        val (image, metadata) = files(webcam.id, size)
        val sidecar = json.decodeFromString<Sidecar>(metadata.readText())
        val imageData = image.readBytes().takeIf { it.isNotEmpty() } ?: return null
        WebcamSnapshot(webcam.id, sidecar.imageURL, imageData, sidecar.capturedAt, sidecar.fetchedAt)
    }.getOrNull()

    /** Replaces the stored snapshot. Returns `false` when it could not be written. */
    fun save(snapshot: WebcamSnapshot, size: SnapshotSize): Boolean = runCatching {
        val (image, metadata) = files(snapshot.webcamID, size)
        directory.mkdirs()
        image.writeBytesAtomically(snapshot.imageData)
        // Written last: without it the image is ignored, never mislabelled.
        metadata.writeTextAtomically(json.encodeToString(Sidecar(snapshot.imageURL, snapshot.capturedAt, snapshot.fetchedAt)))
    }.isSuccess

    fun removeAll() {
        directory.deleteRecursively()
    }

    private fun files(id: String, size: SnapshotSize): Pair<File, File> {
        // IDs are slugs (`les-saisies.bisanne-1500`), safe as file names.
        val name = "$id.${if (size == SnapshotSize.THUMBNAIL) "thumbnail" else "full"}"
        return File(directory, "$name.image") to File(directory, "$name.json")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
