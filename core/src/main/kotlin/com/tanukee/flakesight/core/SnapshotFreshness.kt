package com.tanukee.flakesight.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * How current a webcam image is, based on the camera's capture time only.
 * Port of `SnapshotFreshness` in SkiPatrolKit.
 *
 * The download time is deliberately ignored: off-season cameras keep serving
 * their last picture, which would otherwise look current.
 */
sealed interface SnapshotFreshness {
    val capturedAt: Instant?

    /** Captured less than [STALE_INTERVAL] ago. */
    data class Recent(override val capturedAt: Instant) : SnapshotFreshness

    /** Captured longer ago; must not be presented as current. */
    data class Stale(override val capturedAt: Instant) : SnapshotFreshness

    /** The provider and the image publish no capture time. */
    data object Unknown : SnapshotFreshness {
        override val capturedAt: Instant? get() = null
    }

    val isStale: Boolean get() = this is Stale

    /**
     * "14:30" for recent images, "11 Apr 2026" for stale ones, in the resort's
     * time zone; `null` when unknown. The app wraps it in its "Updated %s" and
     * "Last image %s" strings.
     */
    fun shortLabel(zone: ZoneId, locale: Locale = Locale.getDefault()): String? = when (this) {
        is Recent -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(capturedAt)
        is Stale -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(capturedAt)
        Unknown -> null
    }

    companion object {
        /** Older images are flagged as stale. */
        val STALE_INTERVAL: Duration = Duration.ofHours(24)

        fun of(capturedAt: Instant?, now: Instant = Instant.now()): SnapshotFreshness = when {
            capturedAt == null -> Unknown
            Duration.between(capturedAt, now) < STALE_INTERVAL -> Recent(capturedAt)
            else -> Stale(capturedAt)
        }

        fun of(snapshot: WebcamSnapshot, now: Instant = Instant.now()): SnapshotFreshness = of(snapshot.capturedAt, now)

        /** When an image captured at [capturedAt] stops being recent. */
        fun staleDate(capturedAt: Instant): Instant = capturedAt + STALE_INTERVAL
    }
}

/**
 * Webcams with a recent image first, then those whose image is not loaded or
 * carries no capture time, then those showing an old image. Catalogue order is
 * kept within each group (`sortedBy` is stable).
 */
fun Iterable<Webcam>.sortedByFreshness(freshness: (Webcam) -> SnapshotFreshness?): List<Webcam> =
    sortedBy {
        when (freshness(it)) {
            is SnapshotFreshness.Recent -> 0
            is SnapshotFreshness.Stale -> 2
            else -> 1
        }
    }
