package com.tanukee.flakesight.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The off-piste snow depth of Météo-France's latest avalanche bulletin (BRA)
 * for each massif of the Alps and the Pyrenees, as published every afternoon
 * at [URL] by the tanukee.com website's workflow. Port of `SnowDepthReport`.
 *
 * A bulletin gives the depth at three altitudes 500 m apart, on north and
 * south slopes, and the altitude where continuous snow cover starts. It is a
 * forecaster's estimate for the whole massif, not a measurement at a resort,
 * which is why every screen says "off-piste" and credits the bulletin.
 *
 * Data: Météo-France, Licence Ouverte 2.0. The mountain weather, fresh snow
 * and exposed slopes the file also carries come with the advanced weather screen.
 */
@Serializable
data class SnowDepthReport(val version: Int = 1, val massifs: Map<String, Massif>) {
    /** The depth at one altitude of a massif, in centimetres. */
    @Serializable
    data class Level(val altitude: Int, val north: Int? = null, val south: Int? = null)

    /** North or south slopes, as the bulletin gives the depth. */
    enum class Side { NORTH, SOUTH }

    /** The snow cover on one day: the snow lines and the depth by altitude. */
    @Serializable
    data class Snapshot(
        /** "2026-11-21", French local time. */
        val observedOn: String,
        /** Altitude in metres from which the north slopes are snow-covered; `null` when the bulletin gives none. */
        val snowLineNorth: Int? = null,
        val snowLineSouth: Int? = null,
        /** Lowest first. */
        val levels: List<Level>,
    ) {
        /**
         * The depth at [altitude], the mean of the north and south slopes rounded
         * to [ROUNDING_STEP], or `null` when neither slope can be read there.
         */
        fun depth(altitude: Int): SnowDepthReading.Depth? {
            val slopes = listOfNotNull(slope(Side.NORTH, altitude), slope(Side.SOUTH, altitude))
            if (slopes.isEmpty()) return null
            return SnowDepthReading.Depth(rounded(slopes.map { it.first }.average()), slopes.any { it.second })
        }

        /** The depth on the north or south slopes alone at [altitude]. */
        fun depth(side: Side, altitude: Int): SnowDepthReading.Depth? =
            slope(side, altitude)?.let { SnowDepthReading.Depth(rounded(it.first), it.second) }

        /** The altitude from which the slopes are snow-covered, the mean of north and south when both are given. */
        val snowLine: Int?
            get() = listOfNotNull(snowLineNorth, snowLineSouth).takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }

        /** The day, or `null` when malformed. */
        val observationDate: LocalDate? get() = runCatching { LocalDate.parse(observedOn) }.getOrNull()

        private fun slope(side: Side, altitude: Int) = when (side) {
            Side.NORTH -> Massif.slopeDepth(altitude, levels.map { it.altitude to it.north }, snowLineNorth)
            Side.SOUTH -> Massif.slopeDepth(altitude, levels.map { it.altitude to it.south }, snowLineSouth)
        }

        private fun rounded(centimetres: Double) = DailyForecast.wholeNumber(centimetres / ROUNDING_STEP) * ROUNDING_STEP
    }

    /** One massif's bulletin. */
    @Serializable
    data class Massif(
        val name: String,
        /** When the bulletin came out, ISO 8601 with its offset. */
        val issuedAt: String,
        /** The day the depths describe, "2026-11-21", French local time. */
        val observedOn: String,
        val snowLineNorth: Int? = null,
        val snowLineSouth: Int? = null,
        /** Lowest first. */
        val levels: List<Level>,
        /** The danger level; `null` in files written before it was kept. */
        val avalancheRisk: AvalancheRisk? = null,
        /** The past week of snow cover, oldest first, ending with this one. */
        val history: List<Snapshot>? = null,
        /** The most exposed aspects as the file writes them, "N" to "NW". */
        val exposedSlopes: List<String>? = null,
        val freshSnow: FreshSnow? = null,
        val weather: MountainWeather? = null,
    ) {
        /** Today's snow cover. */
        val current: Snapshot get() = Snapshot(observedOn, snowLineNorth, snowLineSouth, levels)

        /** The aspects the app knows, clockwise from north. */
        val exposedAspects: List<SlopeAspect> get() = exposedSlopes.orEmpty().mapNotNull(SlopeAspect::fromRawValue)

        /** When the bulletin came out, or `null` when malformed. */
        val issueDate: Instant? get() = bulletinTime(issuedAt)

        /** The depth at [altitude]; see [Snapshot.depth]. */
        fun depth(altitude: Int): SnowDepthReading.Depth? = current.depth(altitude)

        /**
         * The change in depth at [altitude] from the oldest day of the history
         * to today, and how many days that covers; `null` without a history or
         * a depth on both days.
         */
        fun change(altitude: Int): Pair<Int, Int>? {
            val first = history?.firstOrNull() ?: return null
            if (first.observedOn >= observedOn) return null
            val before = first.depth(altitude) ?: return null
            val now = depth(altitude) ?: return null
            val start = first.observationDate ?: return null
            val end = current.observationDate ?: return null
            val days = ChronoUnit.DAYS.between(start, end).toInt()
            return if (days > 0) (now.centimetres - before.centimetres) to days else null
        }

        internal companion object {
            /**
             * One slope read at [altitude], in centimetres, and whether it is a lower bound:
             * - between two altitudes of the bulletin, a straight line between them;
             * - between the snow line and the lowest altitude, a straight line from nothing at the snow line;
             * - below the snow line, nothing; below the lowest altitude with no snow line given, unknown;
             * - above the highest altitude, that altitude's depth as a lower bound,
             *   since snow rarely thins with height in a bulletin.
             */
            fun slopeDepth(altitude: Int, points: List<Pair<Int, Int?>>, snowLine: Int?): Pair<Double, Boolean>? {
                val anchors = points.mapNotNull { (a, depth) -> depth?.let { a.toDouble() to it.toDouble() } }
                    .sortedBy { it.first }
                    .toMutableList()
                val lowest = anchors.firstOrNull() ?: return null
                val highest = anchors.last()
                val height = altitude.toDouble()
                if (snowLine != null && snowLine < lowest.first) anchors.add(0, snowLine.toDouble() to 0.0)
                if (height < anchors.first().first) return if (snowLine == null) null else 0.0 to false
                if (height >= highest.first) return highest.second to (height > highest.first)
                for ((low, high) in anchors.zipWithNext()) {
                    if (height < high.first) {
                        val fraction = (height - low.first) / (high.first - low.first)
                        return (low.second + fraction * (high.second - low.second)) to false
                    }
                }
                return null
            }
        }
    }

    /**
     * The resort's depths at its summit and village, or `null` when it has no
     * massif or altitude, its massif has no bulletin, or the bulletin is more
     * than [MAXIMUM_AGE_IN_DAYS] old.
     */
    fun reading(resort: Resort, now: Instant = Instant.now()): SnowDepthReading? {
        val massif = resort.avalancheMassif?.let { massifs[it.toString()] } ?: return null
        val observed = massif.current.observationDate ?: return null
        if (ChronoUnit.DAYS.between(observed, now.atZone(ZONE).toLocalDate()) > MAXIMUM_AGE_IN_DAYS) return null
        val levels = resort.forecastLevels.mapNotNull { level ->
            val depth = level.altitude?.let(massif::depth) ?: return@mapNotNull null
            SnowDepthReading.Level(level, depth)
        }
        if (levels.isEmpty()) return null
        return SnowDepthReading(levels, observed, massif.name, massif)
    }

    companion object {
        const val URL = "https://tanukee.com/flake-sight/snow-depth.json"

        /** Where the "Météo-France" credit links to. */
        const val ATTRIBUTION_URL = "https://meteofrance.com"

        /** Out of season the file keeps the last spring bulletins; older than this, in days, they are not shown. */
        const val MAXIMUM_AGE_IN_DAYS = 3

        /** The bulletin gives a massif's figure, read here between its altitudes. */
        const val ROUNDING_STEP = 5

        /** Bulletin dates are French local time. */
        val ZONE: ZoneId = ZoneId.of("Europe/Paris")

        internal val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): SnowDepthReport = json.decodeFromString(text)
    }
}

/** A slope aspect, clockwise from north. Port of `SlopeAspect`; [rawValue] is the file's. */
enum class SlopeAspect(val rawValue: String) {
    NORTH("N"), NORTH_EAST("NE"), EAST("E"), SOUTH_EAST("SE"), SOUTH("S"), SOUTH_WEST("SW"), WEST("W"), NORTH_WEST("NW");

    /** Clockwise from north, in degrees. */
    val degrees: Double get() = ordinal * 45.0

    companion object {
        fun fromRawValue(raw: String?): SlopeAspect? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Fresh snow over 24 hours at one altitude of the massif, as a range: the
 * last days observed, then the coming days forecast. Port of `FreshSnow`.
 */
@Serializable
data class FreshSnow(
    /** Metres. */
    val altitude: Int,
    /** Oldest first. */
    val days: List<Day>,
) {
    @Serializable
    data class Day(
        /** "2026-11-20", French local time. */
        val date: String,
        val minimum: Int,
        val maximum: Int,
        val isForecast: Boolean,
    ) {
        val day: LocalDate? get() = runCatching { LocalDate.parse(date) }.getOrNull()
    }
}

/** The bulletin's mountain forecast, every six hours. Port of `MountainWeather`. */
@Serializable
data class MountainWeather(
    /** Metres, usually 2,000 and 4,000. */
    val windAltitudes: List<Int?>,
    /** Earliest first. */
    val periods: List<Period>,
) {
    @Serializable
    data class Wind(
        /** km/h. */
        val speed: Int,
        /** Where the wind comes from, as the file writes it. */
        val direction: String? = null,
    ) {
        /** A direction the app does not know, such as "Calme", reads as none rather than failing the whole file. */
        val aspect: SlopeAspect? get() = SlopeAspect.fromRawValue(direction)
    }

    @Serializable
    data class Period(
        /** "2026-11-22T06:00+01:00". */
        val time: String,
        /** At [windAltitudes], in that order. */
        val wind: List<Wind?> = emptyList(),
        /** Metres. */
        val freezingLevel: Int? = null,
        /** Metres; below it, rain. */
        val rainSnowLimit: Int? = null,
    ) {
        val date: Instant? get() = bulletinTime(time)
    }

    /** The first period that has not ended yet (each lasts six hours), or the last one when they all have. */
    fun currentPeriod(now: Instant = Instant.now()): Period? =
        periods.firstOrNull { (it.date ?: Instant.MIN) + Duration.ofHours(6) > now } ?: periods.lastOrNull()

    /**
     * The rain-snow limit for each day the periods cover, in [zone], from the
     * period nearest midday, for the mountain profile.
     */
    fun rainSnowLimits(zone: ZoneId): Map<LocalDate, Int> {
        val kept = mutableMapOf<LocalDate, Pair<Int, Int>>()
        for (period in periods) {
            val date = period.date?.atZone(zone) ?: continue
            val limit = period.rainSnowLimit ?: continue
            val held = kept[date.toLocalDate()]
            if (held != null && kotlin.math.abs(held.first - 12) <= kotlin.math.abs(date.hour - 12)) continue
            kept[date.toLocalDate()] = date.hour to limit
        }
        return kept.mapValues { it.value.second }
    }
}

/** A bulletin time, "2026-11-22T06:00+01:00", or `null` when malformed. */
private fun bulletinTime(text: String): Instant? = runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()

/**
 * The avalanche danger level, on the European five-level scale, below and
 * above the altitude where the bulletin splits it. Port of `AvalancheRisk`.
 */
@Serializable
data class AvalancheRisk(
    val below: Int,
    val above: Int,
    /** Metres; `null` when one level applies at every altitude. */
    val boundary: Int? = null,
    val maximum: Int,
    /** The highest level forecast for the next day. */
    val tomorrowMaximum: Int? = null,
) {
    /** The level that applies at [altitude]. */
    fun level(altitude: Int): Int = if (boundary != null && altitude >= boundary) above else below

    /** Whether the level changes with altitude. */
    val isSplit: Boolean get() = boundary != null && below != above

    /** What the resort screen says between two altitudes; the app words it. */
    sealed interface Summary {
        /** "3/5 Considerable". */
        data class Single(val level: Int) : Summary

        /** "4/5 High above 2,200 m, 3/5 Considerable below". */
        data class Split(val above: Int, val boundary: Int, val below: Int) : Summary
    }

    /**
     * Both levels when the boundary falls inside [lowest]..[highest] metres,
     * otherwise the one level that applies there.
     */
    fun summary(lowest: Int?, highest: Int?): Summary {
        val top = highest ?: lowest
        val bottom = lowest ?: highest
        if (isSplit && boundary != null && top != null && bottom != null && bottom < boundary && boundary <= top) {
            return Summary.Split(above, boundary, below)
        }
        return Summary.Single(top?.let(::level) ?: maximum)
    }
}

/**
 * A resort's snow depth from its massif's avalanche bulletin, summit first.
 * Port of `SnowDepthReading`; the wording is the app's.
 */
data class SnowDepthReading(
    /** Summit then village; a level the bulletin cannot reach is left out. */
    val levels: List<Level>,
    /** The day the depths describe, French time. */
    val observedOn: LocalDate,
    val massifName: String,
    /** The whole bulletin, for the snow line, the week's change and the danger level. */
    val bulletin: SnowDepthReport.Massif? = null,
) {
    data class Depth(
        /** Rounded to 5 cm. */
        val centimetres: Int,
        /** The resort is above the bulletin's highest altitude, so the depth there is at least this. */
        val isLowerBound: Boolean,
    )

    data class Level(val level: ForecastLevel, val depth: Depth)

    /** The highest level read, usually the summit. */
    val top: Level? get() = levels.firstOrNull()

    /** The change at the top level over the history, in centimetres, and the days it covers. */
    fun change(): Pair<Int, Int>? = top?.level?.altitude?.let { bulletin?.change(it) }

    /** Whether the depth at the top level grew over the week, for the arrow; `null` without a change. */
    val isGrowing: Boolean? get() = change()?.first?.takeIf { it != 0 }?.let { it > 0 }

    private val altitudes: List<Int> get() = levels.mapNotNull { it.level.altitude }

    /** The danger level between the village and the summit, as the resort screen says it. */
    fun avalancheSummary(): AvalancheRisk.Summary? = bulletin?.avalancheRisk?.summary(altitudes.minOrNull(), altitudes.maxOrNull())

    /** The highest danger level between the village and the summit. */
    val avalancheLevel: Int?
        get() {
            val risk = bulletin?.avalancheRisk ?: return null
            return altitudes.maxOfOrNull(risk::level) ?: risk.maximum
        }
}

/**
 * Downloads the snow depth report and keeps the last one received on the
 * device, so the resort rows can show it offline. Port of `SnowDepthLoader`.
 * Any failure keeps the stored report; one that grew old stops being shown by
 * itself ([SnowDepthReport.MAXIMUM_AGE_IN_DAYS]).
 */
class SnowDepthStore(
    private val file: File,
    private val http: HttpClient = UrlConnectionHttpClient,
    private val url: String = SnowDepthReport.URL,
) {
    private val state = MutableStateFlow(runCatching { SnowDepthReport.fromJson(file.readText()) }.getOrNull())

    /** The last report received, or `null` before the first one. */
    val report: StateFlow<SnowDepthReport?> = state.asStateFlow()

    /** Fetches the report and stores it. Returns the new report when it changed, `null` otherwise. */
    suspend fun refresh(): SnowDepthReport? = withContext(Dispatchers.IO) {
        val report = runCatching {
            val response = http.get(url, useCaches = true)
            if (response.statusCode !in 200..299) return@runCatching null
            SnowDepthReport.fromJson(response.body.decodeToString())
        }.getOrNull()
        if (report == null || report == state.value) return@withContext null
        // Shown even if it could not be kept for the next launch.
        runCatching { file.writeTextAtomically(SnowDepthReport.json.encodeToString(report)) }
        state.value = report
        report
    }
}
