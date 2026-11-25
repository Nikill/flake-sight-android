package com.tanukee.flakesight.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The webcam put at the top of a resort's screen: stored per resort, ordering
 * every surface, ignored once it no longer matches a webcam. Port of
 * `FeaturedWebcamTests.swift`: the choices live in a file of their own beside
 * the favourites; `theWatchReceivesTheChoices` comes with Wear OS and
 * `theAppGroupKeepsTheChoices` becomes a file round trip. Keep the two files in step.
 */
class FeaturedWebcamTests {
    private val directory = Files.createTempDirectory("FeaturedWebcamTests").toFile()
    private val store = FeaturedWebcamStore(directory.resolve("featured-webcams.json"))

    @Test
    fun oneChoicePerResortThatCanChangeAndBeCleared() {
        assertNull(store.featuredWebcamID("les-saisies"))
        store.feature("les-saisies.bisanne", "les-saisies")
        store.feature("les-saisies.lac", "les-saisies")
        store.feature("tignes.lac", "tignes")
        assertEquals("les-saisies.lac", store.featuredWebcamID("les-saisies"))
        assertEquals(mapOf("les-saisies" to "les-saisies.lac", "tignes" to "tignes.lac"), store.all.value)
        store.clear("les-saisies")
        assertNull(store.featuredWebcamID("les-saisies"))
        store.clear("unknown")
        assertEquals(mapOf("tignes" to "tignes.lac"), store.all.value)
    }

    @Test
    fun favouritesAndFeaturedWebcamsShareTheStore() {
        val favorites = FavoritesStore(directory.resolve("favorites.json"))
        favorites.toggleFavorite("tignes")
        store.feature("la-plagne.belle-plagne", "la-plagne")
        assertEquals(listOf("tignes"), FavoritesStore(directory.resolve("favorites.json")).favorites.value.map { it.resortID })
        assertEquals("la-plagne.belle-plagne", store.featuredWebcamID("la-plagne"))
    }

    @Test
    fun theFeaturedWebcamComesFirstAndTheOthersKeepTheirOrder() {
        val webcams = ResortRepository.bundled().resorts.first { it.webcams.size >= 3 }.webcams
        val last = webcams.last()
        assertEquals(listOf(last.id) + webcams.dropLast(1).map { it.id }, webcams.featuring(last.id).map { it.id })
        assertEquals(webcams, webcams.featuring(webcams[0].id))
        assertEquals(webcams, webcams.featuring(null))
        // A webcam removed from the catalogue or withdrawn is not in the list.
        assertEquals(webcams, webcams.featuring("removed.webcam"))
        assertTrue(emptyList<Webcam>().featuring(last.id).isEmpty())
    }

    @Test
    fun theWidgetFollowsTheDefaultResortsFeaturedWebcam() {
        val repository = ResortRepository.bundled()
        val resort = repository.resorts.first { it.webcams.size >= 2 }
        val featured = resort.webcams.last()
        val unconfigured = WidgetWebcamResolver.resolve(null, resort.id, repository, mapOf(resort.id to featured.id))
        assertEquals(featured.id, unconfigured?.second?.id)
        // A webcam chosen in the widget's settings still wins.
        val configured = resort.webcams.first()
        assertEquals(configured.id, WidgetWebcamResolver.resolve(configured.id, resort.id, repository, mapOf(resort.id to featured.id))?.second?.id)
        // A featured webcam that no longer exists falls back to the first.
        assertEquals(configured.id, WidgetWebcamResolver.resolve(null, resort.id, repository, mapOf(resort.id to "removed.webcam"))?.second?.id)
    }

    /** In place of `theAppGroupKeepsTheChoices`: the choices outlive the store that saved them. */
    @Test
    fun theFileKeepsTheChoices() {
        store.feature("tignes.lac", "tignes")
        assertEquals(mapOf("tignes" to "tignes.lac"), FeaturedWebcamStore(directory.resolve("featured-webcams.json")).all.value)
    }
}
