package com.tanukee.flakesight.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port of `ResortSortingTests.swift`: the resort list's sort orders and its
 * "Favourites on Top" toggle. Keep the two files in step (see IOS_SYNC.md).
 */
class ResortSortingTests {
    private fun resort(id: String, name: String, latitude: Double = 45.0) = Resort(
        id = id, name = name, country = "FR", region = "R", latitude = latitude, longitude = 6.0,
        timeZoneIdentifier = "Europe/Paris", websiteURL = "https://example.com", summitAltitude = 2500, webcams = emptyList(),
    )

    /** Alphabetically: Alpe, Brévent, Col, Dôme. Nearest to [here]: Dôme, Brévent, Col, Alpe. */
    private val repository = ResortRepository(
        listOf(
            resort("col", "Col", latitude = 45.3),
            resort("dome", "Dôme", latitude = 45.0),
            resort("alpe", "Alpe", latitude = 45.4),
            resort("brevent", "Brévent", latitude = 45.1),
        ),
    )
    private val here = Coordinate(45.0, 6.0)

    private fun totals(vararg entries: Pair<String, Pair<Double, Double>>) =
        SnowTotals(entries.associate { (id, amounts) -> id to SnowTotals.Entry(recent = amounts.first, forecast = amounts.second) }, Instant.now())

    private fun ids(
        order: ResortSortOrder = ResortSortOrder.NAME,
        favorites: List<String> = emptyList(),
        first: Boolean = true,
        location: Coordinate? = null,
        snow: SnowTotals? = null,
    ) = repository.sortedResorts(order, favorites, first, location, snow).map { it.id }

    @Test
    fun nameIsAlphabetical() {
        assertEquals(listOf("alpe", "brevent", "col", "dome"), ids())
    }

    @Test
    fun favouritesOnTopAreAlphabeticalToo() {
        assertEquals(listOf("col", "dome", "alpe", "brevent"), ids(favorites = listOf("dome", "col")))
    }

    @Test
    fun favouritesMixInWhenTheToggleIsOff() {
        assertEquals(listOf("alpe", "brevent", "col", "dome"), ids(favorites = listOf("dome", "col"), first = false))
        assertEquals(
            listOf("dome", "brevent", "col", "alpe"),
            ids(ResortSortOrder.DISTANCE, favorites = listOf("alpe"), first = false, location = here),
        )
    }

    @Test
    fun distanceIsNearestFirstInsideEachGroup() {
        assertEquals(listOf("dome", "brevent", "col", "alpe"), ids(ResortSortOrder.DISTANCE, location = here))
        assertEquals(
            listOf("col", "alpe", "dome", "brevent"),
            ids(ResortSortOrder.DISTANCE, favorites = listOf("alpe", "col"), location = here),
        )
    }

    @Test
    fun distanceWithoutALocationIsAlphabetical() {
        assertEquals(listOf("alpe", "brevent", "col", "dome"), ids(ResortSortOrder.DISTANCE))
    }

    @Test
    fun snowSortsReadTheirOwnTotal() {
        val snow = totals(
            "alpe" to (2.0 to 30.0),
            "brevent" to (25.0 to 0.0),
            "col" to (10.0 to 12.0),
            "dome" to (0.0 to 5.0),
        )
        assertEquals(listOf("alpe", "col", "dome", "brevent"), ids(ResortSortOrder.FORECAST_SNOW, snow = snow))
        assertEquals(listOf("brevent", "col", "alpe", "dome"), ids(ResortSortOrder.RECENT_SNOW, snow = snow))
        assertEquals(listOf("alpe", "dome", "brevent", "col"), ids(ResortSortOrder.RECENT_SNOW, favorites = listOf("dome", "alpe"), snow = snow))
    }

    @Test
    fun snowTiesToTheCentimetreStayAlphabetical() {
        val snow = totals(
            "dome" to (0.0 to 8.2),
            "alpe" to (0.0 to 7.9),
            "col" to (0.0 to 0.3),
            "brevent" to (0.0 to 0.0),
        )
        assertEquals(listOf("alpe", "dome", "brevent", "col"), ids(ResortSortOrder.FORECAST_SNOW, snow = snow))
    }

    @Test
    fun resortsWithoutTotalsGoLast() {
        val snow = totals("dome" to (0.0 to 0.0), "col" to (0.0 to 4.0))
        assertEquals(listOf("col", "dome", "alpe", "brevent"), ids(ResortSortOrder.FORECAST_SNOW, snow = snow))
    }

    @Test
    fun snowWithoutTotalsIsAlphabetical() {
        assertEquals(listOf("alpe", "brevent", "col", "dome"), ids(ResortSortOrder.RECENT_SNOW))
    }

    @Test
    fun unknownFavouritesAreIgnoredAndEveryResortAppearsOnce() {
        val catalogue = ResortRepository.bundled()
        for (order in ResortSortOrder.entries) {
            val sorted = catalogue.sortedResorts(order, listOf("chamonix", "nowhere"), location = here)
            assertEquals(catalogue.resorts.size, sorted.size)
            assertEquals(catalogue.resorts.size, sorted.map { it.id }.toSet().size)
            assertEquals("chamonix", sorted.first().id)
        }
    }

    /** Plus the snow orders, and an unknown value reading as the name order rather than failing. */
    @Test
    fun storedRawValuesAreUnchanged() {
        assertEquals(ResortSortOrder.NAME, ResortSortOrder.fromRawValue("name"))
        assertEquals(ResortSortOrder.DISTANCE, ResortSortOrder.fromRawValue("distance"))
        assertEquals(ResortSortOrder.FORECAST_SNOW, ResortSortOrder.fromRawValue("forecastSnow"))
        assertEquals(ResortSortOrder.RECENT_SNOW, ResortSortOrder.fromRawValue("recentSnow"))
        assertEquals(ResortSortOrder.NAME, ResortSortOrder.fromRawValue("altitude"))
    }
}
