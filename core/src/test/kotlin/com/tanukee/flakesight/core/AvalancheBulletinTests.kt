package com.tanukee.flakesight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Port of `AvalancheBulletinTests.swift` for the danger level and the week's
 * history; the slopes, fresh snow, mountain forecast and Watch tests come with
 * the advanced weather screen and Wear OS. Keep the two files in step.
 */
class AvalancheBulletinTests {
    /** Mont-Blanc on 21 November 2026, with the week before. */
    private val massif = SnowDepthReport.Massif(
        name = "Mont-Blanc", issuedAt = "2026-11-21T16:21+01:00", observedOn = "2026-11-21",
        snowLineNorth = 1300, snowLineSouth = 1300,
        levels = SnowDepthTests.montBlanc.levels,
        avalancheRisk = AvalancheRisk(below = 3, above = 4, boundary = 2200, maximum = 4, tomorrowMaximum = 4),
        history = listOf(
            SnowDepthReport.Snapshot(
                "2026-11-15", 1700, 1900,
                listOf(SnowDepthReport.Level(1500, 0, 0), SnowDepthReport.Level(2000, 3, 3), SnowDepthReport.Level(2500, 50, 40)),
            ),
            SnowDepthReport.Snapshot("2026-11-21", 1300, 1300, SnowDepthTests.montBlanc.levels),
        ),
    )

    private val risk = massif.avalancheRisk!!

    private fun reading(summit: Int = 2069, village: Int = 1650) =
        assertNotNull(SnowDepthReport(massifs = mapOf("3" to massif)).reading(SnowDepthTests.resort(summit, village), SnowDepthTests.now))

    // Danger level

    @Test
    fun dangerLevelFollowsTheBoundary() {
        assertEquals(3, risk.level(2199))
        assertEquals(4, risk.level(2200))
        assertEquals(2, AvalancheRisk(below = 2, above = 2, maximum = 2).level(3000))
    }

    /** "4/5 High above 2,200 m, 3/5 Considerable below" or one level, in the app's words. */
    @Test
    fun summaryNamesBothLevelsWhenTheBoundaryCrossesTheSkiArea() {
        assertEquals(AvalancheRisk.Summary.Split(above = 4, boundary = 2200, below = 3), risk.summary(1650, 2500))
        assertEquals(AvalancheRisk.Summary.Single(3), risk.summary(1650, 2069), "Entirely below the boundary")
        assertEquals(AvalancheRisk.Summary.Single(4), risk.summary(2300, 3456), "Entirely above it")
    }

    @Test
    fun readingGivesTheResortsHighestLevel() {
        assertEquals(3, reading().avalancheLevel)
        assertEquals(4, reading(summit = 3456, village = 2100).avalancheLevel)
        assertEquals(AvalancheRisk.Summary.Split(4, 2200, 3), reading(summit = 3456, village = 2100).avalancheSummary())
    }

    // History

    @Test
    fun changeOverTheWeekAtTheSummit() {
        // 2069 m: about 105 cm today, 9 cm a week before (3 to 50 north, 3 to 40 south).
        assertEquals(95 to 6, massif.change(2069))
        assertEquals(95 to 6, reading().change())
        assertEquals(true, reading().isGrowing)
    }

    @Test
    fun noChangeWithoutAHistory() {
        val reading = assertNotNull(SnowDepthTests.report.reading(SnowDepthTests.resort(), SnowDepthTests.now))
        assertNull(reading.change())
        assertNull(reading.isGrowing)
        assertNull(reading.avalancheSummary())
    }

    @Test
    fun sidesAndSnowLineOfASnapshot() {
        val week = massif.history!![0]
        assertEquals(SnowDepthTests.depth(50), week.depth(SnowDepthReport.Side.NORTH, 2500))
        assertEquals(SnowDepthTests.depth(40), week.depth(SnowDepthReport.Side.SOUTH, 2500))
        assertEquals(1800, week.snowLine)
    }

    /** The trimming for the Watch comes with Wear OS; the round trip is what the app needs. */
    @Test
    fun decodesTheFullFileAndTrimsItForTheWatch() {
        val report = SnowDepthReport(massifs = mapOf("3" to massif))
        assertEquals(report, SnowDepthReport.fromJson(SnowDepthReport.json.encodeToString(report)))
    }
}
