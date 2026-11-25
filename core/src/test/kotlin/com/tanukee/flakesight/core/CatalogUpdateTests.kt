package com.tanukee.flakesight.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Resorts published between two releases (decision D20). Port of
 * `CatalogUpdateTests.swift`: the version is the iOS one the bundled catalogue
 * was copied from; the update is kept in a file instead of the App Group
 * (`decodesTheFileAndStoresIt`); `travelsToTheWatch` comes with Wear OS. Keep
 * the two files in step.
 */
class CatalogUpdateTests {
    private val repository = ResortRepository.bundled()
    private val file = Files.createTempFile("CatalogUpdateTests", ".json").toFile().apply { delete() }

    private fun webcam(id: String, resortID: String, provider: WebcamProviderType = WebcamProviderType.SKAPING) = Webcam(
        id = "$resortID.$id", resortID = resortID, name = id.replaceFirstChar { it.uppercase() }, provider = provider,
        sourcePageURL = "https://www.skaping.com/$resortID/$id",
        attribution = WebcamAttribution(providerName = "Skaping"),
        capabilities = setOf(WebcamCapability.STATIC_SNAPSHOT, WebcamCapability.PERIODICALLY_UPDATED_SNAPSHOT),
    )

    /** A new resort in the Vosges, without massif, with one webcam. */
    private fun newResort(id: String = "le-test", region: String = "Vosges", latitude: Double = 48.0) = Resort(
        id = id, name = "Le Test", country = "FR", region = region, latitude = latitude, longitude = 6.9,
        timeZoneIdentifier = "Europe/Paris", websiteURL = "https://example.com", webcamsPageURL = null,
        webcams = listOf(webcam("front-de-neige", id)),
    )

    @Test
    fun appliesOnlyBeforeTheVersionThatBundlesIt() {
        val update = CatalogUpdate(appliesBefore = "1.5", resorts = listOf(newResort()))
        assertTrue(update.applies("1.4"))
        assertTrue(update.applies("1.4.2"))
        assertFalse(update.applies("1.5"))
        assertFalse(update.applies("1.10"))
        assertFalse(CatalogUpdate(schema = 2, appliesBefore = "9", resorts = listOf(newResort())).applies("1.4"))
        assertFalse(CatalogUpdate.NONE.applies("1.4"))
    }

    @Test
    fun addsANewResortAndIgnoresItOnceReleased() {
        val update = CatalogUpdate(appliesBefore = "1.5", resorts = listOf(newResort()))
        assertNotNull(repository.applying(update, "1.4").resort("le-test"))
        assertEquals(repository.resorts.size + 1, repository.applying(update, "1.4").resorts.size)
        assertNull(repository.applying(update, "1.5").resort("le-test"))
    }

    @Test
    fun replacesACataloguedResortThatKeepsItsWebcams() {
        val angles = repository.resorts.first { it.webcams.isNotEmpty() && it.webcams.size < 4 }
        val added = webcam("nouvelle-vue", angles.id)
        val update = CatalogUpdate(appliesBefore = "1.5", resorts = listOf(angles.copy(webcams = angles.webcams + added)))
        val updated = repository.applying(update, "1.4")
        assertEquals(added, updated.resort(angles.id)?.webcams?.last())
        assertEquals(repository.resorts.map { it.id }, updated.resorts.map { it.id }, "The order stays, Les Saisies first")
    }

    /** Widgets are configured by webcam ID: dropping a camera is the withdrawal list's job. */
    @Test
    fun ignoresAReplacementThatDropsAWebcam() {
        val saisies = assertNotNull(repository.resort("les-saisies"))
        val update = CatalogUpdate(appliesBefore = "1.5", resorts = listOf(saisies.copy(webcams = saisies.webcams.drop(1))))
        assertEquals(saisies, repository.applying(update, "1.4").resort("les-saisies"))
    }

    @Test
    fun ignoresAResortThatBreaksTheRules() {
        val update = CatalogUpdate(
            appliesBefore = "1.5",
            resorts = listOf(
                newResort(id = "en-mer", latitude = 43.0),
                newResort(id = "mal-ecrit", region = "Puy de Dôme"),
                newResort(id = "le-bon"),
            ),
        )
        val updated = repository.applying(update, "1.4")
        assertNull(updated.resort("en-mer"))
        assertNull(updated.resort("mal-ecrit"))
        assertNotNull(updated.resort("le-bon"))
    }

    /** A camera on a platform this version cannot read is left out, and the rest of the resort still arrives. */
    @Test
    fun leavesOutWebcamsOnAnUnknownProvider() {
        val base = newResort()
        val resort = base.copy(webcams = base.webcams + webcam("hologramme", base.id, WebcamProviderType.UNKNOWN))
        val updated = repository.applying(CatalogUpdate(appliesBefore = "1.5", resorts = listOf(resort)), "1.4")
        assertEquals(listOf("le-test.front-de-neige"), updated.resort(base.id)?.webcams?.map { it.id })
    }

    /** In place of the App Group: the update is decoded, stored and outlives the store that received it. */
    @Test
    fun decodesTheFileAndStoresIt() = runBlocking<Unit> {
        val update = CatalogUpdate.json.decodeFromString<CatalogUpdate>("""{"schema":1,"appliesBefore":"1.5","resorts":[]}""")
        assertEquals(CatalogUpdate(appliesBefore = "1.5", resorts = emptyList()), update)

        val stored = CatalogUpdate(appliesBefore = "1.5", resorts = listOf(newResort()))
        val body = CatalogUpdate.json.encodeToString(stored).encodeToByteArray()
        val store = CatalogUpdateStore(file, StubHttpClient(mapOf(URL to HttpResponse(200, emptyMap(), body))), URL)
        assertEquals(CatalogUpdate.NONE, store.update.value)
        assertTrue(store.refresh())
        assertFalse(store.refresh(), "Unchanged")
        assertEquals(stored, CatalogUpdateStore(file, StubHttpClient(emptyMap()), URL).update.value)
        file.delete()
    }

    @Test
    fun failedRefreshKeepsTheStoredUpdate() = runBlocking<Unit> {
        val stored = CatalogUpdate(appliesBefore = "1.5", resorts = listOf(newResort()))
        file.writeText(CatalogUpdate.json.encodeToString(stored))
        for (answer in listOf(HttpResponse(404, emptyMap(), "Not Found".encodeToByteArray()), HttpResponse(200, emptyMap(), "<html>".encodeToByteArray()))) {
            val store = CatalogUpdateStore(file, StubHttpClient(mapOf(URL to answer)), URL)
            assertFalse(store.refresh())
            assertEquals(stored, store.update.value)
        }
        file.delete()
    }

    /** The update arrives first, then the withdrawal list takes out what it names, new resorts included. */
    @Test
    fun availableAppliesTheUpdateThenTheWithdrawals() {
        val update = CatalogUpdate(appliesBefore = "1.5", resorts = listOf(newResort(), newResort(id = "le-retire")))
        val available = repository.available(update, DisabledContent(resortIDs = setOf("le-retire")), "1.4")
        assertNotNull(available.resort("le-test"))
        assertNull(available.resort("le-retire"))
    }

    private companion object {
        const val URL = "https://example.com/catalog-updates.json"
    }
}
