package com.tanukee.flakesight.core

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Port of `ProviderTests.swift`. Keep the two files in step (see IOS_SYNC.md). */
class ProviderTests {
    private val paris = ZoneId.of("Europe/Paris")
    private val repository = ResortRepository.bundled()

    private fun webcam(id: String) = assertNotNull(repository.webcam(id)).second

    @Test
    fun directImageProviderPicksURLBySize() = runBlocking<Unit> {
        val webcam = webcam("les-saisies.bisanne-1500")
        val thumbnail = DirectImageProvider.latestSnapshotReference(webcam, SnapshotSize.THUMBNAIL, paris)
        val full = DirectImageProvider.latestSnapshotReference(webcam, SnapshotSize.FULL, paris)
        assertEquals(webcam.thumbnailURL, thumbnail.imageURL)
        assertEquals(webcam.snapshotURL, full.imageURL)
        assertNull(DirectImageProvider.liveStreamURL(webcam))
    }

    @Test
    fun skapingParserFindsNewestCapture() {
        // Excerpt modelled on the public embed page: plain and JSON-escaped URLs, several captures.
        val html = """
            <img src="//data.skaping.com/les-saisies/le-manant/avatar_622.png">
            <script>var medias = ["https:\/\/skaping.s3.gra.io.cloud.ovh.net\/les-saisies\/le-manant\/2026\/10\/24\/large\/08-00.jpg"];</script>
            <meta property="og:image" content="https://skaping.s3.gra.io.cloud.ovh.net/les-saisies/le-manant/2026/10/24/large/08-30.jpg">
            <a href="//skaping.s3.gra.io.cloud.ovh.net/les-saisies/other-cam/2026/10/24/large/09-00.jpg"></a>
        """.trimIndent()
        val latest = assertNotNull(SkapingProvider.latestImage(html, "les-saisies/le-manant", paris))
        assertEquals("https://skaping.s3.gra.io.cloud.ovh.net/les-saisies/le-manant/2026/10/24/large/08-30.jpg", latest.largeURL)
        assertEquals("https://skaping.s3.gra.io.cloud.ovh.net/les-saisies/le-manant/2026/10/24/08-30.jpg", latest.fullURL)
        // 08:30 in Paris (CEST, UTC+2) is 06:30 UTC.
        assertEquals(Instant.parse("2026-10-24T06:30:00Z"), latest.capturedAt)
    }

    @Test
    fun skapingParserUsesStoragePathAndThumbnailStrip() {
        // Modelled on a Les 7 Laux page: the main image is older than the camera's
        // entry in the "other webcams" strip, and other cameras are listed too.
        val html = """
            <img src="https://skaping.s3.gra.io.cloud.ovh.net/les-7-laux/pleynet/les-loups/2026/03/28/large/03-50.jpg">
            <img src="https://skaping.s3.gra.io.cloud.ovh.net/les-7-laux/pleynet/les-loups/2026/04/14/mini/13-00.jpg">
            <img src="https://skaping.s3.gra.io.cloud.ovh.net/les-7-laux/pipay/grand-cerf/2026/10/24/mini/15-40.jpg">
        """.trimIndent()
        val latest = assertNotNull(SkapingProvider.latestImage(html, "les-7-laux/pleynet/les-loups", paris))
        assertEquals("https://skaping.s3.gra.io.cloud.ovh.net/les-7-laux/pleynet/les-loups/2026/04/14/large/13-00.jpg", latest.largeURL)
        assertEquals("https://skaping.s3.gra.io.cloud.ovh.net/les-7-laux/pleynet/les-loups/2026/04/14/13-00.jpg", latest.fullURL)
    }

    @Test
    fun skapingCameraPathPrefersProviderIdentifier() {
        assertEquals("oursiere/panoramique", SkapingProvider.cameraPath(webcam("les-7-laux.oursiere")))
        assertEquals("les-saisies/le-manant", SkapingProvider.cameraPath(webcam("les-saisies.le-manant")))
    }

    @Test
    fun skapingParserReturnsNilWithoutImages() {
        assertNull(SkapingProvider.latestImage("<html></html>", "les-saisies/le-manant", paris))
    }

    // Roundshot

    companion object {
        /**
         * Trimmed from `https://tignes.roundshot.com/lac/structure.json`: the list is
         * not in time order, and a capture still being processed is newer than the
         * last finished one. Also used by `ArchiveProviderTests`.
         */
        fun roundshotImage(status: String = "finished", datetime: Long, time: String): String {
            val base = "https://storage4.roundshot.com/543bc630698b92.10661132/2026-10-30/$time/2026-10-30-$time"
            return """
                {"id": 239482207, "status": "$status", "datetime": $datetime, "tile": {"w": 1024, "h": 1024},
                 "structure": {
                   "full": {"w": 15696, "h": 2048, "url_full": "${base}_full.jpg", "url_tile": "${base}_full/%x%_%y%.jpg"},
                   "half": {"w": 7848, "h": 1024, "url_full": "${base}_half.jpg", "url_tile": "${base}_half/%x%_%y%.jpg"},
                   "quarter": {"w": 3924, "h": 512, "url_full": "${base}_quarter.jpg", "url_tile": "${base}_quarter/%x%_%y%.jpg"},
                   "thumbnail": {"w": 450, "h": 59, "url_full": "${base}_thumbnail.jpg"}
                 }}
            """.trimIndent()
        }
    }

    @Test
    fun roundshotParserFindsNewestFinishedCapture() {
        val json = """
            {"camera": "Lac", "cam_inactive": false, "images": [
              ${roundshotImage(datetime = 1_793_390_400, time = "20-00-00")},
              ${roundshotImage(datetime = 1_793_391_600, time = "20-20-00")},
              ${roundshotImage(status = "processing", datetime = 1_793_392_800, time = "20-40-00")}
            ]}
        """.trimIndent()
        val latest = assertNotNull(RoundshotProvider.latestImage(json.encodeToByteArray(), paris))
        assertEquals("https://storage4.roundshot.com/543bc630698b92.10661132/2026-10-30/20-20-00/2026-10-30-20-20-00_quarter.jpg", latest.quarterURL)
        assertEquals("https://storage4.roundshot.com/543bc630698b92.10661132/2026-10-30/20-20-00/2026-10-30-20-20-00_half.jpg", latest.halfURL)
        // 20:20 on the resort's clock; Paris is on CET (UTC+1) after 25 October.
        assertEquals(Instant.parse("2026-10-30T19:20:00Z"), latest.capturedAt)
    }

    @Test
    fun roundshotStructureSitsBesideThePage() {
        assertEquals("https://tignes.roundshot.com/lac/structure.json", RoundshotProvider.structureURL(webcam("tignes.le-lac")))
        assertEquals("https://portesdusoleil.roundshot.com/structure.json", RoundshotProvider.structureURL(webcam("morzine.grand-conche")))
    }

    @Test
    fun roundshotParserRejectsOtherAnswers() {
        assertNull(RoundshotProvider.latestImage("<html></html>".encodeToByteArray(), paris))
        assertNull(RoundshotProvider.latestImage("""{"images": []}""".encodeToByteArray(), paris))
    }

    // Viewsurf

    /**
     * Trimmed from `https://pv.viewsurf.com/2762/Saint-Francois-Longchamp-TSF-Lauziere-G1`:
     * a panorama and a video, in the page's own loose script syntax.
     */
    private val viewsurfPage = """
        <meta property="og:image" content="https://filmspv.viewsurf.com/stfrancoislongchamp01/1/1/media_1793341806.jpg" />
        <script>
          let playerOptions = {
            "analytics": { "reference": '2762' },
            "collection": [{
              "id": "9092",
              "type": "pano",
              "default": true,
              "timezone": "Europe/Paris",
              "media": {
                "source": "//filmspv.viewsurf.com/stfrancoislongchamp01/1/1/media_1793341806-tiles",
                "thumbnail": "//filmspv.viewsurf.com/stfrancoislongchamp01/1/1/media_1793341806_tn.jpg",
                "date": "2026-10-30T07:30:06+01:00"
              },
              "maxDate": "2026-10-30T07:30:06+01:00",
              "pois": [],
            }, {
              "id": "9094",
              "type": "video",
              "default": false,
              "media": {
                "source": "6e987c27-9067-4491-3433-3730-6d61-63-bdef-8784713cf599d",
                "thumbnail": "//filmspv.viewsurf.com/stfrancoislongchamp01/4/4/media_1793341384_tn.jpg",
                "poster": "//filmspv.viewsurf.com/stfrancoislongchamp01/4/4/media_1793341384.jpg",
                "date": "2026-10-30T07:23:04+01:00"
              },
            },
          ]
        };
        </script>
    """.trimIndent()

    @Test
    fun viewsurfParserReadsTheNamedView() {
        val pano = assertNotNull(ViewsurfProvider.latestImage(viewsurfPage, "9092"))
        assertEquals("https://filmspv.viewsurf.com/stfrancoislongchamp01/1/1/media_1793341806.jpg", pano.imageURL)
        assertEquals(Instant.parse("2026-10-30T06:30:06Z"), pano.capturedAt)
        val video = assertNotNull(ViewsurfProvider.latestImage(viewsurfPage, "9094"))
        assertEquals("https://filmspv.viewsurf.com/stfrancoislongchamp01/4/4/media_1793341384.jpg", video.imageURL)
    }

    @Test
    fun viewsurfParserFallsBackToTheDefaultView() {
        val latest = assertNotNull(ViewsurfProvider.latestImage(viewsurfPage, null))
        assertEquals("https://filmspv.viewsurf.com/stfrancoislongchamp01/1/1/media_1793341806.jpg", latest.imageURL)
        assertEquals(latest.imageURL, ViewsurfProvider.latestImage(viewsurfPage, "1234")?.imageURL)
    }

    @Test
    fun viewsurfViewComesFromThePageURL() {
        assertEquals("9092", ViewsurfProvider.viewID(webcam("saint-francois-longchamp.ts-lauziere")))
        assertEquals("9096", ViewsurfProvider.viewID(webcam("saint-francois-longchamp.ts-soleil-rouge")))
    }

    @Test
    fun viewsurfParserReturnsNilWithoutPlayer() {
        assertNull(ViewsurfProvider.latestImage("<html></html>", null))
    }

    @Test
    fun registryRejectsUnsupportedProviders() {
        val registry = WebcamProviderRegistry.standard(UrlConnectionHttpClient)
        val error = assertFailsWith<WebcamError.UnsupportedProvider> { registry.provider(WebcamProviderType.PANOMAX) }
        assertEquals(WebcamProviderType.PANOMAX, error.type)
    }
}
