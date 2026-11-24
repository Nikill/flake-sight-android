package com.tanukee.flakesight.core

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Port of `SnowfallRangeTests.swift`: how the models' amounts become each
 * day's range and confidence, and how a row of gauges is scaled. Wording tests
 * check the values the app formats. Keep the two files in step.
 */
class SnowfallRangeTests {
    /** Friday 15 January 2027, 09:00 in Paris. */
    private val now = Instant.ofEpochSecond(1_800_000_000)
    private val friday = LocalDate.of(2027, 1, 15)

    private fun day(index: Long, snow: Double, range: SnowfallRange? = null) =
        DailyForecast(friday.plusDays(index), WeatherCondition.SNOW, -4.0, snow, range)

    // Range

    @Test
    fun theRangeRunsFromTheSmallestToTheLargestModel() {
        val range = assertNotNull(SnowfallRange.of(30.0, listOf(15.9, 48.2, 27.2, 36.2)))
        assertEquals(15.9, range.low)
        assertEquals(48.2, range.high)
        assertEquals(ForecastConfidence.LOW, range.confidence)
    }

    @Test
    fun theHeadlineFigureAlwaysSitsInsideTheRange() {
        val range = assertNotNull(SnowfallRange.of(45.0, listOf(20.0, 30.0)))
        assertEquals(20.0 to 45.0, range.low to range.high)
    }

    @Test
    fun modelsWithoutAnAmountAreLeftOut() {
        val range = assertNotNull(SnowfallRange.of(10.0, listOf(null, 8.0, null, 12.0)))
        assertEquals(8.0 to 12.0, range.low to range.high)
    }

    @Test
    fun oneModelIsNoComparison() {
        assertNull(SnowfallRange.of(10.0, listOf(null, 8.0, null, null)))
        assertNull(SnowfallRange.of(10.0, emptyList()))
    }

    @Test
    fun aDryDayHasNoRange() {
        assertNull(SnowfallRange.of(0.0, listOf(0.0, 0.3, 0.4, 0.0)))
        // One model seeing a centimetre puts snow in play.
        assertEquals(1.2, SnowfallRange.of(0.0, listOf(0.0, 1.2, 0.0, 0.0))?.high)
    }

    /** Close means within 5 cm, or within half the high end. */
    @ParameterizedTest
    @CsvSource("0, 5, HIGH", "0, 5.1, LOW", "20, 30, HIGH", "15, 30, HIGH", "14.9, 30, LOW", "5, 20, LOW")
    fun confidenceFollowsTheSpread(first: Double, second: Double, confidence: ForecastConfidence) {
        assertEquals(confidence, SnowfallRange.of(first, listOf(first, second))?.confidence)
    }

    /** "5-20 cm" and "12 cm" in the app, from the whole centimetres. */
    @Test
    fun rangesReadInWholeCentimetres() {
        val wide = SnowfallRange(4.6, 20.4, ForecastConfidence.LOW)
        assertEquals(5 to 20, wide.lowCentimetres to wide.highCentimetres)
        val narrow = SnowfallRange(11.6, 12.4, ForecastConfidence.HIGH)
        assertEquals(12 to 12, narrow.lowCentimetres to narrow.highCentimetres)
    }

    // Forecast

    private fun models(summit: List<Double?>, village: List<Double?>? = null) = ModelSnowfall(
        "test",
        listOfNotNull(
            ModelSnowfall.Level(ForecastLevel.Summit(2700), mapOf(friday to summit)),
            village?.let { ModelSnowfall.Level(ForecastLevel.Village(1200), mapOf(friday to it)) },
        ),
        now,
    )

    @Test
    fun eachDayGetsTheRangeOfItsLevelAndDate() {
        val forecast = ResortForecast(
            "test",
            listOf(
                LevelForecast(ForecastLevel.Summit(2700), listOf(day(0, 10.0), day(1, 4.0)), recentSnowfall = 12.0),
                LevelForecast(ForecastLevel.Village(1200), listOf(day(0, 2.0), day(1, 0.0))),
            ),
            now,
        )
        val ranged = forecast.withRanges(models(listOf(8.0, 14.0), village = listOf(0.0, 1.0)))

        assertEquals(SnowfallRange(8.0, 14.0, ForecastConfidence.HIGH), ranged.levels[0].days[0].snowfallRange)
        assertEquals(SnowfallRange(0.0, 2.0, ForecastConfidence.HIGH), ranged.levels[1].days[0].snowfallRange)
        // Saturday is not in the models' answer.
        assertNull(ranged.levels[0].days[1].snowfallRange)
        assertEquals(12.0, ranged.levels[0].recentSnowfall)
        assertEquals(listOf(10.0, 4.0), ranged.levels[0].days.map { it.snowfall })
    }

    // Gauges

    @Test
    fun theDeepestAmountFillsAGauge() {
        val scale = SnowGaugeScale(listOf(day(0, 45.0, SnowfallRange(40.0, 61.0, ForecastConfidence.LOW)), day(1, 5.0)))
        assertEquals(61.0, scale.ceiling)
        assertEquals(1.0, scale.fraction(61.0))
        assertEquals(0.0, scale.fraction(0.0))
        assertEquals(1.0, scale.fraction(80.0))
    }

    @Test
    fun aLightWeekDoesNotFillTheGauges() {
        val scale = SnowGaugeScale(listOf(day(0, 2.0), day(1, 0.0)))
        assertEquals(SnowGaugeScale.MINIMUM_CEILING, scale.ceiling)
        assertEquals(0.2, scale.fraction(2.0))
    }
}
