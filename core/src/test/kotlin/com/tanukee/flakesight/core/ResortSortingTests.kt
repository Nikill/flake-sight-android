package com.tanukee.flakesight.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port of `ResortSortingTests.swift` for the name and snow orders and
 * "Favourites on Top". The distance tests come with distance sorting. Keep
 * the two files in step (see IOS_SYNC.md).
 */
class ResortSortingTests {
    private fun resort(id: String, name: String) = Resort(
        id = id, name = name, country = "FR", region = "R", latitude = 45.0, longitude = 6.0,
        timeZoneIdentifier = "Europe/Paris", websiteURL = "https://example.com", summitAltitude = 2500, webcams = emptyList(),
    )

    /** Alphabetically: Alpe, Brévent, Col, Dôme. */
    private val repository = ResortRepository(
        listOf(resort("col", "Col"), resort("dome", "Dôme"), resort("alpe", "Alpe"), resort("brevent", "Brévent")),
    )

    private fun totals(vararg entries: Pair<String, Pair<Double, Double>>) =
        SnowTotals(entries.associate { (id, amounts) -> id to SnowTotals.Entry(recent = amounts.first, forecast = amounts.second) }, Instant.now())

    private fun ids(
        order: ResortSortOrder = ResortSortOrder.NAME,
        favorites: List<String> = emptyList(),
        first: Boolean = true,
        snow: SnowTotals? = null,
    ) = repository.sortedResorts(order, favorites, first, snow).map { it.id }

    @Test
    fun nameIsAlphabetical() {
        assertEquals(listOf("alpe", "brevent", "col", "dome"), ids())
    }

    @Test
    fun favouritesOnTopAreAlphabeticalToo() {
        assertEquals(listOf("col", "dome", "alpe", "brevent"), ids(favorites = listOf("dome", "col")))
    }

    /** The distance half of the Swift test waits for distance sorting. */
    @Test
    fun favouritesMixInWhenTheToggleIsOff() {
        assertEquals(listOf("alpe", "brevent", "col", "dome"), ids(favorites = listOf("dome", "col"), first = false))
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
            val sorted = catalogue.sortedResorts(order, listOf("chamonix", "nowhere"))
            assertEquals(catalogue.resorts.size, sorted.size)
            assertEquals(catalogue.resorts.size, sorted.map { it.id }.toSet().size)
            assertEquals("chamonix", sorted.first().id)
        }
    }

    /** iOS's `distance` is not an order here yet, so it reads as the name order rather than failing. */
    @Test
    fun storedRawValuesAreUnchanged() {
        assertEquals(ResortSortOrder.NAME, ResortSortOrder.fromRawValue("name"))
        assertEquals(ResortSortOrder.FORECAST_SNOW, ResortSortOrder.fromRawValue("forecastSnow"))
        assertEquals(ResortSortOrder.RECENT_SNOW, ResortSortOrder.fromRawValue("recentSnow"))
        assertEquals(ResortSortOrder.NAME, ResortSortOrder.fromRawValue("distance"))
    }
}
