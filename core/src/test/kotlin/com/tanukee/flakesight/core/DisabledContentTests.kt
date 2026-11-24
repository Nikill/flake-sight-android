package com.tanukee.flakesight.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of `DisabledContentTests.swift`. The list is kept in a file on the
 * device instead of the App Group (`sharedStateStoresTheList`), and there is no
 * Watch payload yet (`watchPayloadCarriesTheList`, with Wear OS). Keep the two
 * files in step.
 */
class DisabledContentTests {
    private val repository = ResortRepository.bundled()
    private val file = Files.createTempFile("DisabledContentTests", ".json").toFile().apply { delete() }

    @Test
    fun decodesMissingKeysAsEmpty() {
        val content = DisabledContent.json.decodeFromString<DisabledContent>("""{"version":1}""")
        assertEquals(DisabledContent.NONE, content)
        assertTrue(content.isEmpty)
    }

    @Test
    fun removesDisabledResortsWebcamsAndProviders() {
        val resort = repository.resorts.first { it.webcams.size >= 2 }
        val webcam = resort.webcams.first()
        val otherResort = repository.resorts.first { it.id != resort.id }
        val provider = repository.resorts.flatMap { it.webcams }.first { it.provider != webcam.provider }.provider

        val available = repository.removing(
            DisabledContent(providers = setOf(provider.rawValue), resortIDs = setOf(otherResort.id), webcamIDs = setOf(webcam.id)),
        )

        assertNull(available.resort(otherResort.id))
        assertNull(available.webcam(webcam.id))
        assertTrue(available.resorts.flatMap { it.webcams }.none { it.provider == provider })
        assertEquals(repository.resorts.size - 1, available.resorts.size)
        // A resort whose webcams are all withdrawn stays, for its forecast.
        val emptied = repository.removing(DisabledContent(webcamIDs = resort.webcams.map { it.id }.toSet()))
        assertEquals(true, emptied.resort(resort.id)?.webcams?.isEmpty())
    }

    @Test
    fun emptyListKeepsTheCatalogue() {
        assertEquals(repository.resorts, repository.removing(DisabledContent.NONE).resorts)
    }

    /** In place of `sharedStateStoresTheList`: the list outlives the store that received it. */
    @Test
    fun storeKeepsTheListForTheNextLaunch() = runBlocking<Unit> {
        val body = """{"version":1,"disabledResorts":["tignes"]}"""
        val store = DisabledContentStore(file, StubHttpClient(mapOf(URL to HttpResponse(200, emptyMap(), body.encodeToByteArray()))), URL)
        assertEquals(DisabledContent.NONE, store.content.value)
        assertTrue(store.refresh())
        assertEquals(DisabledContent(resortIDs = setOf("tignes")), DisabledContentStore(file, StubHttpClient(emptyMap()), URL).content.value)
        file.delete()
    }

    @Test
    fun refreshStoresTheDownloadedList() = runBlocking<Unit> {
        val body = """{"version":1,"disabledProviders":["viewsurf"],"disabledWebcams":["tignes.toviere"]}"""
        val store = DisabledContentStore(file, StubHttpClient(mapOf(URL to HttpResponse(200, emptyMap(), body.encodeToByteArray()))), URL)

        assertTrue(store.refresh())
        assertEquals(DisabledContent(providers = setOf("viewsurf"), webcamIDs = setOf("tignes.toviere")), store.content.value)
        assertFalse(store.refresh())
        file.delete()
    }

    @Test
    fun failedRefreshKeepsTheStoredList() = runBlocking<Unit> {
        val stored = DisabledContent(resortIDs = setOf("tignes"))
        file.writeText(DisabledContent.json.encodeToString(stored))
        for (answer in listOf(HttpResponse(404, emptyMap(), "Not Found".encodeToByteArray()), HttpResponse(200, emptyMap(), "<html>".encodeToByteArray()))) {
            val store = DisabledContentStore(file, StubHttpClient(mapOf(URL to answer)), URL)
            assertFalse(store.refresh())
            assertEquals(stored, store.content.value)
        }
        file.delete()
    }

    private companion object {
        const val URL = "https://example.com/disabled.json"
    }
}
