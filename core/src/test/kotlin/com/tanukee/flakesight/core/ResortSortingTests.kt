package com.tanukee.flakesight.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port of `ResortSortingTests.swift` for the name order and "Favourites on
 * Top". The distance and snow tests come with those orders. Keep the two
 * files in step (see IOS_SYNC.md).
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

    private fun ids(favorites: List<String> = emptyList(), first: Boolean = true) =
        repository.sortedResorts(favorites, first).map { it.id }

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

    /** For the name order only, until the other orders are ported. */
    @Test
    fun unknownFavouritesAreIgnoredAndEveryResortAppearsOnce() {
        val catalogue = ResortRepository.bundled()
        val sorted = catalogue.sortedResorts(listOf("chamonix", "nowhere"))
        assertEquals(catalogue.resorts.size, sorted.size)
        assertEquals(catalogue.resorts.size, sorted.map { it.id }.toSet().size)
        assertEquals("chamonix", sorted.first().id)
    }
}
