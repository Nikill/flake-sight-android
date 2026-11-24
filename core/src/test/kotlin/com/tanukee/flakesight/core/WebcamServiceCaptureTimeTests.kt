package com.tanukee.flakesight.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Port of `WebcamServiceCaptureTimeTests.swift`: where a snapshot's capture time
 * comes from when the provider gives none. Keep the two files in step (see IOS_SYNC.md).
 */
class WebcamServiceCaptureTimeTests {
    /** Points every webcam at one URL, with or without a capture time. */
    private class StubProvider(val imageURL: String, val capturedAt: Instant?) : WebcamProvider {
        override suspend fun latestSnapshotReference(webcam: Webcam, size: SnapshotSize, zone: ZoneId) =
            SnapshotReference(webcam.id, imageURL, capturedAt)
    }

    private val lastModified = Instant.ofEpochSecond(1_792_225_800) // Sat, 17 Oct 2026 08:30:00 GMT
    private val webcam = testWebcam("test.cam")
    private val url = "stub://images/image.png"

    private fun snapshot(response: HttpResponse, providerCaptureTime: Instant?) = runBlocking {
        WebcamService(
            http = StubHttpClient(mapOf(url to response)),
            registry = WebcamProviderRegistry(mapOf(WebcamProviderType.DIRECT_IMAGE to StubProvider(url, providerCaptureTime))),
        ).latestSnapshot(webcam, SnapshotSize.THUMBNAIL, ZoneOffset.UTC)
    }

    @Test
    fun lastModifiedIsTheCaptureTimeOfLastResort() {
        val response = imageResponse(mapOf("Last-Modified" to "Sat, 17 Oct 2026 08:30:00 GMT"))
        assertEquals(lastModified, snapshot(response, providerCaptureTime = null).capturedAt)
    }

    @Test
    fun providerCaptureTimeWinsOverLastModified() {
        val response = imageResponse(mapOf("Last-Modified" to "Sat, 17 Oct 2026 08:30:00 GMT"))
        val providerTime = lastModified.minusSeconds(300)
        assertEquals(providerTime, snapshot(response, providerTime).capturedAt)
    }

    @Test
    fun withoutAnyMetadataTheCaptureTimeStaysUnknown() {
        assertNull(snapshot(imageResponse(), providerCaptureTime = null).capturedAt)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Sat, 17 Oct 2026 08:30:00 GMT", "Saturday, 17-Oct-26 08:30:00 GMT", "Sat Oct 17 08:30:00 2026"])
    fun httpDateFormatsAreAccepted(text: String) {
        assertEquals(lastModified, WebcamService.httpDate(text))
    }

    @Test
    fun garbageIsNotADate() {
        assertNull(WebcamService.httpDate("yesterday"))
    }

    /** Android only: iOS reads EXIF through ImageIO, `core` parses it itself. */
    @Test
    fun exifCaptureTimeIsReadInTheResortTimeZone() {
        val paris = ZoneId.of("Europe/Paris")
        // No offset: camera local time, Paris on CEST (UTC+2).
        assertEquals(Instant.parse("2026-10-17T06:30:00Z"), Exif.captureDate(jpeg("2026:10:17 08:30:00", null), paris))
        // An explicit offset wins over the resort's zone.
        assertEquals(Instant.parse("2026-10-17T07:30:00Z"), Exif.captureDate(jpeg("2026:10:17 08:30:00", "+01:00"), paris))
        assertNull(Exif.captureDate(testImage, paris))
        assertNull(Exif.captureDate(jpeg("2026:10:17 08:30:00", null).copyOf(40), paris), "Truncated file")
    }

    /** A JPEG holding only an EXIF block, big-endian, with `DateTimeOriginal` and optionally `OffsetTimeOriginal`. */
    private fun jpeg(dateTimeOriginal: String, offset: String?): ByteArray {
        val strings = listOfNotNull(0x9003 to dateTimeOriginal, offset?.let { 0x9011 to it })
        val exifIfd = 26
        var data = exifIfd + 2 + strings.size * 12 + 4
        val tiff = ByteBuffer.allocate(data + strings.sumOf { it.second.length + 1 })
        tiff.put("MM".toByteArray()).putShort(42).putInt(8)
        tiff.putShort(1).putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(exifIfd).putInt(0)
        tiff.putShort(strings.size.toShort())
        for ((tag, text) in strings) {
            tiff.putShort(tag.toShort()).putShort(2).putInt(text.length + 1).putInt(data)
            data += text.length + 1
        }
        tiff.putInt(0)
        for ((_, text) in strings) tiff.put(text.toByteArray()).put(0)

        val segment = "Exif\u0000\u0000".toByteArray() + tiff.array()
        return ByteBuffer.allocate(6 + segment.size + 2)
            .putShort(0xFFD8.toShort()).putShort(0xFFE1.toShort()).putShort((segment.size + 2).toShort())
            .put(segment).putShort(0xFFD9.toShort()).array()
    }
}
