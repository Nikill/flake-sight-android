package com.tanukee.flakesight.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A single webcam belonging to a resort. Port of `Webcam` in SkiPatrolKit.
 *
 * Describes where the webcam lives and how it can be retrieved. The latest image
 * and its capture time are runtime values, not stored here.
 */
@Serializable
data class Webcam(
    /** Stable identifier, namespaced by resort, e.g. `"les-saisies.bisanne-1500"`. */
    val id: String,
    val resortID: String,
    val name: String,
    val provider: WebcamProviderType,
    /** The provider's public page for this webcam. */
    val sourcePageURL: String,
    /** The provider's own identifier, when it differs from what [sourcePageURL] implies. */
    val providerIdentifier: String? = null,
    /** Full-resolution snapshot, when the provider exposes a stable URL for it. */
    val snapshotURL: String? = null,
    /** Lightweight snapshot for lists, widgets and the watch. */
    val thumbnailURL: String? = null,
    /** Live video stream, only set when one is known to be publicly usable. */
    val streamURL: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Altitude in metres. */
    val altitude: Double? = null,
    /** Free-form viewing direction as published by the source. */
    val orientation: String? = null,
    val attribution: WebcamAttribution,
    val capabilities: Set<WebcamCapability>,
) {
    /** `true` only when the webcam declares a live stream *and* has a URL for it. */
    val hasLiveStream: Boolean
        get() = streamURL != null &&
            (WebcamCapability.LIVE_STREAM in capabilities || WebcamCapability.HLS in capabilities)
}

/** Credit and link back to the original source, shown wherever an image is displayed. */
@Serializable
data class WebcamAttribution(
    val providerName: String,
    val providerURL: String? = null,
    /** Owner of the camera / image rights, e.g. `"Les Saisies"`. */
    val owner: String? = null,
)

/**
 * The platform that hosts a webcam. Unknown values decode to [UNKNOWN] so an
 * older app can still read a newer catalogue.
 */
@Serializable(with = WebcamProviderTypeSerializer::class)
enum class WebcamProviderType(val rawValue: String) {
    WEBCAM_HD("webcamHD"),
    SKAPING("skaping"),
    VIEWSURF("viewsurf"),
    PANOMAX("panomax"),
    ROUNDSHOT("roundshot"),
    SKYLINE_WEBCAMS("skylineWebcams"),
    DIRECT_IMAGE("directImage"),
    HLS("hls"),
    UNKNOWN("unknown"),
}

/** What a webcam is able to deliver. Never assume live video. */
@Serializable(with = WebcamCapabilitySerializer::class)
enum class WebcamCapability(val rawValue: String) {
    STATIC_SNAPSHOT("staticSnapshot"),
    PERIODICALLY_UPDATED_SNAPSHOT("periodicallyUpdatedSnapshot"),
    PANORAMA("panorama"),
    LIVE_STREAM("liveStream"),
    HLS("hls"),
    UNKNOWN("unknown"),
}

internal object WebcamProviderTypeSerializer : KSerializer<WebcamProviderType> {
    override val descriptor = PrimitiveSerialDescriptor("WebcamProviderType", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): WebcamProviderType {
        val raw = decoder.decodeString()
        return WebcamProviderType.entries.firstOrNull { it.rawValue == raw } ?: WebcamProviderType.UNKNOWN
    }
    override fun serialize(encoder: Encoder, value: WebcamProviderType) = encoder.encodeString(value.rawValue)
}

internal object WebcamCapabilitySerializer : KSerializer<WebcamCapability> {
    override val descriptor = PrimitiveSerialDescriptor("WebcamCapability", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): WebcamCapability {
        val raw = decoder.decodeString()
        return WebcamCapability.entries.firstOrNull { it.rawValue == raw } ?: WebcamCapability.UNKNOWN
    }
    override fun serialize(encoder: Encoder, value: WebcamCapability) = encoder.encodeString(value.rawValue)
}
