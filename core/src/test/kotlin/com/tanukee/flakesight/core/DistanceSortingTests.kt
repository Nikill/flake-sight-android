package com.tanukee.flakesight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port of `DistanceSortingTests.swift`: sorting the resort list by distance from
 * the user. The Swift tests read `resortsByFavorites`, which only CarPlay and
 * the Watch use; these read `sortedResorts`, the list's equivalent, until those
 * surfaces are ported. Keep the two files in step.
 */
class DistanceSortingTests {
    private val repository = ResortRepository.bundled()
    private val grenoble = Coordinate(45.1885, 5.7245)

    private fun nearestFirst(favorites: List<String> = emptyList(), location: Coordinate? = grenoble) =
        repository.sortedResorts(ResortSortOrder.DISTANCE, favorites, favoritesFirst = true, location = location)

    @Test
    fun distanceToResortCentreIsZero() {
        val chamonix = repository.resort("chamonix")!!
        assertTrue(chamonix.distanceFrom(Coordinate(chamonix.latitude, chamonix.longitude)) < 1)
    }

    @Test
    fun distanceIsInMetres() {
        // Chamrousse is about 18 km from Grenoble as the crow flies.
        assertTrue(repository.resort("chamrousse")!!.distanceFrom(grenoble) in 10_000.0..30_000.0)
    }

    @Test
    fun othersAreSortedNearestFirst() {
        val others = nearestFirst()
        val distances = others.map { it.distanceFrom(grenoble) }
        assertEquals(distances.sorted(), distances)
        val ids = others.map { it.id }
        assertTrue(ids.indexOf("chamrousse") < ids.indexOf("chamonix"))
        assertTrue(ids.indexOf("les-7-laux") < ids.indexOf("chamonix"))
    }

    @Test
    fun favoritesStayFirstAndAreSortedNearestFirst() {
        val sorted = nearestFirst(listOf("chamonix", "chamrousse", "isola-2000"))
        assertEquals(listOf("chamrousse", "chamonix", "isola-2000"), sorted.take(3).map { it.id })
        assertEquals(repository.resorts.size, sorted.size)
    }

    @Test
    fun withoutLocationOrderIsUnchanged() {
        val favorites = listOf("isola-2000", "chamonix")
        assertEquals(
            repository.sortedResorts(ResortSortOrder.NAME, favorites).map { it.id },
            nearestFirst(favorites, location = null).map { it.id },
        )
    }

    @Test
    fun nearestStartsWithTheResortAtTheLocation() {
        val chamonix = repository.resort("chamonix")!!
        assertEquals("chamonix", repository.resorts.nearest(15, Coordinate(chamonix.latitude, chamonix.longitude)).first().id)
    }

    @Test
    fun nearestMatchesDistanceSorting() {
        assertEquals(nearestFirst().take(15).map { it.id }, repository.resorts.nearest(15, grenoble).map { it.id })
    }

    @Test
    fun nearestReturnsAtMostCount() {
        assertEquals(minOf(15, repository.resorts.size), repository.resorts.nearest(15, grenoble).size)
        assertEquals(3, repository.resorts.take(3).nearest(15, grenoble).size)
        assertTrue(emptyList<Resort>().nearest(15, grenoble).isEmpty())
    }
}
