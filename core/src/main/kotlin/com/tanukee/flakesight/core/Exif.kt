package com.tanukee.flakesight.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Reads the capture date of a JPEG from its EXIF block. Stands in for the
 * ImageIO call of `WebcamService.exifCaptureDate` in SkiPatrolKit, as `core`
 * has neither ImageIO nor Android's ExifInterface.
 */
internal object Exif {
    private const val TAG_DATE_TIME = 0x0132
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_DATE_TIME_ORIGINAL = 0x9003
    private const val TAG_OFFSET_TIME_ORIGINAL = 0x9011
    private val format = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /**
     * `DateTimeOriginal`, else the TIFF `DateTime`. Cameras write local time
     * without a zone, so it is read in [zone] unless `OffsetTimeOriginal` is set.
     * `null` for anything that is not a JPEG with such a stamp.
     */
    fun captureDate(data: ByteArray, zone: ZoneId): Instant? = runCatching {
        val tiff = tiffOffset(data) ?: return null
        val reader = Reader(data, tiff, bigEndian = data[tiff] == 'M'.code.toByte())
        val ifd0 = reader.entries(reader.u32(4))
        val exif = ifd0[TAG_EXIF_IFD]?.let { reader.entries(reader.u32(it + 8)) }.orEmpty()
        val raw = (exif[TAG_DATE_TIME_ORIGINAL] ?: ifd0[TAG_DATE_TIME])?.let(reader::ascii) ?: return null
        val local = LocalDateTime.parse(raw, format)
        val offset = exif[TAG_OFFSET_TIME_ORIGINAL]?.let(reader::ascii)
        if (offset != null) local.toInstant(ZoneOffset.of(offset)) else local.atZone(zone).toInstant()
    }.getOrNull()

    /** Start of the TIFF header inside the `Exif` APP1 segment. */
    private fun tiffOffset(data: ByteArray): Int? {
        if (data.u8(0) != 0xFF || data.u8(1) != 0xD8) return null
        var position = 2
        while (position + 4 <= data.size && data.u8(position) == 0xFF) {
            val marker = data.u8(position + 1)
            if (marker == 0xDA) return null // Image data starts: no EXIF before it.
            val length = (data.u8(position + 2) shl 8) or data.u8(position + 3)
            val header = data.copyOfRange(position + 4, minOf(position + 10, data.size)).decodeToString()
            if (marker == 0xE1 && header == "Exif\u0000\u0000") return position + 10
            position += 2 + length
        }
        return null
    }

    private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF

    /** Reads IFDs relative to the TIFF header, in its byte order. */
    private class Reader(val data: ByteArray, val base: Int, val bigEndian: Boolean) {
        fun u16(offset: Int): Int {
            val a = data.u8(base + offset)
            val b = data.u8(base + offset + 1)
            return if (bigEndian) (a shl 8) or b else (b shl 8) or a
        }

        fun u32(offset: Int): Int =
            if (bigEndian) (u16(offset) shl 16) or u16(offset + 2) else (u16(offset + 2) shl 16) or u16(offset)

        /** Tag to entry offset, for the IFD at [offset]. */
        fun entries(offset: Int): Map<Int, Int> =
            (0 until u16(offset)).associate { index -> (offset + 2 + index * 12).let { u16(it) to it } }

        /** An ASCII value: inline when it fits in four bytes, else at its offset. */
        fun ascii(entry: Int): String {
            val count = u32(entry + 4)
            val start = base + if (count <= 4) entry + 8 else u32(entry + 8)
            return data.copyOfRange(start, start + count).decodeToString().trimEnd('\u0000', ' ')
        }

        private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF
    }
}
