package com.tanukee.flakesight.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * The coming days drawn across the mountain: each catalogued level at its
 * altitude with what falls there each day, and the freezing level between
 * them, so "snow at the summit, rain in the village" reads at a glance.
 * Port of `MountainProfile`; the models' ranges come with the models table.
 */
class MountainProfile private constructor(
    val days: List<Day>,
    /** The catalogued altitudes, highest first. */
    val altitudes: List<Int>,
    /** The altitudes the drawing spans: the levels with room around them. A freezing level outside is drawn at the edge. */
    val altitudeDomain: ClosedFloatingPointRange<Double>,
) {
    /** What falls at one level on one day. */
    enum class Precipitation { SNOW, RAIN, MIXED, NONE }

    /** One level on one day. */
    data class Spot(
        val level: ForecastLevel,
        val altitude: Int,
        val forecast: DailyForecast,
        /** Millimetres over the day, `null` without the hourly forecast. */
        val rain: Double?,
    ) {
        val precipitation: Precipitation
            get() {
                val snows = DailyForecast.wholeNumber(forecast.snowfall) >= DailyForecast.SNOW_THRESHOLD
                val rains = rain?.let { it >= ForecastBriefing.RAIN_THRESHOLD } ?: (forecast.condition == WeatherCondition.RAIN)
                return when {
                    snows && rains -> Precipitation.MIXED
                    snows -> Precipitation.SNOW
                    rains -> Precipitation.RAIN
                    else -> Precipitation.NONE
                }
            }
    }

    /** One day across the mountain. */
    data class Day(
        /** The day in the resort's time zone. */
        val date: LocalDate,
        /** Highest level first. */
        val spots: List<Spot>,
        /** The 0 °C altitude averaged over the day, in metres; `null` without the hourly forecast. */
        val freezingLevel: Double?,
    ) {
        /** The freezing level to the nearest hundred metres, for "0 °C at 1,800 m". */
        val roundedFreezingLevel: Int? get() = freezingLevel?.let { DailyForecast.wholeNumber(it / 100) * 100 }
    }

    /** [altitude] brought inside [altitudeDomain]. */
    fun clamped(altitude: Double): Double = min(altitudeDomain.endInclusive, max(altitudeDomain.start, altitude))

    companion object {
        /** Room above the summit and below the village, as a share of the gap between them, and never less than [MINIMUM_MARGIN] metres. */
        const val MARGIN_SHARE = 0.6
        const val MINIMUM_MARGIN = 300.0

        /** `null` for a resort without catalogued altitudes: there is no mountain to draw. */
        fun of(forecast: ResortForecast, detailed: DetailedForecast?, zone: ZoneId): MountainProfile? {
            val levels = forecast.levels.mapNotNull { level -> level.level.altitude?.let { level to it } }
            if (levels.isEmpty()) return null
            val top = levels.maxOf { it.second }
            val bottom = levels.minOf { it.second }

            val hourly = detailed?.levels.orEmpty()
                .distinctBy { it.level }
                .associate { level -> level.level to level.hours.groupBy { it.start.atZone(zone).toLocalDate() } }

            val days = forecast.days.indices.map { index ->
                val date = forecast.days[index].date
                val spots = levels.mapNotNull { (level, altitude) ->
                    val day = level.days.getOrNull(index) ?: return@mapNotNull null
                    val rain = hourly[level.level]?.let { byDay -> byDay[date].orEmpty().sumOf { it.rain } }
                    Spot(level.level, altitude, day, rain)
                }
                // The same at every level of a resort; the top one's hours say it.
                val freezing = hourly[levels[0].first.level]?.get(date).orEmpty().mapNotNull { it.freezingLevel }
                Day(date, spots, freezing.takeIf { it.isNotEmpty() }?.average())
            }
            val margin = max(MINIMUM_MARGIN, (top - bottom) * MARGIN_SHARE)
            return MountainProfile(days, levels.map { it.second }.sortedDescending(), (bottom - margin)..(top + margin))
        }
    }
}
