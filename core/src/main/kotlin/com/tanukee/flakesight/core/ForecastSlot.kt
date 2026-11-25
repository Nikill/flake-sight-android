package com.tanukee.flakesight.core

import java.time.Instant
import java.time.ZoneId

/**
 * Whether the forecast strip and the altitude profile show days or four-hour
 * slots, remembered for every resort. The raw values are stored, so existing
 * ones never change. Port of `ForecastGranularity`; "Days" and "Hours" are app strings.
 */
enum class ForecastGranularity(val rawValue: String) {
    DAYS("days"), HOURS("hours");

    companion object {
        fun fromRawValue(raw: String?): ForecastGranularity = entries.firstOrNull { it.rawValue == raw } ?: DAYS
    }
}

/**
 * Four hours of the hourly forecast at every level, to zoom on when the snow
 * falls. Slots are aligned on midnight in the resort's time zone (00:00, 04:00,
 * 08:00, 12:00, 16:00, 20:00); the slot under way comes first, so the view
 * opens on now. Port of `ForecastSlot`; the wording is the app's.
 */
data class ForecastSlot(
    /** Start of the slot, on the hour. */
    val start: Instant,
    /** Start of the next slot. */
    val end: Instant,
    /** Highest level first, as the forecast's levels. */
    val spots: List<Spot>,
    /** The lowest 0 °C altitude of the slot, metres; `null` without one. */
    val freezingLevel: Double?,
    /** Whether now falls inside the slot. */
    val isCurrent: Boolean,
) {
    /** One level over the slot. */
    data class Spot(
        val level: ForecastLevel,
        /** Centimetres over the slot. */
        val snowfall: Double,
        /** Millimetres over the slot. */
        val rain: Double,
        /** The most telling sky of the slot; `null` when the hours carry none. */
        val condition: WeatherCondition?,
        /** The highest temperature of the slot, °C. */
        val maxTemperature: Double?,
        /** The strongest gust of the slot, km/h. */
        val maxGusts: Double?,
    ) {
        val precipitation: MountainProfile.Precipitation
            get() {
                val snows = DailyForecast.wholeNumber(snowfall) >= DailyForecast.SNOW_THRESHOLD
                val rains = rain >= ForecastBriefing.RAIN_THRESHOLD
                return when {
                    snows && rains -> MountainProfile.Precipitation.MIXED
                    snows -> MountainProfile.Precipitation.SNOW
                    rains -> MountainProfile.Precipitation.RAIN
                    else -> MountainProfile.Precipitation.NONE
                }
            }

        /** Whole centimetres from one, otherwise `null` ("3 cm"). */
        val snowfallCentimetres: Int? get() = DailyForecast.centimetres(snowfall)

        /** Whole millimetres from one, otherwise `null` ("4 mm"). */
        val rainMillimetres: Int? get() = DailyForecast.wholeNumber(rain).takeIf { it >= ForecastBriefing.RAIN_THRESHOLD }

        /** Whole degrees ("-9°"), `null` without a temperature. */
        val maxTemperatureDegrees: Int? get() = maxTemperature?.let(DailyForecast::wholeNumber)
    }

    /** Whether the slot opens a new day, for the day's name above it. */
    fun startsDay(zone: ZoneId): Boolean = start.atZone(zone).hour < LENGTH

    companion object {
        /** Hours in a slot. */
        const val LENGTH = 4

        /** The slots of [detailed] that have not ended at [now], earliest first. */
        fun slots(detailed: DetailedForecast, now: Instant, zone: ZoneId): List<ForecastSlot> {
            fun slotStart(date: Instant): Instant {
                val local = date.atZone(zone)
                return local.withHour(local.hour / LENGTH * LENGTH).withMinute(0).withSecond(0).withNano(0).toInstant()
            }
            val byLevel = detailed.levels.map { level -> level.level to level.hours.groupBy { slotStart(it.start) } }
            val starts = byLevel.flatMap { it.second.keys }.toSortedSet()
            return starts.mapNotNull { start ->
                val end = start.atZone(zone).plusHours(LENGTH.toLong()).toInstant()
                if (end <= now) return@mapNotNull null
                val spots = byLevel.mapNotNull { (level, grouped) ->
                    val hours = grouped[start]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    Spot(
                        level = level,
                        snowfall = hours.sumOf { it.snowfall },
                        rain = hours.sumOf { it.rain },
                        condition = mostTelling(hours.mapNotNull { it.condition }),
                        maxTemperature = hours.mapNotNull { it.temperature }.maxOrNull(),
                        maxGusts = hours.mapNotNull { it.windGusts }.maxOrNull(),
                    )
                }
                if (spots.isEmpty()) return@mapNotNull null
                // The same at every level of a resort; the top one's hours say it.
                val freezing = byLevel.firstOrNull()?.second?.get(start)?.mapNotNull { it.freezingLevel }?.minOrNull()
                ForecastSlot(start, end, spots, freezing, isCurrent = start <= now && now < end)
            }
        }

        /** The sky that says most about the slot. */
        internal fun mostTelling(conditions: List<WeatherCondition>): WeatherCondition? = listOf(
            WeatherCondition.SNOW, WeatherCondition.THUNDERSTORM, WeatherCondition.RAIN, WeatherCondition.FOG,
            WeatherCondition.CLOUDY, WeatherCondition.PARTLY_CLOUDY, WeatherCondition.CLEAR,
        ).firstOrNull { it in conditions }
    }
}
