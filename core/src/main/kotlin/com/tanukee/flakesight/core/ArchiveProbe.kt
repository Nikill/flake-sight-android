package com.tanukee.flakesight.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Finds a past capture on storage that cannot be listed, by asking for the
 * likely file names one by one. Port of `ArchiveProbe`.
 *
 * Skaping and Roundshot name each capture after its local time and refuse
 * directory listings, so the only way to find "the 16:00 image" is to try
 * 16:00, then the times around it. Cameras capture every 10, 30 or 60 minutes
 * on round minutes, so these offsets find the nearest one quickly.
 */
internal object ArchiveProbe {
    /** Minutes from the target, nearest and most common cadences first. */
    val offsets = listOf(0, -30, 30, -10, 10, -20, 20, -60, 60)

    /** Candidate capture times around [target], nearest first. */
    fun times(target: Instant): List<Instant> = offsets.map { target.plusSeconds(it * 60L) }

    /**
     * The first candidate whose URL exists, checked in order with HEAD requests.
     * "Missing" answers (403, 404) move on to the next one; any other failure is
     * thrown so a network error is not reported as "no image".
     */
    suspend fun firstExisting(candidates: List<Pair<String, Instant>>, http: HttpClient): Pair<String, Instant>? {
        for (candidate in candidates) {
            val status = withContext(Dispatchers.IO) { http.head(candidate.first) }.statusCode
            when (status) {
                in 200..299 -> return candidate
                403, 404 -> continue
                else -> throw WebcamError.BadStatusCode(status)
            }
        }
        return null
    }

    /** [instant] in the resort's [zone] with [pattern], e.g. `yyyy/MM/dd` or `HH-mm`. */
    fun stamp(instant: Instant, zone: ZoneId, pattern: String): String =
        DateTimeFormatter.ofPattern(pattern).withZone(zone).format(instant)
}
