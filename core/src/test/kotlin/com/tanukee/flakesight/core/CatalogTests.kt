package com.tanukee.flakesight.core

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of `CatalogTests.swift`. Keep the two files in step (see IOS_SYNC.md). */
class CatalogTests {

    private val repository = ResortRepository.bundled()
    private val resorts = repository.resorts

    @Test
    fun bundledCatalogContainsLesSaisies() {
        val resort = assertNotNull(repository.resort("les-saisies"))
        assertEquals("Les Saisies", resort.name)
        assertEquals("FR", resort.country)
        assertEquals(10, resort.webcams.size)
        assertEquals("Europe/Paris", resort.timeZone.id)
    }

    @Test
    fun bundledCatalogContainsLes7Laux() {
        val resort = assertNotNull(repository.resort("les-7-laux"))
        assertEquals("Les 7 Laux", resort.name)
        assertEquals(4, resort.webcams.size)
        assertTrue(resort.webcams.all { it.provider == WebcamProviderType.SKAPING && it.providerIdentifier != null })
    }

    @ParameterizedTest
    @CsvSource(
        "sept laux, les-7-laux", "7 LAUX, les-7-laux", "pipay, les-7-laux",
        "bisanne, les-saisies", "boetet, les-saisies", "saisies, les-saisies",
        "aiguille du midi, chamonix", "val d'isere, val-d-isere", "2 alpes, les-deux-alpes",
        "merlette, orcieres", "vaujany, alpe-d-huez", "combloux, megeve", "doucy, valmorel", "hohneck, la-bresse-hohneck",
    )
    fun searchMatchesNamesRegionsAndWebcams(query: String, expectedId: String) {
        val matches = resorts.filter { it.matches(query) }
        assertEquals(listOf(expectedId), matches.map { it.id })
    }

    @Test
    fun emptySearchMatchesEverything() {
        assertTrue(resorts.all { it.matches("  ") })
    }

    /**
     * The rules a resort must follow wherever it comes from (coordinates in the
     * French mountains, a known département, the massif, altitudes, season, car
     * park, webcam IDs and the webcam cap) live in [CatalogRules], which also
     * vets a downloaded [CatalogUpdate].
     */
    @Test
    fun everyResortFollowsTheCatalogRules() {
        for (resort in resorts) {
            val problems = CatalogRules.problems(resort)
            assertTrue(problems.isEmpty(), problems.joinToString("; "))
        }
    }

    @Test
    fun bundledCatalogCoversTheFrenchMountains() {
        assertTrue(resorts.size >= 42)
        assertEquals(resorts.size, resorts.map { it.id }.toSet().size, "Duplicate resort IDs")
        assertEquals("les-saisies", resorts.first().id, "The widget falls back to the first resort")
        assertTrue(resorts.first().webcams.isNotEmpty(), "The first resort must have a webcam for the widget")
        assertTrue(resorts.flatMap { it.webcams }.none { it.hasLiveStream }, "No catalogued webcam exposes a public stream yet")
    }

    /**
     * In the Alps and the Pyrenees, only resorts too far from every massif go
     * without one; the Jura, the Vosges and the Massif Central never have one
     * (checked by [CatalogRules]).
     */
    @Test
    fun onlyGreolieresMissesItsBulletinMassif() {
        val withoutMassif = resorts.filter { it.avalancheMassif == null && it.region !in CatalogRules.regionsWithoutBulletin }
        assertEquals(listOf("greolieres"), withoutMassif.map { it.id })
    }

    @Test
    fun villageViewsAddToTheResortsOwnFour() {
        // Grand Tourmalet: three "La Mongie - ..." views and one "Barèges -
        // ...": two La Mongie views and Barèges count as villages, the third
        // La Mongie view as the resort's own.
        val tourmalet = assertNotNull(repository.resort("grand-tourmalet"))
        assertEquals(1, CatalogRules.ownViews(tourmalet))
        val saisies = assertNotNull(repository.resort("les-saisies"))
        assertEquals(saisies.webcams.size, CatalogRules.ownViews(saisies))
    }

    @Test
    fun creditsListPlatformsByWeightAndResortWebsites() {
        assertEquals(
            listOf(WebcamProviderType.WEBCAM_HD, WebcamProviderType.SKAPING, WebcamProviderType.VIEWSURF, WebcamProviderType.ROUNDSHOT),
            repository.creditedProviderTypes,
        )
        assertTrue(repository.hasResortWebsiteWebcams)

        val platformsOnly = ResortRepository(repository.resorts.filter { it.id == "les-7-laux" })
        assertEquals(listOf(WebcamProviderType.SKAPING), platformsOnly.creditedProviderTypes)
        assertFalse(platformsOnly.hasResortWebsiteWebcams)
    }

    @Test
    fun lookupWebcamById() {
        val (resort, webcam) = assertNotNull(repository.webcam("les-saisies.bisanne-1500"))
        assertEquals("les-saisies", resort.id)
        assertEquals("Bisanne 1500", webcam.name)
    }

    @Test
    fun unknownProviderAndCapabilityDecodeGracefully() {
        val json = """
            {"version":1,"resorts":[{"id":"x","name":"X","country":"FR","region":"R","latitude":0,"longitude":0,
            "timeZoneIdentifier":"Europe/Paris","websiteURL":"https://example.com","webcams":[{"id":"x.a","resortID":"x",
            "name":"A","provider":"somethingNew","sourcePageURL":"https://example.com/a",
            "attribution":{"providerName":"New"},"capabilities":["hologram","staticSnapshot"]}]}]}
        """.trimIndent()
        val webcam = assertNotNull(ResortRepository.fromJson(json).resorts.first().webcams.first())
        assertEquals(WebcamProviderType.UNKNOWN, webcam.provider)
        assertEquals(setOf(WebcamCapability.UNKNOWN, WebcamCapability.STATIC_SNAPSHOT), webcam.capabilities)
    }
}
