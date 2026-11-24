package com.tanukee.flakesight.core

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of `SnowTotalsTests.swift`: the bulk snowfall request behind the list's snow sorts. Keep the two files in step. */
class SnowTotalsTests {
    companion object {
        const val BASE = "stub://snow.test/v1/forecast"

        fun resort(id: String, summit: Int? = 2700, village: Int? = 1650) =
            ForecastServiceTests.resort(id = id, village = village, summit = summit)

        /** Seven past days then five forecast days. */
        fun location(snow: String = "1, 0, 0, 0, 0, 2, null, 0.5, 10, 0, 4, 0"): String {
            val days = (8..19).joinToString(", ") { "\"2027-01-%02d\"".format(it) }
            return """{"latitude": 45.76, "longitude": 6.59, "daily": {"time": [$days], "snowfall_sum": [$snow]}}"""
        }

        val resorts = listOf(resort("a"), resort("b"))
        val body = "[${location()}, ${location(snow = "0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 30")}]"
    }

    private val clock = TestClock(ForecastServiceTests.now)
    private val url = ForecastService.snowTotalsRequestURL(resorts, BASE)
    private val http = StubHttpClient(mapOf(url to HttpResponse(200, emptyMap(), body.encodeToByteArray())))
    private val service = ForecastService(http, BASE) { clock.now }

    // Request

    @Test
    fun oneLocationPerResortAtItsSummit() {
        assertEquals(
            "$BASE?latitude=45.76,45.76&longitude=6.59,6.59&elevation=2700,1650&daily=snowfall_sum&past_days=7&forecast_days=5&timezone=Europe/Paris,Europe/Paris",
            ForecastService.snowTotalsRequestURL(listOf(resort("a"), resort("b", summit = null)), BASE),
        )
    }

    @Test
    fun resortsWithoutAltitudeGoInARequestOfTheirOwn() {
        val batches = ForecastService.snowTotalsBatches(listOf(resort("a"), resort("c", summit = null, village = null), resort("b")))
        assertEquals(listOf(listOf("a", "b"), listOf("c")), batches.map { batch -> batch.map { it.id } })
        assertFalse("elevation" in ForecastService.snowTotalsRequestURL(batches[1], BASE))
    }

    @Test
    fun theCatalogueIsSplitIntoBatches() {
        val resorts = ResortRepository.bundled().resorts
        val batches = ForecastService.snowTotalsBatches(resorts)
        assertTrue(batches.all { it.size <= ForecastService.SNOW_TOTALS_BATCH_SIZE })
        assertEquals(resorts.size, batches.flatten().size)
    }

    // Parsing

    @Test
    fun pastDaysAreRecentAndTheRestIsForecast() {
        val entries = ForecastService.snowTotalsEntries(body.encodeToByteArray(), resorts)
        assertEquals(SnowTotals.Entry(recent = 3.0, forecast = 14.5), entries["a"])
        assertEquals(SnowTotals.Entry(recent = 0.0, forecast = 30.0), entries["b"])
    }

    @Test
    fun aSingleLocationIsAccepted() {
        assertEquals(setOf("a"), ForecastService.snowTotalsEntries(location().encodeToByteArray(), listOf(resort("a"))).keys)
    }

    @Test
    fun theWrongNumberOfLocationsIsRejected() {
        assertFailsWith<ForecastError.MalformedResponse> { ForecastService.snowTotalsEntries(location().encodeToByteArray(), resorts) }
    }

    @Test
    fun theWrongNumberOfDaysIsRejected() {
        val ragged = "[${location(snow = "0, 1")}, ${location()}]"
        assertFailsWith<ForecastError.MalformedResponse> { ForecastService.snowTotalsEntries(ragged.encodeToByteArray(), resorts) }
    }

    /** The app formats the centimetres; under one, or for a resort not fetched, there is nothing to show. */
    @Test
    fun labelsSkipLessThanACentimetre() {
        val totals = SnowTotals(mapOf("a" to SnowTotals.Entry(recent = 0.4, forecast = 12.3)), ForecastServiceTests.now)
        assertEquals(12, totals.centimetres(SnowTotals.Kind.FORECAST, "a"))
        assertNull(totals.centimetres(SnowTotals.Kind.RECENT, "a"))
        assertNull(totals.centimetres(SnowTotals.Kind.FORECAST, "missing"))
    }

    // Cache

    @Test
    fun recentTotalsAreReused() = runBlocking<Unit> {
        val first = service.snowTotals(resorts)
        clock.advance(Duration.ofMinutes(29))
        assertEquals(first, service.snowTotals(resorts))
        assertEquals(first, service.cachedSnowTotals(resorts))
        assertEquals(1, http.requestCount(url))

        clock.advance(Duration.ofMinutes(2))
        assertNull(service.cachedSnowTotals(resorts))
        service.snowTotals(resorts)
        assertEquals(2, http.requestCount(url))
    }

    @Test
    fun totalsMissingAResortAreNotReused() = runBlocking<Unit> {
        service.snowTotals(resorts)
        assertNull(service.cachedSnowTotals(resorts + resort("c")))
    }

    @Test
    fun concurrentCallersShareOneLoad() = runBlocking<Unit> {
        val first = async { service.snowTotals(resorts) }
        val second = async { service.snowTotals(resorts) }
        assertEquals(first.await(), second.await())
        assertEquals(1, http.requestCount(url))
    }
}
