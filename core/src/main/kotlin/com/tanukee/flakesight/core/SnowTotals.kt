package com.tanukee.flakesight.core

import java.time.Instant

/**
 * How much snow fell lately and how much is coming, per resort, at the level
 * the resort list ranks by ([Resort.snowRankingLevel]). Drives the snow sorts
 * of the resort list. Port of `SnowTotals` in SkiPatrolKit; sentences are the app's.
 */
data class SnowTotals(val entries: Map<String, Entry>, val fetchedAt: Instant) {
    /** Which of the two totals a sort reads. [rawValue] matches iOS. */
    enum class Kind(val rawValue: String, val dayCount: Int) {
        /** The days before today. */
        RECENT("recent", ForecastService.SNOW_RANKING_PAST_DAYS),

        /** Today and the following days. */
        FORECAST("forecast", ForecastService.DAY_COUNT),
    }

    /** One resort's totals, in centimetres. */
    data class Entry(val recent: Double, val forecast: Double) {
        operator fun get(kind: Kind): Double = when (kind) {
            Kind.RECENT -> recent
            Kind.FORECAST -> forecast
        }
    }

    /** Centimetres for the resort, or `null` when it was not fetched. */
    fun amount(kind: Kind, resortID: String): Double? = entries[resortID]?.get(kind)

    /** Whole centimetres for one or more, otherwise `null` so a row never shows "0 cm". */
    fun centimetres(kind: Kind, resortID: String): Int? = amount(kind, resortID)?.let(DailyForecast::centimetres)

    /** The step of the map's scale for the resort, or `null` when it was not fetched. */
    fun band(kind: Kind, resortID: String): SnowBand? = amount(kind, resortID)?.let(SnowBand::of)
}

/**
 * The step of the map's snow scale a total falls in. One scale serves both the
 * recent and the forecast totals, so the legend stays put when the map
 * switches between them. Port of `SnowBand`; the colours are the app's.
 */
enum class SnowBand {
    /** Under a centimetre: what the rows and pins show as no snow. */
    NONE,
    LIGHT,
    MODERATE,
    HEAVY,
    DEEP;

    /** The first whole centimetre of the step. */
    val lowerBound: Int get() = LOWER_BOUNDS[ordinal]

    /** The last whole centimetre of the step, `null` for the top one. */
    val upperBound: Int? get() = LOWER_BOUNDS.getOrNull(ordinal + 1)?.minus(1)

    companion object {
        private val LOWER_BOUNDS = listOf(0, DailyForecast.SNOW_THRESHOLD, 10, 30, 50)

        /** The step for a total, judged on the whole centimetre shown, so a pin reading "10 cm" is never coloured as "1 to 9 cm". */
        fun of(centimetres: Double): SnowBand {
            val shown = DailyForecast.wholeNumber(kotlin.math.max(centimetres, 0.0))
            return entries[LOWER_BOUNDS.indexOfLast { shown >= it }.coerceAtLeast(0)]
        }
    }
}

/**
 * Where the snow sorts measure: the summit when catalogued, else the village,
 * else the resort centre. Villages often get rain when the top gets snow.
 */
val Resort.snowRankingLevel: ForecastLevel get() = forecastLevels[0]

/**
 * How the resort list is ordered, remembered between launches under
 * [rawValue], the iOS value. Port of `ResortSortOrder`.
 */
enum class ResortSortOrder(val rawValue: String) {
    /** Alphabetically. */
    NAME("name"),

    /** Nearest first, once the user is located. */
    DISTANCE("distance"),

    /** Most snow expected over the forecast days first. */
    FORECAST_SNOW("forecastSnow"),

    /** Most snow fallen over the last days first. */
    RECENT_SNOW("recentSnow");

    /** The snow total this order ranks by, or `null` for the others. */
    val snowKind: SnowTotals.Kind?
        get() = when (this) {
            NAME, DISTANCE -> null
            FORECAST_SNOW -> SnowTotals.Kind.FORECAST
            RECENT_SNOW -> SnowTotals.Kind.RECENT
        }

    companion object {
        /** The stored order, or [NAME] for one this version does not know. */
        fun fromRawValue(raw: String?): ResortSortOrder = entries.firstOrNull { it.rawValue == raw } ?: NAME
    }
}
