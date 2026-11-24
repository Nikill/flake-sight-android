package com.tanukee.flakesight.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Port of `MountainProfileTests.swift`: how the forecast is laid across the
 * mountain. Wording tests check the values the app formats; the models'
 * range comes with the models table. Keep the two files in step.
 */
class MountainProfileTests {
    private val paris = ZoneId.of("Europe/Paris")

    /** Friday 15 January 2027, 09:00 in Paris. */
    private val now = Instant.ofEpochSecond(1_800_000_000)
    private val friday = LocalDate.of(2027, 1, 15)
    private val summit = ForecastLevel.Summit(2100)
    private val village = ForecastLevel.Village(1600)

    private fun day(index: Long, condition: WeatherCondition = WeatherCondition.CLOUDY, snow: Double = 0.0) =
        DailyForecast(friday.plusDays(index), condition, 0.0, snow)

    /** Two days at both levels: snow at the top on Friday while the village gets rain, then a dry Saturday. */
    private val forecast = ResortForecast(
        "test",
        listOf(
            LevelForecast(summit, listOf(day(0, WeatherCondition.SNOW, snow = 38.0), day(1))),
            LevelForecast(village, listOf(day(0, WeatherCondition.RAIN), day(1, WeatherCondition.CLEAR))),
        ),
        now,
    )

    /** Friday and Saturday hour by hour: the freezing level at 1,700 m then 2,500 m, 2 mm of rain an hour in the village on Friday morning. */
    private fun detailed(): DetailedForecast {
        val start = friday.atStartOfDay(paris).toInstant()
        fun hours(rain: (Int) -> Double) = List(48) { index ->
            HourlyForecast(start + Duration.ofHours(index.toLong()), 0.0, rain(index), if (index < 24) 1_700.0 else 2_500.0, null, null, null)
        }
        return DetailedForecast(
            "test",
            listOf(LevelHourlyForecast(summit, hours { 0.0 }), LevelHourlyForecast(village, hours { if (it in 6 until 9) 2.0 else 0.0 })),
            now,
        )
    }

    private fun profile(detailed: DetailedForecast? = detailed()) = assertNotNull(MountainProfile.of(forecast, detailed, paris))

    @Test
    fun eachDayHasEveryLevelFromTheTop() {
        val profile = profile()
        assertEquals(listOf(friday, friday.plusDays(1)), profile.days.map { it.date })
        assertEquals(listOf<ForecastLevel>(summit, village), profile.days[0].spots.map { it.level })
        assertEquals(listOf(2100, 1600), profile.days[0].spots.map { it.altitude })
        assertEquals(listOf(2100, 1600), profile.altitudes)
    }

    /** "38 cm" and "Rain" in the app. */
    @Test
    fun snowUpTopAndRainInTheVillage() {
        val spots = profile().days[0].spots
        assertEquals(listOf(MountainProfile.Precipitation.SNOW, MountainProfile.Precipitation.RAIN), spots.map { it.precipitation })
        assertEquals(6.0, spots[1].rain)
        assertEquals(38, spots[0].forecast.snowfallCentimetres)
    }

    @Test
    fun snowAndRainTogetherAreMixed() {
        val spot = MountainProfile.Spot(village, 1600, day(0, snow = 4.0), rain = 3.0)
        assertEquals(MountainProfile.Precipitation.MIXED, spot.precipitation)
        assertEquals(4, spot.forecast.snowfallCentimetres)
    }

    @Test
    fun withoutTheHourlyForecastTheSkySaysRain() {
        val profile = profile(detailed = null)
        assertEquals(listOf(MountainProfile.Precipitation.SNOW, MountainProfile.Precipitation.RAIN), profile.days[0].spots.map { it.precipitation })
        assertEquals(listOf(MountainProfile.Precipitation.NONE, MountainProfile.Precipitation.NONE), profile.days[1].spots.map { it.precipitation })
        assertEquals(WeatherCondition.CLEAR, profile.days[1].spots[1].forecast.condition)
        assertNull(profile.days[0].freezingLevel)
    }

    /** "0 °C at 1,700 m" in the app. */
    @Test
    fun theFreezingLevelIsTheDaysAverage() {
        val profile = profile()
        assertEquals(listOf(1_700.0, 2_500.0), profile.days.map { it.freezingLevel })
        assertEquals(1_700, profile.days[0].roundedFreezingLevel)
    }

    @Test
    fun theDrawingLeavesRoomAroundTheLevels() {
        val profile = profile(detailed = null)
        // 500 m between the levels: 300 m of room, the minimum.
        assertEquals(1_300.0..2_400.0, profile.altitudeDomain)
        assertEquals(2_400.0, profile.clamped(2_500.0))
        assertEquals(1_800.0, profile.clamped(1_800.0))
        assertEquals(1_300.0, profile.clamped(900.0))
    }

    @Test
    fun aResortWithoutAltitudesHasNoProfile() {
        val forecast = ResortForecast("test", listOf(LevelForecast(ForecastLevel.ResortCentre, listOf(day(0)))), now)
        assertNull(MountainProfile.of(forecast, null, paris))
    }
}
