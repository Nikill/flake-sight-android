package com.tanukee.flakesight.core

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of `ForecastBriefingTests.swift`: how the hourly forecast becomes the
 * resort screen's sentences. Hours start at midnight in Paris on Friday 15
 * January 2027. The sentences are built in the app, so wording tests check
 * the values they read; `sentencesReadInFrench` is covered by the app's
 * French strings. Keep the two files in step (see IOS_SYNC.md).
 */
class ForecastBriefingTests {
    private val paris = ZoneId.of("Europe/Paris")

    /** Friday 15 January 2027, 09:00 in Paris. */
    private val now = Instant.ofEpochSecond(1_800_000_000)
    private val friday = LocalDate.of(2027, 1, 15)
    private val fridayStart = friday.atStartOfDay(paris).toInstant()
    private val summit = ForecastLevel.Summit(2700)
    private val village = ForecastLevel.Village(1650)

    /** One calm, cold, half-cloudy hour; tests change what they look at. */
    class Hour(
        var snow: Double = 0.0,
        var rain: Double = 0.0,
        var freezingLevel: Double = 2_000.0,
        var gusts: Double = 20.0,
        var cloud: Int = 50,
        var visibility: Double = 20_000.0,
        var snowDepth: Double? = null,
    )

    private fun hours(index: Int) = fridayStart + Duration.ofHours(index.toLong())

    private fun level(level: ForecastLevel, count: Int, change: Hour.(Int) -> Unit) = LevelHourlyForecast(
        level,
        List(count) { index ->
            val hour = Hour().apply { change(index) }
            HourlyForecast(hours(index), hour.snow, hour.rain, hour.freezingLevel, hour.gusts, hour.cloud, hour.visibility, hour.snowDepth)
        },
    )

    private fun briefing(
        now: Instant = this.now,
        hours: Int = 48,
        summit: Hour.(Int) -> Unit = {},
        village: Hour.(Int) -> Unit = {},
    ) = ForecastBriefing(
        DetailedForecast("test", listOf(level(this.summit, hours, summit), level(this.village, hours, village)), now),
        now,
        paris,
    )

    // Periods

    @Test
    fun periodsFollowTheSkiDay() {
        val briefing = briefing()
        val saturday = friday.plusDays(1)
        assertEquals(
            listOf(
                friday to ForecastPeriod.Part.MORNING, friday to ForecastPeriod.Part.AFTERNOON, friday to ForecastPeriod.Part.NIGHT,
                saturday to ForecastPeriod.Part.MORNING, saturday to ForecastPeriod.Part.AFTERNOON, saturday to ForecastPeriod.Part.NIGHT,
            ),
            briefing.periods.map { it.period.day to it.period.part },
        )
        val night = briefing.periods[2].period
        assertEquals(hours(18), night.start)
        assertEquals(hours(33), night.end)
        assertFalse(night.isSkiing)
        assertTrue(briefing.periods.all { period -> period.levels.map { it.level } == listOf(summit, village) })
    }

    @Test
    fun hoursBeforeNineBelongToTheNightBefore() {
        val first = briefing(now = fridayStart).periods[0].period
        assertEquals(friday.minusDays(1) to ForecastPeriod.Part.NIGHT, first.day to first.part)
        assertEquals(hours(-6), first.start)
    }

    @Test
    fun pastHoursAreLeftOut() {
        // 10:30 on Friday: the current hour still counts, earlier ones do not.
        val briefing = briefing(now = now + Duration.ofMinutes(90), summit = {
            if (it == 9) snow = 5.0
            if (it == 10) snow = 1.0
        })
        assertEquals(1.0, briefing.periods[0].levels[0].snowfall)
    }

    @Test
    fun periodConditionsFoldTheirHours() {
        val briefing = briefing(summit = { index ->
            if (index in 9 until 13) {
                freezingLevel = index * 100.0
                gusts = index * 3.0
                cloud = if (index == 9) 100 else 0
                visibility = index * 100.0
            }
        })
        val morning = briefing.periods[0].levels[0]
        assertEquals(900.0, morning.minFreezingLevel)
        assertEquals(36.0, morning.maxGusts)
        assertEquals(25.0, morning.meanCloudCover)
        assertEquals(900.0, morning.minVisibility)
    }

    // Snow soon

    @Test
    fun noSnowSoonIsSaidPlainly() {
        assertNull(briefing().upcomingSnow)
    }

    @Test
    fun snowLaterTodayGivesItsHours() {
        val snow = briefing(summit = { if (it in 15 until 21) snow = 0.5 }).upcomingSnow!!
        assertEquals(3.0, snow.amount)
        assertEquals(UpcomingSnow(summit, hours(15), hours(21), 3.0), snow)
    }

    @Test
    fun snowFallingNowSaysUntilWhen() {
        // The hour before 09:00 is over and does not count.
        val snow = briefing(summit = { if (it in 8 until 12) snow = 1.0 }).upcomingSnow!!
        assertTrue(snow.start <= now, "Snowing now")
        assertEquals(hours(12), snow.end)
        assertEquals(3.0, snow.amount)
    }

    @Test
    fun snowTomorrowIsNamedAndADayAheadIsTheLimit() {
        // Saturday 06:00, then Saturday 09:00, 24 hours from now.
        val snow = briefing(summit = {
            if (it == 30) snow = 2.0
            if (it == 33) snow = 5.0
        }).upcomingSnow!!
        assertEquals(2.0, snow.amount)
        assertEquals(hours(30) to hours(31), snow.start to snow.end)
    }

    @Test
    fun aDustingIsNotSnow() {
        assertNull(briefing(summit = { snow = 0.05 }).upcomingSnow)
        assertNull(briefing(summit = { if (it in 12 until 16) snow = 0.1 }).upcomingSnow)
    }

    @Test
    fun snowInTheVillageOnlyIsNotCounted() {
        assertNull(briefing(village = { if (it in 15 until 21) snow = 1.0 }).upcomingSnow)
    }

    // Snow on the ground

    /** 09:00 is hour 9; the hours either side say something else. */
    @Test
    fun theDepthIsTheCurrentHoursAtTheSummit() {
        val briefing = briefing(summit = { snowDepth = if (it == 9) 93.0 else 40.0 }, village = { snowDepth = 20.0 })
        assertEquals(SnowDepth(summit, 93.0), briefing.snowDepth)
        assertEquals(95, briefing.roundedSnowDepth)
    }

    @Test
    fun theCurrentHourCountsUntilItEnds() {
        assertEquals(9.0, briefing(now = now + Duration.ofSeconds(3599), summit = { snowDepth = it.toDouble() }).snowDepth?.depth)
    }

    @ParameterizedTest
    @CsvSource("2.4,", "2.5, 5", "12.4, 10", "152.6, 155")
    fun depthsAreRoundedToFiveCentimetres(depth: Double, shown: Int?) {
        assertEquals(shown, briefing(summit = { snowDepth = depth }).roundedSnowDepth)
    }

    @Test
    fun noDepthSaysNothing() {
        assertNull(briefing().snowDepth)
        assertNull(briefing().roundedSnowDepth)
        // No hour covers a moment past the forecast.
        assertNull(briefing(now = hours(48), summit = { snowDepth = 80.0 }).snowDepth)
    }

    // Good days

    private val saturday = friday.plusDays(1)

    @Test
    fun aClearCalmDayAfterFreshSnowIsGood() {
        val briefing = briefing(summit = {
            if (it in 20 until 32) snow = 1.0
            if (it in 33 until 48) cloud = 10
        })
        assertEquals(1, briefing.goodDays.size)
        assertTrue(briefing.goodDays[0].isWholeDay)
        assertEquals(12.0, briefing.goodDays[0].freshSnow)
        assertEquals(saturday, briefing.goodDays[0].period.day)
    }

    @Test
    fun onlyTheGoodPartOfTheDayIsNamed() {
        val day = briefing(summit = {
            if (it in 20 until 32) snow = 1.0
            if (it in 33 until 37) cloud = 10
        }).goodDays.single()
        assertFalse(day.isWholeDay)
        assertEquals(saturday to ForecastPeriod.Part.MORNING, day.period.day to day.period.part)
    }

    @Test
    fun aThawAtTheSummitSpoilsAGoodDay() {
        val briefing = briefing(summit = {
            if (it in 20 until 32) snow = 1.0
            if (it in 33 until 37) {
                cloud = 10
                freezingLevel = 2_900.0
            }
        })
        assertTrue(briefing.goodDays.isEmpty())
    }

    @Test
    fun fogUnderAClearSkySpoilsAGoodDay() {
        val briefing = briefing(summit = {
            if (it in 20 until 32) snow = 1.0
            if (it in 33 until 37) cloud = 10
            if (it == 34) visibility = 300.0
        })
        assertTrue(briefing.goodDays.isEmpty())
    }

    @Test
    fun rainOrWindSpoilAGoodDay() {
        val rainy = briefing(summit = {
            if (it in 20 until 32) snow = 1.0
            if (it in 33 until 37) cloud = 10
            if (it == 34) rain = 1.0
        })
        assertTrue(rainy.goodDays.isEmpty())
        val windy = briefing(summit = {
            if (it in 20 until 32) snow = 1.0
            if (it in 33 until 37) {
                cloud = 10
                gusts = 45.0
            }
        })
        assertTrue(windy.goodDays.isEmpty())
    }

    @Test
    fun clearSkiesOnlyInTheVillageDoNotCount() {
        val briefing = briefing(summit = { if (it in 20 until 32) snow = 1.0 }, village = { if (it in 33 until 37) cloud = 10 })
        assertTrue(briefing.goodDays.isEmpty())
    }

    @Test
    fun twoGoodDaysAreTwoSentences() {
        val days = briefing(hours = 72, summit = {
            if (it in 20 until 32 || it in 42 until 54) snow = 1.0
            if (it in 33 until 37 || it in 57 until 66) cloud = 10
        }).goodDays
        assertEquals(
            listOf(Triple(saturday, ForecastPeriod.Part.MORNING, false), Triple(saturday.plusDays(1), ForecastPeriod.Part.MORNING, true)),
            days.map { Triple(it.period.day, it.period.part, it.isWholeDay) },
        )
        assertEquals(listOf(12.0, 12.0), days.map { it.freshSnow })
    }

    @Test
    fun sunshineWithoutFreshSnowIsNotAGoodDay() {
        assertTrue(briefing(summit = { cloud = 0 }).goodDays.isEmpty())
    }
}
