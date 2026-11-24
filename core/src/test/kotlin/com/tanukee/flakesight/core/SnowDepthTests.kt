package com.tanukee.flakesight.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of `SnowDepthTests.swift`: the avalanche bulletins' snow depth, read at
 * each resort's summit and village. Wording tests check the values the app
 * formats; the CarPlay and Watch tests come with those surfaces. Keep the two
 * files in step (see IOS_SYNC.md).
 */
class SnowDepthTests {
    companion object {
        /** 22 November 2026, 10:00 in Paris. */
        val now: Instant = Instant.ofEpochSecond(1_795_338_000)

        fun resort(summit: Int? = 2069, village: Int? = 1650, massif: Int? = 3) = Resort(
            id = "r", name = "R", country = "FR", region = "R", latitude = 45.76, longitude = 6.53,
            timeZoneIdentifier = "Europe/Paris", websiteURL = "https://example.com",
            villageAltitude = village, summitAltitude = summit, avalancheMassif = massif, webcams = emptyList(),
        )

        /** Mont-Blanc's bulletin of 21 November 2026. */
        val montBlanc = SnowDepthReport.Massif(
            name = "Mont-Blanc", issuedAt = "2026-11-21T16:21+01:00", observedOn = "2026-11-21",
            snowLineNorth = 1300, snowLineSouth = 1300,
            levels = listOf(
                SnowDepthReport.Level(1500, 40, 40),
                SnowDepthReport.Level(2000, 100, 100),
                SnowDepthReport.Level(2500, 130, 120),
            ),
        )

        val report = SnowDepthReport(massifs = mapOf("3" to montBlanc))

        fun depth(centimetres: Int, isLowerBound: Boolean = false) = SnowDepthReading.Depth(centimetres, isLowerBound)
    }

    @TempDir
    lateinit var directory: File

    // Reading the bulletin

    @Test
    fun depthAtABulletinAltitudeIsTheMeanOfBothSlopes() {
        assertEquals(depth(125), montBlanc.depth(2500))
        assertEquals(depth(40), montBlanc.depth(1500))
    }

    @Test
    fun depthBetweenTwoAltitudesFollowsAStraightLine() {
        // 1750 m is half way from 40 to 100 cm.
        assertEquals(depth(70), montBlanc.depth(1750))
        // 2069 m: 100 + 0.138 × 30 north, 100 + 0.138 × 20 south, mean 103.5, rounded to 105.
        assertEquals(depth(105), montBlanc.depth(2069))
    }

    @Test
    fun depthBelowTheLowestAltitudeStartsFromNothingAtTheSnowLine() {
        // 1400 m is half way from the snow line (1300 m, 0 cm) to 1500 m (40 cm).
        assertEquals(depth(20), montBlanc.depth(1400))
        assertEquals(depth(0), montBlanc.depth(1200))
    }

    @Test
    fun depthBelowTheLowestAltitudeIsUnknownWithoutASnowLine() {
        val massif = SnowDepthReport.Massif(
            name = "Pays-Basque", issuedAt = "2026-11-21T15:52+01:00", observedOn = "2026-11-21",
            levels = listOf(SnowDepthReport.Level(1000, 10, 0), SnowDepthReport.Level(1500, 30, 20)),
        )
        assertNull(massif.depth(900))
        assertEquals(depth(5), massif.depth(1000))
    }

    @Test
    fun oneSlopeWithoutASnowLineStillLetsTheOtherSpeak() {
        val massif = SnowDepthReport.Massif(
            name = "Cerdagne-Canigou", issuedAt = "2026-11-21T15:52+01:00", observedOn = "2026-11-21",
            snowLineNorth = 1400,
            levels = listOf(SnowDepthReport.Level(1500, 20, 10), SnowDepthReport.Level(2000, 40, 30)),
        )
        // South is unknown below 1500 m, north reads 0 below its snow line.
        assertEquals(depth(0), massif.depth(1300))
    }

    @Test
    fun depthAboveTheHighestAltitudeIsALowerBound() {
        assertEquals(depth(125, isLowerBound = true), montBlanc.depth(3456))
    }

    @Test
    fun aSnowLineAboveTheLowestAltitudeLeavesTheLevelsAsGiven() {
        val massif = SnowDepthReport.Massif(
            name = "Ubaye", issuedAt = "2026-11-21T16:00+01:00", observedOn = "2026-11-21",
            snowLineNorth = 1800, snowLineSouth = 2000,
            levels = listOf(SnowDepthReport.Level(1500, 0, 0), SnowDepthReport.Level(2000, 10, 0)),
        )
        assertEquals(depth(0), massif.depth(1500))
        assertEquals(depth(5), massif.depth(2000))
    }

    // A resort's reading

    @Test
    fun readingGivesTheSummitThenTheVillage() {
        val reading = assertNotNull(report.reading(resort(), now))
        assertEquals(listOf(ForecastLevel.Summit(2069), ForecastLevel.Village(1650)), reading.levels.map { it.level })
        assertEquals(listOf(105, 60), reading.levels.map { it.depth.centimetres })
        assertEquals("Mont-Blanc", reading.massifName)
    }

    @Test
    fun readingLeavesOutWhatTheBulletinCannotGive() {
        assertNull(report.reading(resort(massif = null), now))
        assertNull(report.reading(resort(massif = 5), now), "No bulletin for that massif")
        assertNull(report.reading(resort(summit = null, village = null), now), "No altitude")
    }

    @Test
    fun oldBulletinsAreNotShown() {
        assertNotNull(report.reading(resort(), now + Duration.ofDays(2)))
        assertNull(report.reading(resort(), now + Duration.ofDays(3)))
    }

    // Wording: the values the app formats

    /** "▲ 105 ▼ 60 cm" and "▲ 125+ ▼ 105 cm" in the app. */
    @Test
    fun compactLabelUsesTheStripArrows() {
        val reading = assertNotNull(report.reading(resort(), now))
        assertEquals(listOf(depth(105), depth(60)), reading.levels.map { it.depth })
        val high = assertNotNull(report.reading(resort(summit = 3456, village = 2100), now))
        assertEquals(listOf(depth(125, isLowerBound = true), depth(105)), high.levels.map { it.depth })
    }

    @Test
    fun sourceLabelGivesTheBulletinDay() {
        assertEquals(LocalDate.of(2026, 11, 21), assertNotNull(report.reading(resort(), now)).observedOn)
    }

    // The published file

    @Test
    fun decodesThePublishedFile() {
        val json = """
            {"version": 1, "source": "Météo-France", "massifs": {"3": {"name": "Mont-Blanc",
            "issuedAt": "2026-11-21T16:21+01:00", "observedOn": "2026-11-21", "snowLineNorth": 1300,
            "snowLineSouth": null, "levels": [{"altitude": 1500, "north": 40, "south": null}]}}}
        """.trimIndent()
        val massif = assertNotNull(SnowDepthReport.fromJson(json).massifs["3"])
        assertNull(massif.snowLineSouth)
        assertEquals(listOf(SnowDepthReport.Level(1500, 40, null)), massif.levels)
    }

    @Test
    fun refreshStoresTheReportAndAFailureKeepsIt() = runBlocking<Unit> {
        val file = File(directory, "snow-depth.json")
        val body = SnowDepthReport.json.encodeToString(report).encodeToByteArray()
        val http = StubHttpClient(
            mapOf(
                "stub://ok" to HttpResponse(200, emptyMap(), body),
                "stub://missing" to HttpResponse(404, emptyMap(), "Not Found".encodeToByteArray()),
                "stub://malformed" to HttpResponse(200, emptyMap(), "<html>".encodeToByteArray()),
            ),
        )
        val store = SnowDepthStore(file, http, "stub://ok")
        assertEquals(report, store.refresh())
        assertEquals(report, store.report.value)
        assertNull(store.refresh(), "Unchanged")

        for (url in listOf("stub://missing", "stub://malformed")) {
            val failing = SnowDepthStore(file, http, url)
            assertEquals(report, failing.report.value, "Kept on the device")
            assertNull(failing.refresh())
            assertEquals(report, failing.report.value)
        }
        assertTrue(file.exists())
    }
}
