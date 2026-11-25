package com.tanukee.flakesight.core

/**
 * Who makes each weather model the app compares, for the explanation of the
 * models table. In the order of [SnowfallRange.MODELS]. Port of
 * `WeatherModelInfo`: the provider's proper name is here, the translated
 * words (country, the ECMWF's full name, the grids and horizons) are the app's
 * strings, picked by [id].
 *
 * Grids and horizons follow Open-Meteo's documentation of each provider's API
 * (November 2026); check them again when a model changes.
 */
data class WeatherModelInfo(
    /** Open-Meteo's name, one of [SnowfallRange.MODELS]. */
    val id: String,
    /** "ICON", as in the models table. */
    val name: String,
    /** "Deutscher Wetterdienst (DWD)"; `null` when the name is translated (ECMWF's). */
    val provider: String?,
) {
    companion object {
        val ALL = listOf(
            WeatherModelInfo("meteofrance_seamless", "Météo-France", "Météo-France"),
            WeatherModelInfo("icon_seamless", "ICON", "Deutscher Wetterdienst (DWD)"),
            WeatherModelInfo("ecmwf_ifs025", "ECMWF", null),
            WeatherModelInfo("gfs_seamless", "GFS", "NOAA"),
        )
    }
}
