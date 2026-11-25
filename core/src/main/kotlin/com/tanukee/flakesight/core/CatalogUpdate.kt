package com.tanukee.flakesight.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Resorts published between two releases (decision D20): new resorts, and
 * catalogued ones whose entry changed (a camera added or replaced, a season
 * announced). Downloaded from the same site as [DisabledContent], which stays
 * the only way to take something out. Port of `CatalogUpdate`.
 *
 * ```
 * {
 *   "schema": 1,
 *   "appliesBefore": "1.5",
 *   "resorts": [ { "id": "le-semnoz", ... } ]
 * }
 * ```
 *
 * [appliesBefore] is the first iOS version whose bundled catalogue holds these
 * entries: from that version on, the update is ignored, so a release absorbs it
 * without anyone having to clear the file. Android compares it with
 * [BUNDLED_CATALOG_VERSION], the iOS version its own `ResortCatalog.json` was
 * copied from, since the two apps share the file but not their version numbers.
 */
@Serializable
data class CatalogUpdate(
    val schema: Int = SUPPORTED_SCHEMA,
    val appliesBefore: String,
    val resorts: List<Resort>,
) {
    /** Whether a catalogue copied from iOS [catalogVersion] ("1.4", "1.4.1") should apply it. */
    fun applies(catalogVersion: String): Boolean =
        schema == SUPPORTED_SCHEMA && resorts.isNotEmpty() && compareVersions(catalogVersion, appliesBefore) < 0

    companion object {
        const val URL = "https://tanukee.com/flake-sight/catalog-updates.json"

        /** The layout this version reads. A later layout is ignored whole. */
        const val SUPPORTED_SCHEMA = 1

        /**
         * The iOS marketing version of the commit `ResortCatalog.json` was last
         * copied from. Updated with the catalogue at each sync (see IOS_SYNC.md).
         */
        const val BUNDLED_CATALOG_VERSION = "1.2"

        val NONE = CatalogUpdate(appliesBefore = "0", resorts = emptyList())

        internal val json = Json { ignoreUnknownKeys = true }

        /** Numeric comparison of dotted versions, like Foundation's `.numeric`: "1.10" comes after "1.5". */
        internal fun compareVersions(a: String, b: String): Int {
            val left = a.split('.').map { it.toIntOrNull() ?: 0 }
            val right = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(left.size, right.size)) {
                val order = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
                if (order != 0) return order
            }
            return 0
        }
    }
}

/**
 * The catalogue with [update] applied, for a catalogue copied from iOS [catalogVersion].
 *
 * A new resort is added and a catalogued one replaced, each only when it passes
 * [CatalogRules]. Webcams on a provider this version does not know are left out
 * first. A replacement must keep every webcam ID the resort had, since widgets
 * are configured by ID: withdrawing a camera is [DisabledContent]'s job.
 */
fun ResortRepository.applying(update: CatalogUpdate, catalogVersion: String = CatalogUpdate.BUNDLED_CATALOG_VERSION): ResortRepository {
    if (!update.applies(catalogVersion)) return this
    val resorts = resorts.toMutableList()
    for (incoming in update.resorts) {
        val resort = incoming.copy(webcams = incoming.webcams.filter { it.provider != WebcamProviderType.UNKNOWN })
        if (CatalogRules.problems(resort).isNotEmpty()) continue
        val index = resorts.indexOfFirst { it.id == resort.id }
        if (index < 0) {
            resorts += resort
        } else {
            val keptIDs = resort.webcams.map { it.id }.toSet()
            if (resorts[index].webcams.all { it.id in keptIDs }) resorts[index] = resort
        }
    }
    return ResortRepository(resorts)
}

/**
 * The catalogue with the last downloaded [update] applied, then without what
 * the last downloaded list [disabled] withdrew, new resorts included. Every
 * surface that shows the catalogue starts from this. Port of `ResortRepository.available()`.
 */
fun ResortRepository.available(
    update: CatalogUpdate,
    disabled: DisabledContent,
    catalogVersion: String = CatalogUpdate.BUNDLED_CATALOG_VERSION,
): ResortRepository = applying(update, catalogVersion).removing(disabled)

/**
 * Downloads the catalogue update and keeps the last one received on the
 * device, where the widget reads it too. Port of `CatalogUpdateLoader`.
 *
 * The app calls [refresh] each time it opens, with the list of withdrawn
 * content. Any failure (offline, missing file, malformed JSON) keeps the stored
 * update, so a resort added once stays until a release absorbs it.
 */
class CatalogUpdateStore(
    private val file: File,
    private val http: HttpClient = UrlConnectionHttpClient,
    private val url: String = CatalogUpdate.URL,
) {
    private val state = MutableStateFlow(
        runCatching { CatalogUpdate.json.decodeFromString<CatalogUpdate>(file.readText()) }.getOrDefault(CatalogUpdate.NONE),
    )

    /** The last update received, [CatalogUpdate.NONE] before the first one. */
    val update: StateFlow<CatalogUpdate> = state.asStateFlow()

    /** Fetches the update and stores it. Returns `true` if it changed, so the caller can rebuild what it shows. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val update = runCatching {
            val response = http.get(url, useCaches = false)
            if (response.statusCode !in 200..299) return@runCatching null
            CatalogUpdate.json.decodeFromString<CatalogUpdate>(response.body.decodeToString())
        }.getOrNull()
        if (update == null || update == state.value) return@withContext false
        // Applied even if it could not be kept for the next launch.
        runCatching { file.writeTextAtomically(CatalogUpdate.json.encodeToString(update)) }
        state.value = update
        true
    }
}
