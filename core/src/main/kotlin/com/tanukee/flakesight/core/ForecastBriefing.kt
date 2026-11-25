package com.tanukee.flakesight.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One hour of the detailed forecast at one level, as Open-Meteo gives it.
 * Port of `HourlyForecast`. Values the model leaves out stay `null`; snow and rain count as none.
 */
data class HourlyForecast(
    /** Start of the hour. */
    val start: Instant,
    /** Centimetres over the hour. */
    val snowfall: Double,
    /** Millimetres over the hour. */
    val rain: Double,
    /** Metres above sea level; the same at every level of a resort. */
    val freezingLevel: Double?,
    /** km/h at 10 m. */
    val windGusts: Double?,
    /** Percent. */
    val cloudCover: Int?,
    /** Metres. */
    val visibility: Double?,
    /** Snow on the ground in centimetres, as the model estimates it. */
    val snowDepth: Double? = null,
    /** The sky for the hour, from its WMO code. */
    val condition: WeatherCondition? = null,
    /** °C at 2 m, at the level's altitude. */
    val temperature: Double? = null,
) {
    val end: Instant get() = start + ONE_HOUR

    internal companion object {
        val ONE_HOUR: Duration = Duration.ofHours(1)
    }
}

/** The forecast hours at one level, earliest first. */
data class LevelHourlyForecast(val level: ForecastLevel, val hours: List<HourlyForecast>)

/** Five days hour by hour at the summit and the village. Port of `DetailedForecast`. */
data class DetailedForecast(val resortID: String, val levels: List<LevelHourlyForecast>, val fetchedAt: Instant)

/**
 * A stretch of a ski day: the morning and the afternoon, when the lifts run,
 * and the night from 18:00 to 09:00, when most of the snow falls. Port of `ForecastPeriod`.
 */
data class ForecastPeriod(
    /** The resort-local day the period starts on. */
    val day: LocalDate,
    val part: Part,
    val start: Instant,
    val end: Instant,
) {
    enum class Part { MORNING, AFTERNOON, NIGHT }

    val isSkiing: Boolean get() = part != Part.NIGHT

    companion object {
        const val MORNING_START_HOUR = 9
        const val AFTERNOON_START_HOUR = 13
        const val NIGHT_START_HOUR = 18

        /** The period an hour falls in. Hours before 09:00 belong to the night that started the evening before. */
        fun containing(instant: Instant, zone: ZoneId): ForecastPeriod {
            val local = instant.atZone(zone)
            val today = local.toLocalDate()
            fun at(hour: Int, day: LocalDate) = day.atTime(hour, 0).atZone(zone).toInstant()
            return when {
                local.hour < MORNING_START_HOUR -> {
                    val yesterday = today.minusDays(1)
                    ForecastPeriod(yesterday, Part.NIGHT, at(NIGHT_START_HOUR, yesterday), at(MORNING_START_HOUR, today))
                }
                local.hour < AFTERNOON_START_HOUR ->
                    ForecastPeriod(today, Part.MORNING, at(MORNING_START_HOUR, today), at(AFTERNOON_START_HOUR, today))
                local.hour < NIGHT_START_HOUR ->
                    ForecastPeriod(today, Part.AFTERNOON, at(AFTERNOON_START_HOUR, today), at(NIGHT_START_HOUR, today))
                else ->
                    ForecastPeriod(today, Part.NIGHT, at(NIGHT_START_HOUR, today), at(MORNING_START_HOUR, today.plusDays(1)))
            }
        }
    }
}

/** What one period looks like at one level, from its hours. */
data class PeriodConditions(
    val level: ForecastLevel,
    /** Centimetres. */
    val snowfall: Double,
    /** Millimetres. */
    val rain: Double,
    /** Metres above sea level. */
    val minFreezingLevel: Double?,
    /** km/h. */
    val maxGusts: Double?,
    /** Percent. */
    val meanCloudCover: Double?,
    /** Metres. */
    val minVisibility: Double?,
) {
    internal constructor(level: ForecastLevel, hours: List<HourlyForecast>) : this(
        level = level,
        snowfall = hours.sumOf { it.snowfall },
        rain = hours.sumOf { it.rain },
        minFreezingLevel = hours.mapNotNull { it.freezingLevel }.minOrNull(),
        maxGusts = hours.mapNotNull { it.windGusts }.maxOrNull(),
        meanCloudCover = hours.mapNotNull { it.cloudCover }.takeIf { it.isNotEmpty() }?.average(),
        minVisibility = hours.mapNotNull { it.visibility }.minOrNull(),
    )
}

/** One period and its conditions at each level. */
data class BriefingPeriod(val period: ForecastPeriod, val levels: List<PeriodConditions>)

/** Snow at the top level in the next 24 hours. */
data class UpcomingSnow(
    val level: ForecastLevel,
    /** Start of the first hour with snow; already past when it is snowing now. */
    val start: Instant,
    /** End of the last hour with snow. */
    val end: Instant,
    /** Centimetres over those hours. */
    val amount: Double,
)

/** The snow on the ground at the top level this hour, as the model estimates it. */
data class SnowDepth(val level: ForecastLevel, /** Centimetres. */ val depth: Double)

/** A coming day whose ski time is clear and calm after fresh snow. */
data class GoodSkiingDay(
    /** The first good period of the day. */
    val period: ForecastPeriod,
    /** The morning and the afternoon are both good. */
    val isWholeDay: Boolean,
    /** Centimetres at the top level in the 24 hours before [period]. */
    val freshSnow: Double,
)

/**
 * The hourly forecast condensed into what a skier asks: is snow coming in
 * the next hours, and which coming days have fresh snow under a clear sky,
 * with the model's estimate of the snow on the ground at the top. Port of
 * `ForecastBriefing`; the sentences are the app's.
 */
class ForecastBriefing(forecast: DetailedForecast, val now: Instant, val zone: ZoneId) {
    /** Periods still to come, the current one first. */
    val periods: List<BriefingPeriod>

    /** `null` when less than half a centimetre is coming in the next 24 hours. */
    val upcomingSnow: UpcomingSnow?

    /** Earliest first. */
    val goodDays: List<GoodSkiingDay>

    /** `null` when the model gave no depth for the current hour. */
    val snowDepth: SnowDepth?

    init {
        val order = mutableListOf<ForecastPeriod>()
        // The current hour still counts.
        val hoursByLevel = forecast.levels.map { level ->
            level.hours.filter { it.end > now }.groupBy { hour ->
                ForecastPeriod.containing(hour.start, zone).also { if (it !in order) order += it }
            }
        }
        periods = order.sortedBy { it.start }.map { period ->
            BriefingPeriod(
                period,
                forecast.levels.zip(hoursByLevel).mapNotNull { (level, grouped) ->
                    grouped[period]?.let { PeriodConditions(level.level, it) }
                },
            )
        }
        val top = forecast.levels.firstOrNull()
        upcomingSnow = top?.let { upcomingSnow(it, now) }
        goodDays = top?.let { goodDays(periods, it) }.orEmpty()
        snowDepth = top?.let { level ->
            level.hours.lastOrNull { it.start <= now && now < it.end }?.snowDepth?.let { SnowDepth(level.level, it) }
        }
    }

    /**
     * The depth to the nearest 5 cm, or `null` under that: a model saying
     * "no snow" is not worth stating as a fact on a ski resort's screen.
     */
    val roundedSnowDepth: Int?
        get() {
            val depth = snowDepth?.depth ?: return null
            val rounded = DailyForecast.wholeNumber(depth / SNOW_DEPTH_STEP) * SNOW_DEPTH_STEP
            return rounded.takeIf { it >= SNOW_DEPTH_STEP }
        }

    companion object {
        /** Rain from which a period is spoilt, in millimetres. */
        const val RAIN_THRESHOLD = 1.0

        /** Visibility under which a period is foggy, in metres. */
        const val POOR_VISIBILITY_THRESHOLD = 1_000.0

        /** Snow in the previous 24 hours that makes a clear, calm ski period good. */
        const val FRESH_SNOW_THRESHOLD = 10.0

        /** Clear means at most this much cloud on average, in percent. */
        const val CLEAR_SKY_CLOUD_COVER = 30.0

        /** Calm means gusts below this, in km/h. */
        const val CALM_GUST_THRESHOLD = 40.0

        /** Less snow than this over a period or the next 24 hours, in centimetres, is no snow. */
        internal const val DRY_PERIOD_SNOWFALL = 0.5

        /** How far ahead "snow soon" looks, in hours. */
        const val UPCOMING_SNOW_HOURS = 24L

        /** An hour with less snow than this, in centimetres, is dry. */
        internal const val SNOWY_HOUR_SNOWFALL = 0.1

        /** A model's depth for a whole ski area is not precise to the centimetre. */
        const val SNOW_DEPTH_STEP = 5

        /** Every snowy hour at the top level from the current one to 24 hours ahead, dry hours between them included. */
        private fun upcomingSnow(top: LevelHourlyForecast, now: Instant): UpcomingSnow? {
            val horizon = now + Duration.ofHours(UPCOMING_SNOW_HOURS)
            val snowy = top.hours.filter { it.end > now && it.start < horizon && it.snowfall >= SNOWY_HOUR_SNOWFALL }
            val amount = snowy.sumOf { it.snowfall }
            if (snowy.isEmpty() || amount < DRY_PERIOD_SNOWFALL) return null
            return UpcomingSnow(top.level, snowy.first().start, snowy.last().end, amount)
        }

        /**
         * Ski periods at the top level, where the snow stays, that are dry,
         * clear, calm, out of the fog and below the freezing level, with fresh
         * snow in the 24 hours before. A good afternoon after a good morning
         * makes the whole day good.
         */
        private fun goodDays(periods: List<BriefingPeriod>, top: LevelHourlyForecast): List<GoodSkiingDay> {
            val days = mutableListOf<GoodSkiingDay>()
            for (briefing in periods) {
                val period = briefing.period
                val conditions = briefing.levels.firstOrNull { it.level == top.level } ?: continue
                val cloud = conditions.meanCloudCover ?: continue
                val gusts = conditions.maxGusts ?: continue
                if (!period.isSkiing ||
                    conditions.snowfall >= DRY_PERIOD_SNOWFALL ||
                    conditions.rain >= RAIN_THRESHOLD ||
                    cloud > CLEAR_SKY_CLOUD_COVER ||
                    gusts >= CALM_GUST_THRESHOLD ||
                    (conditions.minVisibility ?: Double.POSITIVE_INFINITY) < POOR_VISIBILITY_THRESHOLD
                ) {
                    continue
                }
                // A thaw at the summit spoils the fresh snow.
                val level = top.level
                val lowest = conditions.minFreezingLevel
                if (level is ForecastLevel.Summit && lowest != null && lowest > level.altitude) continue

                val dayBefore = period.start - Duration.ofHours(24)
                val fresh = top.hours.filter { it.start >= dayBefore && it.start < period.start }.sumOf { it.snowfall }
                if (fresh < FRESH_SNOW_THRESHOLD) continue

                val last = days.lastOrNull()
                if (last != null && last.period.day == period.day) {
                    days[days.lastIndex] = last.copy(isWholeDay = true)
                } else {
                    days += GoodSkiingDay(period, isWholeDay = false, freshSnow = fresh)
                }
            }
            return days
        }
    }
}
