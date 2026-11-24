package com.tanukee.flakesight.core

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of `ForecastDisplayTests.swift`. Sentences are built from string
 * resources in the app, so the wording tests become tests of the values the
 * app formats; the snow summary and VoiceOver tests come with their screens.
 * Keep the two files in step (see IOS_SYNC.md).
 */
class ForecastDisplayTests {
    private val paris = ZoneId.of("Europe/Paris")

    /** Friday 15 January 2027, 09:00 in Paris. */
    private val now = Instant.ofEpochSecond(1_800_000_000)
    private val friday = LocalDate.of(2027, 1, 15)

    private fun day(index: Long, condition: WeatherCondition = WeatherCondition.CLEAR, max: Double? = -4.0, snow: Double = 0.0) =
        DailyForecast(friday.plusDays(index), condition, max, snow)

    // Conditions

    @ParameterizedTest
    @CsvSource(
        "0, CLEAR", "1, PARTLY_CLOUDY", "2, PARTLY_CLOUDY", "3, CLOUDY", "45, FOG", "48, FOG",
        "51, RAIN", "55, RAIN", "57, RAIN", "61, RAIN", "65, RAIN", "67, RAIN", "80, RAIN", "82, RAIN",
        "71, SNOW", "75, SNOW", "77, SNOW", "85, SNOW", "86, SNOW", "95, THUNDERSTORM", "99, THUNDERSTORM",
        "42, CLOUDY", "-1, CLOUDY",
    )
    fun wmoCodesFoldIntoConditions(code: Int, expected: WeatherCondition) {
        assertEquals(expected, WeatherCondition.fromWmoCode(code))
    }

    // Snow rule

    @ParameterizedTest
    @CsvSource("0.0, false", "0.4, false", "0.5, true", "1.0, true", "12.0, true")
    fun snowIsExpectedFromAWholeCentimetre(snowfall: Double, expected: Boolean) {
        assertEquals(expected, day(0, WeatherCondition.CLOUDY, snow = snowfall).isSnowExpected)
    }

    @Test
    fun aSnowSkyCountsEvenWithoutMeasurableSnow() {
        assertTrue(day(0, WeatherCondition.SNOW, snow = 0.0).isSnowExpected)
    }

    /** The app formats the centimetres; under one there is nothing to show. */
    @Test
    fun snowfallLabelHidesLessThanACentimetre() {
        assertNull(day(0, snow = 0.4).snowfallCentimetres)
        assertEquals(12, day(0, snow = 12.3).snowfallCentimetres)
    }

    @Test
    fun snowTotalSumsTheDays() {
        val level = LevelForecast(ForecastLevel.Summit(2700), listOf(day(0, snow = 1.5), day(1, snow = 2.0), day(2)))
        assertEquals(3.5, level.snowTotal)
    }

    // Recent snow

    private fun recent(vararg totals: Pair<ForecastLevel, Double?>) =
        ResortForecast("test", totals.map { LevelForecast(it.first, listOf(day(0)), it.second) }, now)

    @Test
    fun recentSnowNamesEveryLevel() {
        val forecast = recent(ForecastLevel.Summit(2700) to 24.4, ForecastLevel.Village(1200) to 8.0)
        assertEquals(listOf(ForecastLevel.Summit(2700) to 24, ForecastLevel.Village(1200) to 8), forecast.recentSnow())
    }

    @Test
    fun recentSnowSaysWhichLevelHadNone() {
        val forecast = recent(ForecastLevel.Summit(2700) to 12.0, ForecastLevel.Village(1200) to 0.4)
        assertEquals(listOf(ForecastLevel.Summit(2700) to 12, ForecastLevel.Village(1200) to null), forecast.recentSnow())
    }

    @Test
    fun recentSnowAtASingleLevel() {
        assertEquals(listOf<Pair<ForecastLevel, Int?>>(ForecastLevel.ResortCentre to 3), recent(ForecastLevel.ResortCentre to 3.0).recentSnow())
    }

    @Test
    fun noRecentSnowIsSaid() {
        val forecast = recent(ForecastLevel.Summit(2700) to 0.4, ForecastLevel.Village(1200) to 0.0)
        assertTrue(forecast.recentSnow()!!.all { it.second == null })
    }

    @Test
    fun recentSnowNeedsEveryLevel() {
        assertNull(recent(ForecastLevel.Summit(2700) to 12.0, ForecastLevel.Village(1200) to null).recentSnow())
        assertNull(ResortForecast("test", listOf(LevelForecast(ForecastLevel.Summit(2700), listOf(day(0)))), now).recentSnow())
    }

    // Temperature and days

    @Test
    fun temperaturesAreWholeDegrees() {
        assertEquals(-4, day(0, max = -4.4).maxTemperatureDegrees)
        assertEquals(1, day(0, max = 0.6).maxTemperatureDegrees)
        assertEquals(0, day(0, max = -0.4).maxTemperatureDegrees)
        // Half away from zero, as on iOS.
        assertEquals(-5, day(0, max = -4.5).maxTemperatureDegrees)
        assertNull(day(0, max = null).maxTemperatureDegrees)
    }

    @Test
    fun weekdaysFollowTheResortTimeZoneAndLocale() {
        assertEquals("Fri", day(0).weekdayLabel(Locale.UK))
        assertEquals("Sat", day(1).weekdayLabel(Locale.UK))
        assertEquals("ven.", day(0).weekdayLabel(Locale.FRANCE))
        assertTrue(day(0).isToday(now, paris))
        assertFalse(day(1).isToday(now, paris))
    }

    // Levels

    @Test
    fun resortLevelsFollowTheCatalogueAltitudes() {
        fun resort(village: Int?, summit: Int?) = ForecastServiceTests.resort(village = village, summit = summit)
        assertEquals(listOf(ForecastLevel.Summit(2700), ForecastLevel.Village(1650)), resort(1650, 2700).forecastLevels)
        assertEquals(listOf<ForecastLevel>(ForecastLevel.Summit(2700)), resort(null, 2700).forecastLevels)
        assertEquals(listOf<ForecastLevel>(ForecastLevel.Village(1650)), resort(1650, null).forecastLevels)
        assertEquals(listOf<ForecastLevel>(ForecastLevel.ResortCentre), resort(null, null).forecastLevels)
    }
}
