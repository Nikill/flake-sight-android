package com.tanukee.flakesight.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Webcams, resorts and providers that must no longer be shown, read from a file
 * on tanukee.com so one can be withdrawn (for instance at its owner's request)
 * without an app update. Port of `DisabledContent`.
 *
 * The file only names what is switched off, so resorts added in a later app
 * version need no change to it. Every key is optional:
 *
 * ```json
 * {
 *   "version": 1,
 *   "disabledProviders": ["viewsurf"],
 *   "disabledResorts": ["tignes"],
 *   "disabledWebcams": ["les-saisies.bisanne-1500"]
 * }
 * ```
 *
 * Providers are [WebcamProviderType] raw values.
 */
@Serializable
data class DisabledContent(
    @SerialName("disabledProviders") val providers: Set<String> = emptySet(),
    @SerialName("disabledResorts") val resortIDs: Set<String> = emptySet(),
    @SerialName("disabledWebcams") val webcamIDs: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = providers.isEmpty() && resortIDs.isEmpty() && webcamIDs.isEmpty()

    fun isDisabled(resort: Resort): Boolean = resort.id in resortIDs

    fun isDisabled(webcam: Webcam): Boolean =
        webcam.id in webcamIDs || webcam.resortID in resortIDs || webcam.provider.rawValue in providers

    companion object {
        /** Where the published list lives. */
        const val URL = "https://tanukee.com/flake-sight/disabled.json"

        val NONE = DisabledContent()

        internal val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Downloads the list of withdrawn content and keeps the last one received on
 * the device, where the widget reads it too. Port of `DisabledContentLoader`.
 *
 * The app calls [refresh] each time it opens. Any failure (offline, missing
 * file, malformed JSON) keeps the stored list, so content withdrawn once stays
 * withdrawn and nothing is hidden by accident.
 */
class DisabledContentStore(
    private val file: File,
    private val http: HttpClient = UrlConnectionHttpClient,
    private val url: String = DisabledContent.URL,
) {
    private val state = MutableStateFlow(
        runCatching { DisabledContent.json.decodeFromString<DisabledContent>(file.readText()) }.getOrDefault(DisabledContent.NONE),
    )

    /** The last list received, [DisabledContent.NONE] before the first one. */
    val content: StateFlow<DisabledContent> = state.asStateFlow()

    /** Fetches the list and stores it. Returns `true` if it changed, so the caller can rebuild what it shows. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val content = runCatching {
            val response = http.get(url, useCaches = false)
            if (response.statusCode !in 200..299) return@runCatching null
            DisabledContent.json.decodeFromString<DisabledContent>(response.body.decodeToString())
        }.getOrNull()
        if (content == null || content == state.value) return@withContext false
        // Applied even if it could not be kept for the next launch.
        runCatching { file.writeTextAtomically(DisabledContent.json.encodeToString(content)) }
        state.value = content
        true
    }
}

/**
 * The bundled catalogue without what [disabled] withdrew: drops disabled
 * resorts, and disabled webcams from the others. A resort left without webcams
 * stays, for its forecast. Every surface that shows the catalogue starts from this.
 */
fun ResortRepository.removing(disabled: DisabledContent): ResortRepository {
    if (disabled.isEmpty) return this
    return ResortRepository(
        resorts.filterNot(disabled::isDisabled).map { resort -> resort.copy(webcams = resort.webcams.filterNot(disabled::isDisabled)) },
    )
}
