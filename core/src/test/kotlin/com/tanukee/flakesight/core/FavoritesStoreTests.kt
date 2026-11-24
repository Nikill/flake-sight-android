package com.tanukee.flakesight.core

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of `FavoritesStoreTests.swift`. Keep the two files in step (see IOS_SYNC.md). */
class FavoritesStoreTests {
    @TempDir
    lateinit var directory: File

    private val clock = TestClock()

    /** Each favourite is added a second after the previous one, as a user would. */
    private fun makeStore(file: File = File(directory, "favorites.json")) =
        FavoritesStore(file) { clock.now.also { clock.advance(Duration.ofSeconds(1)) } }

    private val store by lazy { makeStore() }

    @Test
    fun firstFavoriteBecomesDefault() {
        store.toggleFavorite("les-saisies")
        store.toggleFavorite("la-plagne")
        assertTrue(store.isFavorite("les-saisies"))
        assertTrue(store.isFavorite("la-plagne"))
        assertEquals("les-saisies", store.defaultResortID())
    }

    @Test
    fun toggleTwiceRemovesFavorite() {
        store.toggleFavorite("les-saisies")
        store.toggleFavorite("les-saisies")
        assertFalse(store.isFavorite("les-saisies"))
        assertNull(store.defaultResortID())
    }

    @Test
    fun setDefaultIsExclusiveAndAddsFavorite() {
        store.toggleFavorite("les-saisies")
        store.setDefault("val-thorens")
        assertTrue(store.isFavorite("val-thorens"))
        assertEquals("val-thorens", store.defaultResortID())
        assertEquals(1, store.favorites.value.count { it.isDefault })
    }

    @Test
    fun removingDefaultPromotesOldestRemaining() {
        store.toggleFavorite("les-saisies")
        store.toggleFavorite("la-plagne")
        store.toggleFavorite("les-arcs")
        store.toggleFavorite("les-saisies")
        assertEquals("la-plagne", store.defaultResortID())
    }

    /** Android only: SwiftData persists on iOS, the JSON file does here. */
    @Test
    fun favoritesSurviveANewStore() {
        store.toggleFavorite("les-saisies")
        store.setDefault("la-plagne")
        val reopened = makeStore()
        assertEquals(listOf("les-saisies", "la-plagne"), reopened.favorites.value.map { it.resortID })
        assertEquals("la-plagne", reopened.defaultResortID())
    }

    /** Android only: a write that fails throws and leaves the favourites as they were. */
    @Test
    fun aFailedSaveChangesNothing() {
        // A non-empty folder where the file should be: moving the new file over it fails.
        val blocked = File(directory, "blocked").apply { mkdirs() }
        File(blocked, "inside").writeText("x")
        val store = makeStore(blocked)
        assertFailsWith<IOException> { store.toggleFavorite("les-saisies") }
        assertFalse(store.isFavorite("les-saisies"))
        assertTrue(store.favorites.value.isEmpty())
    }

    /** Android only: an unreadable file reads as no favourites rather than crashing the app. */
    @Test
    fun anUnreadableFileIsNoFavorites() {
        val file = File(directory, "favorites.json").apply { writeText("not json") }
        assertTrue(makeStore(file).favorites.value.isEmpty())
    }
}
