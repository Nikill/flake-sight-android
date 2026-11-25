package com.tanukee.flakesight.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hourly forecast grouped into four-hour slots for the hours view. Port
 * of `ForecastSlotTests.swift`: the wording tests check the values the app
 * formats, and `wordsReadInFrench` is the app's French strings. Keep the two files in step.
 */
class ForecastSlotTests {
    private val paris = ZoneId.of("Europe/Paris")
    /** Midnight in Paris at the start of Friday 15 January 2027. */
    private val fridayStart: Instant = LocalDate.of(2027, 1, 15).atStartOfDay(paris).toInstant()
    private val summit = ForecastLevel.Summit(2100)
    private val village = ForecastLevel.Village(1600)

    private fun hoursAfter(start: Instant, hours: Double): Instant = start.plusSeconds((hours * 3600).toLong())

    /**
     * Two days hour by hour: at the summit, 1 cm of snow an hour from 20:00 to
     * midnight on Friday under a snowy sky, -5 °C rising to -3 °C at 14:00; in
     * the village, 2 mm of rain an hour over the same hours.
     */
    private fun detailed(): DetailedForecast {
        fun hours(snow: Double, rain: Double, temperature: Double) = (0 until 48).map { index ->
            val evening = index in 20 until 24
            HourlyForecast(
                start = hoursAfter(fridayStart, index.toDouble()),
                snowfall = if (evening) snow else 0.0,
                rain = if (evening) rain else 0.0,
                freezingLevel = (2_000 - index * 10).toDouble(),
                windGusts = index.toDouble(),
                cloudCover = null,
                visibility = null,
                condition = when {
                    evening -> if (snow > 0) WeatherCondition.SNOW else WeatherCondition.RAIN
                    index < 12 -> WeatherCondition.CLEAR
                    else -> WeatherCondition.CLOUDY
                },
                temperature = if (index == 14) temperature + 2 else temperature,
            )
        }
        return DetailedForecast(
            "test",
            listOf(LevelHourlyForecast(summit, hours(1.0, 0.0, -5.0)), LevelHourlyForecast(village, hours(0.0, 2.0, 3.0))),
            fridayStart,
        )
    }

    @Test
    fun slotsAreFourHoursFromMidnightAndStartWithTheCurrentOne() {
        // Friday 09:30: the 08:00 slot is under way.
        val slots = ForecastSlot.slots(detailed(), hoursAfter(fridayStart, 9.5), paris)
        assertEquals(hoursAfter(fridayStart, 8.0), slots.first().start)
        assertTrue(slots.first().isCurrent)
        assertTrue(slots.drop(1).none { it.isCurrent })
        // 08:00 to the end of Saturday: four slots left on Friday, six on Saturday.
        assertEquals(10, slots.size)
        assertTrue(slots.all { it.end.epochSecond - it.start.epochSecond == 4 * 3600L })
    }

    @Test
    fun eachLevelAddsUpItsSlot() {
        val evening = ForecastSlot.slots(detailed(), fridayStart, paris).first { it.start == hoursAfter(fridayStart, 20.0) }
        assertEquals(listOf(summit, village), evening.spots.map { it.level })
        assertEquals(4.0, evening.spots[0].snowfall)
        assertEquals(MountainProfile.Precipitation.SNOW, evening.spots[0].precipitation)
        assertEquals(WeatherCondition.SNOW, evening.spots[0].condition)
        assertEquals(8.0, evening.spots[1].rain)
        assertEquals(MountainProfile.Precipitation.RAIN, evening.spots[1].precipitation)
        assertEquals(8, evening.spots[1].rainMillimetres)
        // The lowest freezing level of the slot, the strongest gust.
        assertEquals((2_000 - 23 * 10).toDouble(), evening.freezingLevel)
        assertEquals(23.0, evening.spots[0].maxGusts)
    }

    @Test
    fun theSlotKeepsItsHighestTemperatureAndMostTellingSky() {
        val afternoon = ForecastSlot.slots(detailed(), fridayStart, paris).first { it.start == hoursAfter(fridayStart, 12.0) }
        assertEquals(-3.0, afternoon.spots[0].maxTemperature)
        assertEquals(-3, afternoon.spots[0].maxTemperatureDegrees)
        assertEquals(WeatherCondition.CLOUDY, afternoon.spots[0].condition)
        assertEquals(WeatherCondition.SNOW, ForecastSlot.mostTelling(listOf(WeatherCondition.CLEAR, WeatherCondition.RAIN, WeatherCondition.SNOW)))
        assertNull(ForecastSlot.mostTelling(emptyList()))
    }

    /** The sentence is the app's; here, what it reads. */
    @Test
    fun aSlotReadsAsOneSentence() {
        val slots = ForecastSlot.slots(detailed(), fridayStart, paris)
        val evening = slots.first { it.start == hoursAfter(fridayStart, 20.0) }
        assertFalse(evening.startsDay(paris))
        assertTrue(slots.first().startsDay(paris))
        assertEquals(4, evening.spots[0].snowfallCentimetres)
        assertEquals(-5, evening.spots[0].maxTemperatureDegrees)
        assertEquals(3, evening.spots[1].maxTemperatureDegrees)
    }

    @Test
    fun endedSlotsAreLeftOut() {
        // Saturday 23:00: only the last slot is left.
        val slots = ForecastSlot.slots(detailed(), hoursAfter(fridayStart, 47.0), paris)
        assertEquals(1, slots.size)
        assertTrue(slots.first().isCurrent)
    }
}
