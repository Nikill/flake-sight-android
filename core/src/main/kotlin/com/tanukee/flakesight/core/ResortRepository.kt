package com.tanukee.flakesight.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Collator
import java.util.Locale

/**
 * Read-only access to the resort and webcam catalogue. Port of `ResortRepository`
 * in SkiPatrolKit.
 *
 * `ResortCatalog.json` is copied from the iOS repository and never edited here
 * (see IOS_SYNC.md).
 */
class ResortRepository(val resorts: List<Resort>) {

    fun resort(withId: String): Resort? = resorts.firstOrNull { it.id == withId }

    fun webcam(withId: String): Pair<Resort, Webcam>? {
        for (resort in resorts) {
            resort.webcam(withId)?.let { return resort to it }
        }
        return null
    }

    /**
     * The whole catalogue in the resort list's [order], favourites first when
     * [favoritesFirst] (both groups in that order), mixed in otherwise. Unknown
     * favourite IDs are ignored. Port of `sortedResorts`.
     *
     * Resorts are alphabetical, then ordered by the key the sort reads: nearest
     * first with a [location], most snow first (to the whole centimetre, as
     * shown) with [snow]. Without that data, or for a resort missing from
     * [snow], the order stays alphabetical, the missing ones last.
     */
    fun sortedResorts(
        order: ResortSortOrder,
        favoriteIDs: Collection<String>,
        favoritesFirst: Boolean = true,
        location: Coordinate? = null,
        snow: SnowTotals? = null,
    ): List<Resort> {
        // Accent-aware, like localizedStandardCompare: Brévent before Col, Dôme after.
        val collator = Collator.getInstance(Locale.FRENCH)
        val alphabetical = resorts.sortedWith { a, b -> collator.compare(a.name, b.name) }
        val kind = order.snowKind
        // `sortedBy` is stable, so equal keys stay alphabetical; missing totals rank below zero.
        val sorted = when {
            order == ResortSortOrder.DISTANCE && location != null -> alphabetical.sortedBy { it.distanceFrom(location) }
            kind != null && snow != null -> alphabetical.sortedBy { resort ->
                snow.amount(kind, resort.id)?.let { -DailyForecast.wholeNumber(it).toDouble() } ?: Double.POSITIVE_INFINITY
            }
            else -> alphabetical
        }
        if (!favoritesFirst) return sorted
        val (favorites, others) = sorted.partition { it.id in favoriteIDs }
        return favorites + others
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Decodes a catalogue in the format of `ResortCatalog.json`. */
        fun fromJson(text: String): ResortRepository =
            ResortRepository(json.decodeFromString<ResortCatalog>(text).resorts)

        /**
         * The catalogue bundled with the app. Anything shown to the user starts from
         * it without the withdrawn content ([removing], the app's `availableRepository`).
         */
        fun bundled(): ResortRepository {
            val stream = ResortRepository::class.java.getResourceAsStream("/ResortCatalog.json")
                ?: error("ResortCatalog.json is missing from the core resources")
            return fromJson(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }
}

/** On-disk representation of the catalogue. */
@Serializable
internal data class ResortCatalog(
    val version: Int,
    val resorts: List<Resort>,
)
