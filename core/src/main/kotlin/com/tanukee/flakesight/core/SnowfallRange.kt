package com.tanukee.flakesight.core

import java.time.Instant
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/** How far the weather models agree on a day's snowfall. Port of `ForecastConfidence`. */
enum class ForecastConfidence {
    /** The models give close amounts. */
    HIGH,

    /** The models disagree: the storm may be bigger, smaller or miss. */
    LOW,
}

/**
 * The plausible snowfall of a day at one level: from the smallest to the
 * largest amount among several weather models and the headline figure, so the
 * headline always sits inside it. A wide range means the models disagree,
 * which is what the confidence says in a word. Port of `SnowfallRange`.
 */
data class SnowfallRange(
    /** Centimetres. */
    val low: Double,
    /** Centimetres. */
    val high: Double,
    val confidence: ForecastConfidence,
) {
    /** Whole centimetres at each end, for "5-20 cm", or one figure when both round alike. */
    val lowCentimetres: Int get() = DailyForecast.wholeNumber(low)
    val highCentimetres: Int get() = DailyForecast.wholeNumber(high)

    companion object {
        /**
         * The models compared, by Open-Meteo name: Météo-France (AROME then
         * ARPEGE, the finest grid over the Alps, about four days), DWD ICON,
         * ECMWF IFS and NOAA GFS.
         */
        val MODELS = listOf("meteofrance_seamless", "icon_seamless", "ecmwf_ifs025", "gfs_seamless")

        /** The models' names for people, in the order of [MODELS]; proper names, not translated. */
        val MODEL_NAMES = listOf("Météo-France", "ICON", "ECMWF", "GFS")

        /** Fewer models than this with an amount for the day is no comparison. */
        internal const val MINIMUM_MODEL_COUNT = 2

        /** A spread up to this, in centimetres, is close agreement whatever the amount: 0 to 4 cm is the same day on skis. */
        const val CONFIDENT_SPREAD = 5.0

        /** A spread up to this share of the high end is close agreement too: 20 to 30 cm is the same storm, 5 to 20 cm is not. */
        const val CONFIDENT_RELATIVE_SPREAD = 0.5

        /**
         * The range around [expected] from the models' amounts for the same day,
         * `null` when fewer than two models have one (Météo-France stops after
         * about four days) or when no one expects a centimetre.
         */
        fun of(expected: Double, modelAmounts: List<Double?>): SnowfallRange? {
            val amounts = modelAmounts.filterNotNull()
            if (amounts.size < MINIMUM_MODEL_COUNT) return null
            val all = amounts + expected
            val low = all.min()
            val high = all.max()
            if (DailyForecast.wholeNumber(high) < DailyForecast.SNOW_THRESHOLD) return null
            val isClose = high - low <= max(CONFIDENT_SPREAD, CONFIDENT_RELATIVE_SPREAD * high)
            return SnowfallRange(low, high, if (isClose) ForecastConfidence.HIGH else ForecastConfidence.LOW)
        }
    }
}

/** Each model's daily snowfall at each level of a resort, for the ranges. Port of `ModelSnowfall`. */
data class ModelSnowfall(val resortID: String, val levels: List<Level>, val fetchedAt: Instant) {
    /** One level's days, each with one amount per model of [SnowfallRange.MODELS], `null` where a model has none. */
    data class Level(val level: ForecastLevel, val days: Map<LocalDate, List<Double?>>)
}

/** The same forecast with each day's range from the models' amounts, matched by level and date. */
fun ResortForecast.withRanges(models: ModelSnowfall): ResortForecast = copy(
    levels = levels.map { level ->
        val amounts = models.levels.firstOrNull { it.level == level.level }?.days.orEmpty()
        level.copy(days = level.days.map { day -> day.copy(snowfallRange = amounts[day.date]?.let { SnowfallRange.of(day.snowfall, it) }) })
    },
)

/**
 * The height shared by a row of snow gauges: the deepest amount in the row,
 * range included, fills a gauge, so days compare at a glance. A minimum keeps
 * a 2 cm week from looking like a storm. Port of `SnowGaugeScale`.
 */
class SnowGaugeScale(days: List<DailyForecast>) {
    /** Centimetres a full gauge stands for. */
    val ceiling: Double = max(MINIMUM_CEILING, days.maxOfOrNull { max(it.snowfall, it.snowfallRange?.high ?: 0.0) } ?: 0.0)

    /** The share of a full gauge [centimetres] fills, from 0 to 1. */
    fun fraction(centimetres: Double): Double = min(1.0, max(0.0, centimetres / ceiling))

    companion object {
        /** The ceiling never goes under this, in centimetres. */
        const val MINIMUM_CEILING = 10.0
    }
}
