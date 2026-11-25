package com.tanukee.flakesight.core

/**
 * What a catalogued resort must satisfy, whether it ships in the bundled
 * catalogue or arrives in a [CatalogUpdate]. `CatalogTests` runs these rules on
 * the bundled file; the update is checked against them on the device, and a
 * resort that breaks one is ignored rather than shown with a typo. Port of
 * `CatalogRules`.
 */
object CatalogRules {
    /**
     * The French Alps (Drôme's Vercors included), the French Pyrenees, the
     * Jura, the Vosges and the Massif Central (decision D18). Catches swapped
     * or mistyped coordinates.
     */
    val mountainRanges: List<Triple<String, ClosedFloatingPointRange<Double>, ClosedFloatingPointRange<Double>>> = listOf(
        Triple("French Alps", 43.7..46.5, 5.0..7.7),
        Triple("French Pyrenees", 42.3..43.2, -1.8..3.2),
        Triple("Jura", 46.2..47.1, 5.7..6.6),
        Triple("Vosges", 47.6..48.5, 6.7..7.4),
        Triple("Massif Central", 44.0..45.9, 2.4..4.5),
    )

    /** Départements of the Alps and the Pyrenees, all inside Météo-France's avalanche bulletin massifs. */
    val regionsWithBulletin: Set<String> = setOf(
        "Alpes-Maritimes", "Alpes-de-Haute-Provence", "Drôme", "Haute-Savoie", "Hautes-Alpes", "Isère", "Savoie",
        "Ariège", "Aude", "Haute-Garonne", "Hautes-Pyrénées", "Pyrénées-Atlantiques", "Pyrénées-Orientales",
    )

    /** Départements outside every Météo-France avalanche bulletin massif. */
    val regionsWithoutBulletin: Set<String> = setOf(
        "Ain", "Doubs", "Jura",
        "Vosges", "Haut-Rhin", "Bas-Rhin", "Territoire de Belfort", "Haute-Saône",
        "Puy-de-Dôme", "Cantal", "Loire", "Haute-Loire", "Ardèche", "Aveyron", "Lozère", "Gard",
    )

    /** Resorts catalogued before the webcam cap, which keep their views. */
    internal val resortsAboveTheCap: Set<String> = setOf("les-saisies", "les-7-laux")

    /** Everything wrong with [resort], in words; empty when it is fit to show. */
    fun problems(resort: Resort): List<String> {
        val problems = mutableListOf<String>()
        fun check(condition: Boolean, problem: () -> String) {
            if (!condition) problems += "${resort.id}: ${problem()}"
        }

        check(resort.country == "FR") { "country ${resort.country}" }
        check(mountainRanges.any { (_, latitude, longitude) -> resort.latitude in latitude && resort.longitude in longitude }) {
            "outside the French mountains"
        }
        check(resort.region in regionsWithBulletin || resort.region in regionsWithoutBulletin) { "region ${resort.region}" }

        // The massif picks the bulletin the snow depth is read from, so a typo
        // would give a resort another valley's snow. The Jura box overlaps the
        // Alps one, so the rule follows the département.
        resort.avalancheMassif?.let { massif ->
            val alps = 1..23
            val pyrenees = 64..74
            check(resort.region !in regionsWithoutBulletin) { "massif $massif outside every bulletin massif" }
            val inPyrenees = resort.latitude < 43.3
            check(if (inPyrenees) massif in pyrenees else massif in alps) { "massif $massif is in the other range" }
        }

        // Altitudes feed the forecast request.
        resort.villageAltitude?.let { check(it in 300..4900) { "village altitude $it" } }
        resort.summitAltitude?.let { check(it in 300..4900) { "summit altitude $it" } }
        val village = resort.villageAltitude
        val summit = resort.summitAltitude
        if (village != null && summit != null) check(summit > village) { "summit below village" }

        // Season dates are typed by hand; a swapped day and month or a wrong
        // year would announce an opening in the summer. Day ranges are
        // guaranteed by `LocalDate`, which refuses an impossible day.
        resort.season?.let { season ->
            val opening = season.opening
            check(opening.monthValue >= 10 || opening.monthValue == 1) { "opens in month ${opening.monthValue}" }
            season.closing?.let { closing ->
                check(closing.monthValue in 3..6) { "closes in month ${closing.monthValue}" }
                val seasonYear = if (opening.monthValue == 1) opening.year else opening.year + 1
                check(closing.year == seasonYear) { "closes in ${closing.year}" }
            }
            check(season.sourceURL.startsWith("https://")) { "season source is not https" }
        }

        // Drivers are sent to the car park.
        resort.parking?.let { parking ->
            val distance = resort.distanceFrom(parking.latitude, parking.longitude)
            check(distance <= 3000) { "car park is ${distance.toInt()} m from the resort centre" }
            check(parking.name?.isNotEmpty() ?: true) { "car park has an empty name" }
        }

        resort.liveVideoURL?.let { url ->
            val uri = runCatching { java.net.URI(url) }.getOrNull()
            check(uri?.scheme == "https" && uri.host == "www.youtube.com") { "live video $url" }
            check(uri?.path?.let { it.startsWith("/@") && it.endsWith("/live") } == true) { "live video $url" }
        }

        check(resort.webcams.map { it.id }.toSet().size == resort.webcams.size) { "duplicate webcam IDs" }
        for (webcam in resort.webcams) {
            check(webcam.resortID == resort.id) { "${webcam.id} belongs to ${webcam.resortID}" }
            check(webcam.id.startsWith("${resort.id}.")) { "${webcam.id} is not prefixed by the resort" }
            check(webcam.provider != WebcamProviderType.UNKNOWN) { "${webcam.id} has an unknown provider" }
            if (webcam.provider == WebcamProviderType.WEBCAM_HD) {
                check(webcam.snapshotURL != null && webcam.thumbnailURL != null) { "${webcam.id} lacks its Webcam HD images" }
            }
        }
        if (resort.id !in resortsAboveTheCap) {
            check(ownViews(resort) <= 4) { "too many webcams of its own" }
            check(resort.webcams.size <= 8) { "too many webcams" }
        }
        return problems
    }

    /**
     * Four views of the resort's own, plus up to two per attached village
     * ("Vaujany - Sommet 2800" on Alpe d'Huez), eight at most. A village view is
     * one whose name starts with one of the resort's alternate names followed by
     * " - ". Beyond two, a village's views count as the resort's own: a sector
     * named the same way ("La Mongie - Village" on Grand Tourmalet) is part of
     * the resort, not an attached village.
     */
    internal fun ownViews(resort: Resort): Int {
        val villages = resort.alternateNames.orEmpty()
        val villageOfEachView = resort.webcams.mapNotNull { webcam ->
            villages.firstOrNull { webcam.name.searchFolded().startsWith(it.searchFolded() + " - ") }
        }
        val villageViews = villageOfEachView.groupingBy { it.searchFolded() }.eachCount().values.sumOf { minOf(it, 2) }
        return resort.webcams.size - villageViews
    }
}
