package com.tanukee.flakesight.core

import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Base64

/**
 * Viewsurf (pv.viewsurf.com) webcams. Port of `ViewsurfProvider` in SkiPatrolKit.
 *
 * The player page embeds a `playerOptions` script whose `collection` lists the
 * camera's views, each with the latest capture's date and a `_tn.jpg`
 * thumbnail. The same name without `_tn` is the full image; the thumbnail is
 * too small for a card, so both sizes use the full image.
 *
 * The page URL's `i` query item picks a view: base64 for `"9092:"`. Without
 * it, the view the page marks as default is used.
 */
class ViewsurfProvider(private val http: HttpClient = UrlConnectionHttpClient) : WebcamProvider {

    override suspend fun latestSnapshotReference(webcam: Webcam, size: SnapshotSize, zone: ZoneId): SnapshotReference {
        val html = http.fetchBody(webcam.sourcePageURL).decodeToString()
        val latest = latestImage(html, viewID(webcam)) ?: throw WebcamError.LatestImageNotFound()
        return SnapshotReference(webcam.id, latest.imageURL, latest.capturedAt)
    }

    internal data class LatestImage(val imageURL: String, val capturedAt: Instant)

    private class View(val id: String, val isDefault: Boolean, val thumbnail: String?, val date: String?)

    internal companion object {
        private val idPattern = Regex(""""id"\s*:\s*"(\d+)"""")
        private val defaultPattern = Regex(""""default"\s*:\s*true""")

        /** The view named by the page URL's `i` query item, `"9092"` for `?i=OTA5Mjo`. */
        fun viewID(webcam: Webcam): String? {
            val encoded = runCatching { URI(webcam.sourcePageURL).query }.getOrNull()
                ?.split('&')?.firstOrNull { it.startsWith("i=") }?.removePrefix("i=")
                ?: return null
            val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            val decoded = runCatching { Base64.getDecoder().decode(padded).decodeToString() }.getOrNull() ?: return null
            return decoded.split(':').firstOrNull { it.isNotEmpty() }
        }

        /** The latest image of [viewID] in the player page, or of the default view. */
        fun latestImage(html: String, viewID: String?): LatestImage? {
            val start = html.indexOf("\"collection\"").takeIf { it >= 0 } ?: return null
            val views = views(html.substring(start + "\"collection\"".length))
            val view = views.firstOrNull { it.id == viewID } ?: views.firstOrNull { it.isDefault } ?: views.firstOrNull()
                ?: return null
            val thumbnail = view.thumbnail?.takeIf { it.endsWith("_tn.jpg") } ?: return null
            val capturedAt = view.date?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return null
            // Protocol-relative in the page.
            val full = (if (thumbnail.startsWith("//")) "https:$thumbnail" else thumbnail).removeSuffix("_tn.jpg") + ".jpg"
            return LatestImage(full, capturedAt)
        }

        /** One view per `"id"` in the collection, reading the fields up to the next one. */
        private fun views(collection: String): List<View> {
            val ids = idPattern.findAll(collection).toList()
            return ids.mapIndexed { index, id ->
                val end = ids.getOrNull(index + 1)?.range?.first ?: collection.length
                val fields = collection.substring(id.range.last + 1, end)
                fun value(key: String) = Regex(""""$key"\s*:\s*"([^"]*)"""").find(fields)?.groupValues?.get(1)
                View(id.groupValues[1], defaultPattern.containsMatchIn(fields), value("thumbnail"), value("date"))
            }
        }
    }
}
