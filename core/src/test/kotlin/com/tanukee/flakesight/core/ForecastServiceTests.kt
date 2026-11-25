package com.tanukee.flakesight.core

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Port of `ForecastServiceTests.swift` for the daily forecast; the hourly,
 * models and snow totals tests come with those requests. Keep the two files
 * in step (see IOS_SYNC.md).
 */
class ForecastServiceTests {
    companion object {
        /** Friday 15 January 2027, 09:00 in Paris. */
        val now: Instant = Instant.ofEpochSecond(1_800_000_000)
        val friday: LocalDate = LocalDate.of(2027, 1, 15)
        const val BASE = "stub://forecast.test/v1/forecast"

        fun resort(id: String = "test", village: Int? = 1650, summit: Int? = 2700) = Resort(
            id = id, name = "Test", country = "FR", region = "R", latitude = 45.76, longitude = 6.59,
            timeZoneIdentifier = "Europe/Paris", websiteURL = "https://example.com",
            villageAltitude = village, summitAltitude = summit, webcams = emptyList(),
        )

        fun location(temperatures: String = "-4.4, -7.1, null", snow: String = "0, 12.3, 0.4") = """
            {"latitude": 45.76, "longitude": 6.59, "daily_units": {"snowfall_sum": "cm"},
             "daily": {"time": ["2027-01-15", "2027-01-16", "2027-01-17"],
                       "weather_code": [0, 73, null],
                       "temperature_2m_max": [$temperatures],
                       "snowfall_sum": [$snow]}}
        """.trimIndent()

        val twoLocations = "[${location()}, ${location(temperatures = "3, 1, 2", snow = "0, 0, 0")}]"

        /** The seven days before Friday 15 January 2027, then five from it. */
        fun locationWithPastDays(snow: String): String {
            val days = (8..19).joinToString(", ") { "\"2027-01-%02d\"".format(it) }
            val codes = List(12) { "0" }.joinToString(", ")
            return """
                {"latitude": 45.76, "longitude": 6.59,
                 "daily": {"time": [$days], "weather_code": [$codes], "temperature_2m_max": [$codes], "snowfall_sum": [$snow]}}
            """.trimIndent()
        }

        fun hourlyLocation(snow: String = "0, 1.2, 0.4", visibility: String = "24000, 800, null") = """
            {"latitude": 45.76, "longitude": 6.59,
             "hourly": {"time": ["2027-01-15T09:00", "2027-01-15T10:00", "2027-01-15T11:00"],
                        "snowfall": [$snow],
                        "rain": [0, 0.3, null],
                        "freezing_level_height": [1500, 1450, null],
                        "wind_gusts_10m": [20.2, 61.0, null],
                        "cloud_cover": [10, 100, null],
                        "visibility": [$visibility],
                        "snow_depth": [0.93, 0.95, null],
                        "weather_code": [0, 73, null],
                        "temperature_2m": [-4.2, -6, null]}}
        """.trimIndent()

        val twoHourlyLocations = "[${hourlyLocation()}, ${hourlyLocation(snow = "0, 0, 0")}]"

        fun parse(body: String, resort: Resort = resort(), fetchedAt: Instant = now) =
            ForecastService.forecast(body.encodeToByteArray(), resort, fetchedAt)

        fun response(body: String, statusCode: Int = 200) = HttpResponse(statusCode, emptyMap(), body.encodeToByteArray())
    }

    private val clock = TestClock(now)

    /** A service that only reaches [stubs], with a clock the test controls. */
    private fun makeService(stubs: Map<Resort, HttpResponse>): Pair<ForecastService, StubHttpClient> {
        val http = StubHttpClient(stubs.mapKeys { ForecastService.requestURL(it.key, BASE) })
        return ForecastService(http, BASE) { clock.now } to http
    }

    // Request

    @Test
    fun requestAsksForEveryLevelInOneCall() {
        assertEquals(
            "$BASE?latitude=45.76,45.76&longitude=6.59,6.59&elevation=2700,1650&daily=weather_code,temperature_2m_max,snowfall_sum&past_days=7&forecast_days=5&timezone=Europe/Paris",
            ForecastService.requestURL(resort(), BASE),
        )
    }

    @Test
    fun requestForASingleLevelHasOneElevation() {
        assertEquals(
            "$BASE?latitude=45.76&longitude=6.59&elevation=2700&daily=weather_code,temperature_2m_max,snowfall_sum&past_days=7&forecast_days=5&timezone=Europe/Paris",
            ForecastService.requestURL(resort(village = null), BASE),
        )
    }

    @Test
    fun requestAtTheResortCentreLetsOpenMeteoPickTheElevation() {
        assertEquals(
            "$BASE?latitude=45.76&longitude=6.59&daily=weather_code,temperature_2m_max,snowfall_sum&past_days=7&forecast_days=5&timezone=Europe/Paris",
            ForecastService.requestURL(resort(village = null, summit = null), BASE),
        )
    }

    @Test
    fun theDetailedRequestAsksForWhatTheSentencesReadHourByHour() {
        assertEquals(
            "$BASE?latitude=45.76,45.76&longitude=6.59,6.59&elevation=2700,1650&hourly=snowfall,rain,freezing_level_height,wind_gusts_10m,cloud_cover,visibility,snow_depth,weather_code,temperature_2m&forecast_days=5&timezone=Europe/Paris",
            ForecastService.detailedRequestURL(resort(), BASE),
        )
    }

    // Parsing

    @Test
    fun severalLocationsBecomeTheResortLevels() {
        val forecast = parse(twoLocations)
        assertEquals("test", forecast.resortID)
        assertEquals(listOf(ForecastLevel.Summit(2700), ForecastLevel.Village(1650)), forecast.levels.map { it.level })
        assertEquals(listOf(friday, friday.plusDays(1), friday.plusDays(2)), forecast.days.map { it.date })

        val summit = forecast.levels[0].days
        assertEquals(listOf(WeatherCondition.CLEAR, WeatherCondition.SNOW, WeatherCondition.CLOUDY), summit.map { it.condition })
        assertEquals(listOf(-4.4, -7.1, null), summit.map { it.maxTemperature })
        assertEquals(listOf(0.0, 12.3, 0.4), summit.map { it.snowfall })
        assertEquals(listOf(3.0, 1.0, 2.0), forecast.levels[1].days.map { it.maxTemperature })
    }

    @Test
    fun theDaysBeforeTodayAddUpToTheRecentSnowfall() {
        val body = "[${locationWithPastDays("5, 0, null, 10, 0, 0, 9.5, 2, 0, 0, 0, 0")}, ${locationWithPastDays("0, 0, 0, 0, 0, 0, 0.2, 0, 0, 0, 0, 0")}]"
        val forecast = parse(body)
        assertEquals(5, forecast.days.size)
        assertEquals(friday, forecast.days.first().date)
        assertEquals(listOf(24.5, 0.2), forecast.levels.map { it.recentSnowfall })
        assertEquals(listOf(2.0, 0.0, 0.0, 0.0, 0.0), forecast.levels[0].days.map { it.snowfall })
    }

    /** An answer without the seven days before today gives no recent snowfall rather than a wrong one. */
    @Test
    fun withoutTheWholeWeekBeforeTheRecentSnowfallIsUnknown() {
        assertEquals(listOf(null, null), parse(twoLocations).levels.map { it.recentSnowfall })
    }

    @Test
    fun anAnswerWithOnlyPastDaysIsRejected() {
        assertFailsWith<ForecastError.MalformedResponse> {
            parse(location(), resort(village = null, summit = null), now + Duration.ofDays(7))
        }
    }

    @Test
    fun aSingleLocationIsAcceptedForOneLevel() {
        val forecast = parse(location(), resort(village = null, summit = null))
        assertEquals(listOf<ForecastLevel>(ForecastLevel.ResortCentre), forecast.levels.map { it.level })
        assertEquals(3, forecast.days.size)
    }

    @Test
    fun theWrongNumberOfLocationsIsRejected() {
        assertFailsWith<ForecastError.MalformedResponse> { parse(location()) }
    }

    @Test
    fun raggedDailyArraysAreRejected() {
        assertFailsWith<ForecastError.MalformedResponse> { parse("[${location(snow = "0, 1")}, ${location()}]") }
    }

    @Test
    fun garbageIsRejected() {
        assertFailsWith<ForecastError.MalformedResponse> { parse("<html>") }
    }

    @Test
    fun hourlyLocationsBecomeTheResortLevels() {
        val forecast = ForecastService.detailedForecast(twoHourlyLocations.encodeToByteArray(), resort(), now)
        assertEquals(listOf(ForecastLevel.Summit(2700), ForecastLevel.Village(1650)), forecast.levels.map { it.level })

        val summit = forecast.levels[0].hours
        assertEquals(listOf(now, now.plusSeconds(3600), now.plusSeconds(7200)), summit.map { it.start })
        assertEquals(listOf(0.0, 1.2, 0.4), summit.map { it.snowfall })
        assertEquals(listOf(0.0, 0.3, 0.0), summit.map { it.rain })
        assertEquals(listOf(1500.0, 1450.0, null), summit.map { it.freezingLevel })
        assertEquals(listOf(20.2, 61.0, null), summit.map { it.windGusts })
        assertEquals(listOf(10, 100, null), summit.map { it.cloudCover })
        assertEquals(listOf(24000.0, 800.0, null), summit.map { it.visibility })
        assertEquals(listOf(93.0, 95.0, null), summit.map { it.snowDepth })
        assertEquals(listOf(WeatherCondition.CLEAR, WeatherCondition.SNOW, null), summit.map { it.condition })
        assertEquals(listOf(-4.2, -6.0, null), summit.map { it.temperature })
        assertEquals(listOf(0.0, 0.0, 0.0), forecast.levels[1].hours.map { it.snowfall })
    }

    @Test
    fun raggedHourlyArraysAreRejected() {
        val body = "[${hourlyLocation(visibility = "24000, 800")}, ${hourlyLocation()}]"
        assertFailsWith<ForecastError.MalformedResponse> { ForecastService.detailedForecast(body.encodeToByteArray(), resort(), now) }
    }

    @Test
    fun aDailyAnswerIsNotADetailedOne() {
        assertFailsWith<ForecastError.MalformedResponse> { ForecastService.detailedForecast(twoLocations.encodeToByteArray(), resort(), now) }
    }

    @Test
    fun theModelsRequestAsksForEachModelsDailySnowfall() {
        assertEquals(
            "$BASE?latitude=45.76,45.76&longitude=6.59,6.59&elevation=2700,1650&daily=snowfall_sum&models=meteofrance_seamless,icon_seamless,ecmwf_ifs025,gfs_seamless&forecast_days=5&timezone=Europe/Paris",
            ForecastService.modelSnowfallRequestURL(resort(), BASE),
        )
    }

    /** Two days of each model's snowfall; [models] maps a model to its days. */
    private fun modelLocation(models: Map<String, String>, days: String = "\"2027-01-15\", \"2027-01-16\""): String {
        val series = models.entries.joinToString(", ") { (model, values) -> "\"snowfall_sum_$model\": [$values]" }
        return """{"latitude": 45.76, "longitude": 6.59, "daily": {"time": [$days], $series}}"""
    }

    @Test
    fun eachModelKeepsItsAmountsInTheOrderOfTheModels() {
        val summit = modelLocation(
            mapOf("meteofrance_seamless" to "15.9, null", "icon_seamless" to "48.2, 12.5", "ecmwf_ifs025" to "27.2, 25.8", "gfs_seamless" to "36.2, 10.9"),
        )
        // A model Open-Meteo left out counts as having no amount.
        val village = modelLocation(mapOf("icon_seamless" to "40, 10", "gfs_seamless" to "30, 0"))
        val models = ForecastService.modelSnowfall("[$summit, $village]".encodeToByteArray(), resort(), now)
        assertEquals(listOf(ForecastLevel.Summit(2700), ForecastLevel.Village(1650)), models.levels.map { it.level })
        assertEquals(listOf(15.9, 48.2, 27.2, 36.2), models.levels[0].days[friday])
        assertEquals(listOf(null, 12.5, 25.8, 10.9), models.levels[0].days[friday.plusDays(1)])
        assertEquals(listOf(null, 40.0, null, 30.0), models.levels[1].days[friday])
    }

    @Test
    fun raggedModelSeriesAreRejected() {
        val body = "[${modelLocation(mapOf("icon_seamless" to "1"))}, ${modelLocation(mapOf("icon_seamless" to "1, 2"))}]"
        assertFailsWith<ForecastError.MalformedResponse> { ForecastService.modelSnowfall(body.encodeToByteArray(), resort(), now) }
    }

    @Test
    fun theModelsAnswerNeedsEveryLevel() {
        assertFailsWith<ForecastError.MalformedResponse> {
            ForecastService.modelSnowfall(modelLocation(mapOf("icon_seamless" to "1, 2")).encodeToByteArray(), resort(), now)
        }
    }

    // HTTP

    @Test
    fun anOpenMeteoRefusalCarriesItsReason() = runBlocking<Unit> {
        val (service, _) = makeService(mapOf(resort() to response("""{"error": true, "reason": "Invalid elevation"}""", 400)))
        val error = assertFailsWith<ForecastError.Rejected> { service.forecast(resort()) }
        assertEquals("Invalid elevation", error.reason)
    }

    @Test
    fun otherFailuresKeepTheStatusCode() = runBlocking<Unit> {
        val (service, _) = makeService(mapOf(resort() to response("", 503)))
        val error = assertFailsWith<ForecastError.BadStatusCode> { service.forecast(resort()) }
        assertEquals(503, error.code)
        assertNull(service.cachedForecast(resort()))
    }

    // Cache

    @Test
    fun aRecentForecastIsReused() = runBlocking<Unit> {
        val (service, http) = makeService(mapOf(resort() to response(twoLocations)))
        val url = ForecastService.requestURL(resort(), BASE)

        val first = service.forecast(resort())
        clock.advance(Duration.ofMinutes(29))
        val second = service.forecast(resort())
        assertEquals(first, second)
        assertEquals(first, service.cachedForecast(resort()))
        assertEquals(1, http.requestCount(url))

        clock.advance(Duration.ofMinutes(2))
        assertNull(service.cachedForecast(resort()))
        service.forecast(resort())
        assertEquals(2, http.requestCount(url))
    }

    @Test
    fun midnightInTheResortEndsTheCache() = runBlocking<Unit> {
        // 23:50 in Paris on the Friday.
        clock.now = friday.atTime(23, 50).atZone(resort().timeZone).toInstant()
        val (service, http) = makeService(mapOf(resort() to response(twoLocations)))

        service.forecast(resort())
        clock.advance(Duration.ofMinutes(15))
        assertNull(service.cachedForecast(resort()))
        service.forecast(resort())
        assertEquals(2, http.requestCount(ForecastService.requestURL(resort(), BASE)))
    }

    @Test
    fun concurrentCallersShareOneRequest() = runBlocking<Unit> {
        val (service, http) = makeService(mapOf(resort() to response(twoLocations)))
        val first = async { service.forecast(resort()) }
        val second = async { service.forecast(resort()) }
        assertSame(first.await(), second.await())
        assertEquals(1, http.requestCount(ForecastService.requestURL(resort(), BASE)))
        assertEquals(0, service.inFlightCount)
    }

    @Test
    fun theDetailedForecastIsCachedApartFromTheDailyOne() = runBlocking<Unit> {
        val dailyURL = ForecastService.requestURL(resort(), BASE)
        val hourlyURL = ForecastService.detailedRequestURL(resort(), BASE)
        val http = StubHttpClient(mapOf(dailyURL to response(twoLocations), hourlyURL to response(twoHourlyLocations)))
        val service = ForecastService(http, BASE) { clock.now }

        service.forecast(resort())
        val first = service.detailedForecast(resort())
        clock.advance(Duration.ofMinutes(29))
        assertEquals(first, service.detailedForecast(resort()))
        assertEquals(1, http.requestCount(hourlyURL))
        assertEquals(1, http.requestCount(dailyURL))

        clock.advance(Duration.ofMinutes(2))
        service.detailedForecast(resort())
        assertEquals(2, http.requestCount(hourlyURL))
    }

    @Test
    fun theModelsAreCachedLikeTheForecast() = runBlocking<Unit> {
        val url = ForecastService.modelSnowfallRequestURL(resort(), BASE)
        val location = modelLocation(mapOf("icon_seamless" to "4, 2", "gfs_seamless" to "6, 0"))
        val http = StubHttpClient(mapOf(url to response("[$location, $location]")))
        val service = ForecastService(http, BASE) { clock.now }

        assertNull(service.cachedModelSnowfall(resort()))
        val first = service.modelSnowfall(resort())
        clock.advance(Duration.ofMinutes(29))
        assertEquals(first, service.cachedModelSnowfall(resort()))
        service.modelSnowfall(resort())
        assertEquals(1, http.requestCount(url))

        clock.advance(Duration.ofMinutes(2))
        assertNull(service.cachedModelSnowfall(resort()))
        service.modelSnowfall(resort())
        assertEquals(2, http.requestCount(url))
    }

    @Test
    fun differentResortsAreCachedApart() = runBlocking<Unit> {
        val other = resort(id = "other", village = null, summit = null)
        val (service, _) = makeService(mapOf(resort() to response(twoLocations), other to response(location())))

        assertEquals("test", service.forecast(resort()).resortID)
        val second = service.forecast(other)
        assertEquals("other", second.resortID)
        assertEquals(listOf<ForecastLevel>(ForecastLevel.ResortCentre), second.levels.map { it.level })
    }
}
