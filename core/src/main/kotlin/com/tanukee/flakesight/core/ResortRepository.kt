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
     * The catalogue for the resort list: alphabetical, favourites first when
     * [favoritesFirst] (both groups alphabetical), mixed in otherwise. Unknown
     * favourite IDs are ignored. Port of `sortedResorts` for the name order;
     * distance and snow orders come with location and forecasts.
     */
    fun sortedResorts(favoriteIDs: Collection<String>, favoritesFirst: Boolean = true): List<Resort> {
        // Accent-aware, like localizedStandardCompare: Brévent before Col, Dôme after.
        val collator = Collator.getInstance(Locale.FRENCH)
        val sorted = resorts.sortedWith { a, b -> collator.compare(a.name, b.name) }
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
         * The catalogue bundled with the app. Anything shown to the user will start
         * from `available()` once withdrawn content is ported; this is for tests and
         * previews until then.
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
