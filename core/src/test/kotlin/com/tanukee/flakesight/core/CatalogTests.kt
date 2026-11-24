package com.tanukee.flakesight.core

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        "merlette, orcieres",
    )
    fun searchMatchesNamesRegionsAndWebcams(query: String, expectedId: String) {
        val matches = resorts.filter { it.matches(query) }
        assertEquals(listOf(expectedId), matches.map { it.id })
    }

    @Test
    fun emptySearchMatchesEverything() {
        assertTrue(resorts.all { it.matches("  ") })
    }

    @Test
    fun webcamsAreConsistent() {
        for (resort in resorts) {
            assertEquals(resort.webcams.size, resort.webcams.map { it.id }.toSet().size, "Duplicate webcam IDs in ${resort.id}")
            for (webcam in resort.webcams) {
                assertEquals(resort.id, webcam.resortID)
                assertTrue(webcam.id.startsWith("${resort.id}."), webcam.id)
                assertTrue(webcam.provider != WebcamProviderType.UNKNOWN, webcam.id)
                assertTrue(!webcam.hasLiveStream, "No catalogued webcam exposes a public stream yet")
                if (webcam.provider == WebcamProviderType.WEBCAM_HD) {
                    assertTrue(webcam.snapshotURL != null && webcam.thumbnailURL != null, webcam.id)
                }
            }
        }
    }

    @Test
    fun bundledCatalogCoversTheFrenchMountains() {
        assertTrue(resorts.size >= 42)
        assertEquals(resorts.size, resorts.map { it.id }.toSet().size, "Duplicate resort IDs")
        assertEquals("les-saisies", resorts.first().id, "The widget falls back to the first resort")
        assertTrue(resorts.first().webcams.isNotEmpty(), "The first resort must have a webcam for the widget")
        for (resort in resorts) {
            assertEquals("FR", resort.country)
            assertTrue(
                MOUNTAIN_RANGES.any { resort.latitude in it.first && resort.longitude in it.second },
                "${resort.id} is outside the French Alps and Pyrenees",
            )
        }
        for (resort in resorts.filter { it.id !in setOf("les-saisies", "les-7-laux") }) {
            assertTrue(resort.webcams.size <= 4, "${resort.id} has too many webcams")
        }
    }

    @Test
    fun altitudesAreWithinTheMountainsAndOrdered() {
        for (resort in resorts) {
            resort.villageAltitude?.let { assertTrue(it in 300..4900, "${resort.id} village altitude") }
            resort.summitAltitude?.let { assertTrue(it in 300..4900, "${resort.id} summit altitude") }
            val village = resort.villageAltitude
            val summit = resort.summitAltitude
            if (village != null && summit != null) {
                assertTrue(summit > village, "${resort.id} summit below village")
            }
        }
        assertTrue(resorts.any { it.summitAltitude != null && it.villageAltitude != null }, "No resort has both altitudes")
    }

    @Test
    fun avalancheMassifsAreAlpineOrPyrenean() {
        val alps = 1..23
        val pyrenees = 64..74
        for (resort in resorts) {
            val massif = resort.avalancheMassif ?: continue
            assertTrue(massif in alps || massif in pyrenees, "${resort.id} massif $massif")
            val inPyrenees = resort.latitude < 43.3
            assertTrue(if (inPyrenees) massif in pyrenees else massif in alps, "${resort.id} massif $massif is in the other range")
        }
        assertEquals(listOf("greolieres"), resorts.filter { it.avalancheMassif == null }.map { it.id })
    }

    @Test
    fun seasonsAreWithinTheWinter() {
        for (resort in resorts) {
            val season = resort.season ?: continue
            val opening = season.opening
            assertTrue(opening.monthValue >= 10 || opening.monthValue == 1, "${resort.id} opens in month ${opening.monthValue}")
            season.closing?.let { closing ->
                assertTrue(closing.monthValue in 3..6, "${resort.id} closes in month ${closing.monthValue}")
                val seasonYear = if (opening.monthValue == 1) opening.year else opening.year + 1
                assertEquals(seasonYear, closing.year, "${resort.id} closes in ${closing.year}")
            }
            assertTrue(season.sourceURL.startsWith("https://"), "${resort.id} season source is not https")
        }
    }

    @Test
    fun carParksAreNearTheirResort() {
        for (resort in resorts) {
            val parking = resort.parking ?: continue
            val distance = resort.distanceFrom(parking.latitude, parking.longitude)
            assertTrue(distance <= 3000, "${resort.id} car park is ${distance.toInt()} m from the resort centre")
            assertTrue(parking.name?.isNotEmpty() ?: true, "${resort.id} car park has an empty name")
        }
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

    private companion object {
        /** The French Alps, Drôme's Vercors included, and the French Pyrenees. */
        val MOUNTAIN_RANGES = listOf(
            43.7..46.5 to 5.0..7.7,
            42.3..43.2 to -1.8..3.2,
        )
    }
}
