package com.tanukee.flakesight.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.time.ZoneId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Five-day forecasts per resort from Open-Meteo, one request per resort
 * covering its summit and village, cached in memory for half an hour. Port of
 * `ForecastService` in SkiPatrolKit for the daily forecast; the hourly
 * forecast, the models' snowfall and the snow totals come later.
 *
 * Open-Meteo's free tier is for non-commercial use and its data is CC BY 4.0,
 * which is why every screen that draws a forecast credits it.
 */
class ForecastService(
    private val http: HttpClient = UrlConnectionHttpClient,
    /** The forecast endpoint. Tests point it at a stub; a paid plan would use its customer host. */
    private val baseURL: String = DEFAULT_BASE_URL,
    private val now: () -> Instant = Instant::now,
) {
    private val lock = Any()
    private val cache = mutableMapOf<String, ResortForecast>()

    /** Requests under way, so concurrent callers for one resort share a request. */
    private val inFlight = mutableMapOf<String, Deferred<ResortForecast>>()
    private var snowTotalsCache: SnowTotals? = null

    /** The snow-totals load under way, shared by concurrent callers. */
    private var snowTotalsInFlight: Deferred<SnowTotals>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The last forecast still valid, so a screen can draw before awaiting. */
    fun cachedForecast(resort: Resort): ResortForecast? =
        synchronized(lock) { cache[resort.id] }?.takeIf { isFresh(it.fetchedAt, resort.timeZone) }

    /**
     * The last snow totals still valid and covering every resort asked for,
     * so the list can sort before awaiting.
     */
    fun cachedSnowTotals(resorts: List<Resort>): SnowTotals? = synchronized(lock) { snowTotalsCache }?.takeIf { cached ->
        isFresh(cached.fetchedAt, resorts.firstOrNull()?.timeZone ?: ZoneId.systemDefault()) &&
            resorts.all { it.id in cached.entries }
    }

    /**
     * Recent and coming snow for every resort, at its [Resort.snowRankingLevel]:
     * a few multi-location requests (one per [SNOW_TOTALS_BATCH_SIZE] resorts)
     * instead of one per resort. From memory when fresh; concurrent callers share one load.
     */
    suspend fun snowTotals(resorts: List<Resort>): SnowTotals {
        cachedSnowTotals(resorts)?.let { return it }
        val task = synchronized(lock) {
            snowTotalsInFlight ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val fetchedAt = now()
                    val entries = coroutineScope {
                        snowTotalsBatches(resorts).map { batch ->
                            async { snowTotalsEntries(load(snowTotalsRequestURL(batch, baseURL)), batch) }
                        }.awaitAll()
                    }.fold(emptyMap<String, SnowTotals.Entry>()) { all, batch -> all + batch }
                    SnowTotals(entries, fetchedAt).also { synchronized(lock) { snowTotalsCache = it } }
                } finally {
                    val self = coroutineContext.job
                    synchronized(lock) { if (snowTotalsInFlight === self) snowTotalsInFlight = null }
                }
            }.also { snowTotalsInFlight = it }
        }
        task.start()
        return task.await()
    }

    /**
     * The resort's forecast, from memory when fresh, otherwise one request
     * shared by every concurrent caller. Cancelling a caller never cancels
     * the request others wait for.
     */
    suspend fun forecast(resort: Resort): ResortForecast {
        cachedForecast(resort)?.let { return it }
        val task = synchronized(lock) {
            inFlight[resort.id] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val forecast = forecast(load(requestURL(resort, baseURL)), resort, now())
                    synchronized(lock) { cache[resort.id] = forecast }
                    forecast
                } finally {
                    val self = coroutineContext.job
                    synchronized(lock) { if (inFlight[resort.id] === self) inFlight.remove(resort.id) }
                }
            }.also { inFlight[resort.id] = it }
        }
        task.start()
        return task.await()
    }

    /** Fresh means recent and still the same resort-local day: a cached "today" must not survive midnight. */
    private fun isFresh(fetchedAt: Instant, zone: ZoneId): Boolean {
        val current = now()
        return Duration.between(fetchedAt, current) < MINIMUM_REFRESH_INTERVAL &&
            fetchedAt.atZone(zone).toLocalDate() == current.atZone(zone).toLocalDate()
    }

    /** Never from the HTTP cache: a stale forecast is worse than none. */
    private suspend fun load(url: String): ByteArray {
        val response = withContext(Dispatchers.IO) { http.get(url, useCaches = false) }
        if (response.statusCode !in 200..299) {
            val failure = runCatching { json.decodeFromString<OpenMeteoFailure>(response.body.decodeToString()) }.getOrNull()
            if (failure?.error == true) throw ForecastError.Rejected(failure.reason)
            throw ForecastError.BadStatusCode(response.statusCode)
        }
        return response.body
    }

    /** Requests currently shared between callers. */
    internal val inFlightCount: Int get() = synchronized(lock) { inFlight.size }

    companion object {
        /** Forecasts younger than this are reused. The models update hourly and the strip is a glance. */
        val MINIMUM_REFRESH_INTERVAL: Duration = Duration.ofMinutes(30)
        const val DEFAULT_BASE_URL = "https://api.open-meteo.com/v1/forecast"

        /** Where the required "Weather data by Open-Meteo" credit links to. */
        const val ATTRIBUTION_URL = "https://open-meteo.com"
        const val DAY_COUNT = 5

        /** Days before today that count as recent snow. */
        const val SNOW_RANKING_PAST_DAYS = 7

        /** Locations per snow-totals request, to keep the URL short. */
        const val SNOW_TOTALS_BATCH_SIZE = 100

        private val json = Json { ignoreUnknownKeys = true }

        private fun url(baseURL: String, items: List<Pair<String, String>>): String {
            val separator = if ('?' in baseURL) "&" else "?"
            return baseURL + separator + items.joinToString("&") { (name, value) -> "$name=$value" }
        }

        /**
         * One request for every level of the resort: the coordinates repeated,
         * and the altitudes as `elevation` so Open-Meteo downscales to each.
         * The days before today come too, for the snow fallen lately.
         */
        fun requestURL(resort: Resort, baseURL: String): String {
            val levels = resort.forecastLevels
            fun repeated(value: Double) = List(levels.size) { value.toString() }.joinToString(",")
            val altitudes = levels.mapNotNull { it.altitude }
            val items = listOfNotNull(
                "latitude" to repeated(resort.latitude),
                "longitude" to repeated(resort.longitude),
                altitudes.takeIf { it.size == levels.size }?.let { "elevation" to it.joinToString(",") },
                "daily" to "weather_code,temperature_2m_max,snowfall_sum",
                "past_days" to SNOW_RANKING_PAST_DAYS.toString(),
                "forecast_days" to DAY_COUNT.toString(),
                "timezone" to resort.timeZoneIdentifier,
            )
            return url(baseURL, items)
        }

        /**
         * The resorts split into requests: those with an altitude and those
         * without apart (`elevation` is all or nothing), then at most
         * [SNOW_TOTALS_BATCH_SIZE] per request.
         */
        fun snowTotalsBatches(resorts: List<Resort>): List<List<Resort>> {
            val (withAltitude, withoutAltitude) = resorts.partition { it.snowRankingLevel.altitude != null }
            return listOf(withAltitude, withoutAltitude).flatMap { it.chunked(SNOW_TOTALS_BATCH_SIZE) }
        }

        /**
         * One location per resort at its [Resort.snowRankingLevel], daily
         * snowfall from [SNOW_RANKING_PAST_DAYS] days ago to the end of the forecast.
         */
        fun snowTotalsRequestURL(resorts: List<Resort>, baseURL: String): String {
            require(resorts.isNotEmpty()) { "No resort to ask for" }
            val altitudes = resorts.mapNotNull { it.snowRankingLevel.altitude }
            val items = listOfNotNull(
                "latitude" to resorts.joinToString(",") { it.latitude.toString() },
                "longitude" to resorts.joinToString(",") { it.longitude.toString() },
                altitudes.takeIf { it.size == resorts.size }?.let { "elevation" to it.joinToString(",") },
                "daily" to "snowfall_sum",
                "past_days" to SNOW_RANKING_PAST_DAYS.toString(),
                "forecast_days" to DAY_COUNT.toString(),
                "timezone" to resorts.joinToString(",") { it.timeZoneIdentifier },
            )
            return url(baseURL, items)
        }

        /** Parses a snow-totals answer: one location per resort, in order, each with the past days then the forecast days. */
        fun snowTotalsEntries(data: ByteArray, resorts: List<Resort>): Map<String, SnowTotals.Entry> {
            val locations = decodeLocations(data, SnowLocation.serializer())
            if (locations.size != resorts.size) throw ForecastError.MalformedResponse()
            val dayCount = SNOW_RANKING_PAST_DAYS + DAY_COUNT
            return resorts.zip(locations) { resort, location ->
                val daily = location.daily
                if (daily.time.size != dayCount || daily.snowfallSum.size != dayCount) throw ForecastError.MalformedResponse()
                val snowfall = daily.snowfallSum.map { it ?: 0.0 }
                resort.id to SnowTotals.Entry(
                    recent = snowfall.take(SNOW_RANKING_PAST_DAYS).sum(),
                    forecast = snowfall.drop(SNOW_RANKING_PAST_DAYS).sum(),
                )
            }.toMap()
        }

        /**
         * Parses Open-Meteo's answer: an array of locations when several were
         * asked for, a single object otherwise. Every level must come back with
         * the same days. Days before the resort-local day of [fetchedAt] add up
         * to the level's recent snowfall, known only when there are exactly
         * [SNOW_RANKING_PAST_DAYS] of them; the forecast days start today.
         */
        fun forecast(data: ByteArray, resort: Resort, fetchedAt: Instant): ResortForecast {
            val locations = decodeLocations(data, OpenMeteoLocation.serializer())
            val levels = resort.forecastLevels
            if (locations.size != levels.size) throw ForecastError.MalformedResponse()
            val today = fetchedAt.atZone(resort.timeZone).toLocalDate()

            val forecasts = levels.zip(locations) { level, location ->
                val daily = location.daily
                val count = daily.time.size
                if (count == 0 || daily.weatherCode.size != count || daily.temperatureMax.size != count || daily.snowfallSum.size != count) {
                    throw ForecastError.MalformedResponse()
                }
                val days = List(count) { index ->
                    DailyForecast(
                        date = runCatching { LocalDate.parse(daily.time[index]) }.getOrElse { throw ForecastError.MalformedResponse() },
                        condition = WeatherCondition.fromWmoCode(daily.weatherCode[index] ?: -1),
                        maxTemperature = daily.temperatureMax[index],
                        snowfall = daily.snowfallSum[index] ?: 0.0,
                    )
                }
                val (past, upcoming) = days.partition { it.date < today }
                if (upcoming.isEmpty()) throw ForecastError.MalformedResponse()
                val recentSnowfall = past.takeIf { it.size == SNOW_RANKING_PAST_DAYS }?.sumOf { it.snowfall }
                LevelForecast(level, upcoming, recentSnowfall)
            }
            return ResortForecast(resort.id, forecasts, fetchedAt)
        }

        /** An array of locations when several were asked for, a single object otherwise. */
        private fun <T> decodeLocations(data: ByteArray, serializer: KSerializer<T>): List<T> = runCatching {
            when (val root = json.parseToJsonElement(data.decodeToString())) {
                is JsonArray -> json.decodeFromJsonElement(ListSerializer(serializer), root)
                else -> listOf(json.decodeFromJsonElement(serializer, root))
            }
        }.getOrElse { throw ForecastError.MalformedResponse() }
    }

    @Serializable
    private class OpenMeteoLocation(val daily: Daily)

    @Serializable
    private class Daily(
        val time: List<String>,
        @SerialName("weather_code") val weatherCode: List<Int?>,
        @SerialName("temperature_2m_max") val temperatureMax: List<Double?>,
        @SerialName("snowfall_sum") val snowfallSum: List<Double?>,
    )

    @Serializable
    private class SnowLocation(val daily: SnowDaily)

    @Serializable
    private class SnowDaily(val time: List<String>, @SerialName("snowfall_sum") val snowfallSum: List<Double?>)

    @Serializable
    private class OpenMeteoFailure(val error: Boolean, val reason: String)
}

/**
 * Why a forecast could not be fetched. Never shown: the strip simply stays
 * empty, so the messages are not translated.
 */
sealed class ForecastError(message: String) : Exception(message) {
    class BadStatusCode(val code: Int) : ForecastError("Open-Meteo answered $code.")

    /** Open-Meteo refused the request and said why. */
    class Rejected(val reason: String) : ForecastError("Open-Meteo refused the request: $reason")
    class MalformedResponse : ForecastError("Open-Meteo's answer could not be read.")
}
