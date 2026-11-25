package com.tanukee.flakesight.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The same webcams with [webcamID] first and the others in their order. An
 * unknown or withdrawn ID (not in the list) leaves the order as is. Port of
 * `Array.featuring(_:)`.
 */
fun List<Webcam>.featuring(webcamID: String?): List<Webcam> {
    val featured = firstOrNull { it.id == webcamID } ?: return this
    return listOf(featured) + (this - featured)
}

/**
 * The webcam the user put at the top of each resort's screen, at most one per
 * resort, favourite or not. Port of `FeaturedWebcam` and `FeaturedWebcamStore`:
 * a JSON map from resort ID to webcam ID beside the favourites file instead of
 * SwiftData, keyed by IDs so catalogue updates never lose the choice.
 *
 * As [FavoritesStore], a change is published only once written, and a failed
 * write throws [IOException] and changes nothing.
 */
class FeaturedWebcamStore(private val file: File) {
    private val state = MutableStateFlow(
        runCatching { json.decodeFromString<Map<String, String>>(file.readText()) }.getOrDefault(emptyMap()),
    )

    /** Every resort's chosen webcam, updated after each saved change. */
    val all: StateFlow<Map<String, String>> = state.asStateFlow()

    /** The chosen webcam of [resortID], `null` when none was chosen. */
    fun featuredWebcamID(resortID: String): String? = state.value[resortID]

    /** Puts [webcamID] at the top of [resortID]'s screen, replacing any earlier choice. */
    @Synchronized
    fun feature(webcamID: String, resortID: String) = save(state.value + (resortID to webcamID))

    /** Back to the screen's own order for [resortID]. */
    @Synchronized
    fun clear(resortID: String) {
        if (resortID in state.value) save(state.value - resortID)
    }

    private fun save(choices: Map<String, String>) {
        file.writeTextAtomically(json.encodeToString(choices))
        state.value = choices
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
