package com.tanukee.flakesight.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate
import java.time.ZoneId

/**
 * A ski resort and the webcams it exposes. Port of `Resort` in SkiPatrolKit.
 *
 * Static catalogue data: user state such as favourites is stored separately and
 * keyed by [id], so the catalogue can be replaced without losing user choices.
 */
@Serializable
data class Resort(
    /** Stable slug, e.g. `"les-saisies"`. Used in deep links. */
    val id: String,
    val name: String,
    /** ISO 3166-1 alpha-2 country code, e.g. `"FR"`. */
    val country: String,
    val region: String,
    val latitude: Double,
    val longitude: Double,
    /** IANA time zone of the resort. Webcam providers usually stamp images in local time. */
    val timeZoneIdentifier: String,
    val websiteURL: String,
    /** Official page listing the resort's webcams. */
    val webcamsPageURL: String? = null,
    /** Other names people search for, e.g. `"Sept Laux"` for Les 7 Laux. */
    val alternateNames: List<String>? = null,
    /** Altitude in metres of the main base the resort markets. */
    val villageAltitude: Int? = null,
    /** Altitude in metres of the highest lift-served point. */
    val summitAltitude: Int? = null,
    /** Météo-France avalanche bulletin massif the resort centre lies in. */
    val avalancheMassif: Int? = null,
    /** The car park drivers are sent to; absent when none is catalogued. */
    val parking: Parking? = null,
    /** The season dates the resort announced; absent when none is published. */
    val season: ResortSeason? = null,
    val webcams: List<Webcam>,
) {
    val timeZone: ZoneId
        get() = runCatching { ZoneId.of(timeZoneIdentifier) }.getOrDefault(ZoneId.systemDefault())

    /**
     * Case- and diacritic-insensitive match on the resort's names, region and,
     * optionally, webcam names, so "bisanne" finds Les Saisies.
     */
    fun matches(searchText: String, includingWebcams: Boolean = true): Boolean {
        val query = searchText.trim()
        if (query.isEmpty()) return true
        val fields = listOf(name, region) + alternateNames.orEmpty() +
            (if (includingWebcams) webcams.map { it.name } else emptyList())
        return fields.any { it.searchContains(query) }
    }

    /** Straight-line distance in metres from a point to the resort centre. */
    fun distanceFrom(latitude: Double, longitude: Double): Double =
        Geo.distanceMetres(this.latitude, this.longitude, latitude, longitude)

    /** Straight-line distance in metres from [location] to the resort centre. */
    fun distanceFrom(location: Coordinate): Double = distanceFrom(location.latitude, location.longitude)

    /** Where driving directions lead: the car park when catalogued, else the resort centre. */
    val drivingDestination: Pair<Double, Double>
        get() = parking?.let { it.latitude to it.longitude } ?: (latitude to longitude)

    fun webcam(withId: String): Webcam? = webcams.firstOrNull { it.id == withId }
}

/** A position, such as the user's: what `CLLocation` gives the iOS sorts. */
data class Coordinate(val latitude: Double, val longitude: Double)

/**
 * The [count] resorts whose centres are closest to [location], nearest first
 * (fewer when there are not enough). The map opens framed on them and the user.
 */
fun Iterable<Resort>.nearest(count: Int, location: Coordinate): List<Resort> =
    sortedBy { it.distanceFrom(location) }.take(maxOf(count, 0))

/** A car park drivers can be sent to. */
@Serializable
data class Parking(
    val latitude: Double,
    val longitude: Double,
    /** As signposted, e.g. "Parking du Chozal". Absent when it has no name. */
    val name: String? = null,
)

/**
 * The winter season dates a resort announced, as catalogued. Days are written
 * `"2026-12-12"` in the JSON and are days in the resort's time zone.
 */
@Serializable
data class ResortSeason(
    @Serializable(with = LocalDateSerializer::class) val opening: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val closing: LocalDate? = null,
    val sourceURL: String,
)

internal object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
}
