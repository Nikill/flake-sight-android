package com.tanukee.flakesight.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Case- and diacritic-insensitive containment, the equivalent of Foundation's
 * `localizedStandardContains` used by the iOS search.
 */
internal fun String.searchContains(query: String): Boolean =
    searchFolded().contains(query.searchFolded())

private val combiningMarks = Regex("\\p{Mn}+")

internal fun String.searchFolded(): String =
    combiningMarks.replace(Normalizer.normalize(this, Normalizer.Form.NFD), "")
        .lowercase(Locale.ROOT)

internal object Geo {
    private const val EARTH_RADIUS_METRES = 6_371_008.8

    /** Great-circle distance in metres between two coordinates. */
    fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METRES * asin(sqrt(a))
    }
}
