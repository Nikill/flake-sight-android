package com.tanukee.flakesight.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.floor

/**
 * The handful of sky conditions the forecast strip draws. Port of
 * `WeatherCondition` in SkiPatrolKit: Open-Meteo's WMO 4677 codes folded so a
 * column reads at a glance.
 */
enum class WeatherCondition {
    CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, THUNDERSTORM;

    companion object {
        /** Codes outside the table read as [CLOUDY], the most neutral pictogram. */
        fun fromWmoCode(code: Int): WeatherCondition = when (code) {
            0 -> CLEAR
            1, 2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..57, in 61..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> THUNDERSTORM
            else -> CLOUDY
        }
    }
}

/**
 * Where in the resort a forecast applies. Summit and village come from the
 * catalogue altitudes; a resort without either gets one row at its centre.
 */
sealed interface ForecastLevel {
    /** Metres, or `null` at the resort centre. */
    val altitude: Int?

    data class Summit(override val altitude: Int) : ForecastLevel
    data class Village(override val altitude: Int) : ForecastLevel
    data object ResortCentre : ForecastLevel {
        override val altitude: Int? get() = null
    }
}

/** Summit then village when catalogued, otherwise the resort centre. */
val Resort.forecastLevels: List<ForecastLevel>
    get() = listOfNotNull(
        summitAltitude?.let { ForecastLevel.Summit(it) },
        villageAltitude?.let { ForecastLevel.Village(it) },
    ).ifEmpty { listOf(ForecastLevel.ResortCentre) }

/** One day of forecast at one level. Port of `DailyForecast`; wording is the app's. */
data class DailyForecast(
    /** The day in the resort's time zone. */
    val date: LocalDate,
    val condition: WeatherCondition,
    /** Daytime maximum in °C; `null` when the model gave none. */
    val maxTemperature: Double?,
    /** Snowfall over the day in centimetres. */
    val snowfall: Double,
    /** How far other weather models agree on [snowfall]; `null` when they could not be fetched or no snow is in play. */
    val snowfallRange: SnowfallRange? = null,
) {
    /** A measurable fall, or a snow sky even without one. */
    val isSnowExpected: Boolean
        get() = wholeNumber(snowfall) >= SNOW_THRESHOLD || condition == WeatherCondition.SNOW

    /** Whole centimetres when at least one is expected, else `null`, so the strip never shows "0 cm". */
    val snowfallCentimetres: Int?
        get() = centimetres(snowfall)

    /** Whole degrees, `-0.4` reading 0, or `null` when unknown. */
    val maxTemperatureDegrees: Int?
        get() = maxTemperature?.let(::wholeNumber)

    /** "Fri" or "ven." */
    fun weekdayLabel(locale: Locale = Locale.getDefault()): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

    /** "Friday" or "vendredi", for sentences read aloud. */
    fun weekdayName(locale: Locale = Locale.getDefault()): String = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)

    fun isToday(now: Instant, zone: ZoneId): Boolean = date == now.atZone(zone).toLocalDate()

    companion object {
        /** Snowfall that counts as "snow expected", once rounded to whole centimetres. */
        const val SNOW_THRESHOLD = 1

        /** Whole centimetres for one or more, else `null`. */
        fun centimetres(snowfall: Double): Int? = wholeNumber(snowfall).takeIf { it >= SNOW_THRESHOLD }

        /** Rounded half away from zero, as Swift's `rounded()`; `Math.round` would make -4.5 read -4. */
        internal fun wholeNumber(value: Double): Int =
            if (value < 0) -floor(-value + 0.5).toInt() else floor(value + 0.5).toInt()
    }
}

/** The forecast days at one level, today first, and the snow fallen over the days before. */
data class LevelForecast(
    val level: ForecastLevel,
    val days: List<DailyForecast>,
    /** Centimetres over the [ForecastService.SNOW_RANKING_PAST_DAYS] days before today, `null` when not covered. */
    val recentSnowfall: Double? = null,
) {
    /** Centimetres over all the days. */
    val snowTotal: Double get() = days.sumOf { it.snowfall }
}

/**
 * A resort's forecast for the next few days: summit then village, or a
 * single row at the resort centre. Port of `ResortForecast`.
 */
data class ResortForecast(val resortID: String, val levels: List<LevelForecast>, val fetchedAt: Instant) {
    /** The calendar shared by every level. */
    val days: List<DailyForecast> get() = levels.firstOrNull()?.days.orEmpty()

    /** Whether any level expects snow on any day. */
    val isSnowExpected: Boolean get() = levels.any { level -> level.days.any { it.isSnowExpected } }

    /**
     * The snow fallen at each level over the days before today, in whole
     * centimetres (`null` for under one), for "In the last 7 days: 24 cm at
     * the summit, none in the village." `null` when a level lacks its total.
     */
    fun recentSnow(): List<Pair<ForecastLevel, Int?>>? {
        if (levels.isEmpty() || levels.any { it.recentSnowfall == null }) return null
        return levels.map { it.level to DailyForecast.centimetres(it.recentSnowfall!!) }
    }
}
