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
}

/**
 * Where the snow sorts measure: the summit when catalogued, else the village,
 * else the resort centre. Villages often get rain when the top gets snow.
 */
val Resort.snowRankingLevel: ForecastLevel get() = forecastLevels[0]

/**
 * How the resort list is ordered, remembered between launches under
 * [rawValue], the iOS value. Port of `ResortSortOrder`; distance comes with location.
 */
enum class ResortSortOrder(val rawValue: String) {
    /** Alphabetically. */
    NAME("name"),

    /** Most snow expected over the forecast days first. */
    FORECAST_SNOW("forecastSnow"),

    /** Most snow fallen over the last days first. */
    RECENT_SNOW("recentSnow");

    /** The snow total this order ranks by, or `null` for the others. */
    val snowKind: SnowTotals.Kind?
        get() = when (this) {
            NAME -> null
            FORECAST_SNOW -> SnowTotals.Kind.FORECAST
            RECENT_SNOW -> SnowTotals.Kind.RECENT
        }

    companion object {
        /** The stored order, or [NAME] for one this version does not know (iOS's `distance`). */
        fun fromRawValue(raw: String?): ResortSortOrder = entries.firstOrNull { it.rawValue == raw } ?: NAME
    }
}
