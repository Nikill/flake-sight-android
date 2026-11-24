package com.tanukee.flakesight.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * A resort the user marked as favourite. Port of `FavoriteResort` in SkiPatrolKit.
 *
 * Stored apart from the catalogue and keyed by [Resort.id], so catalogue
 * updates never lose user choices. At most one favourite is the default; it
 * will drive the widgets, Wear OS and Android Auto.
 */
@Serializable
data class FavoriteResort(
    val resortID: String,
    val isDefault: Boolean = false,
    @Serializable(with = InstantSerializer::class) val addedAt: Instant,
)

/**
 * Reads and changes favourite resorts while keeping the "at most one default"
 * rule. Port of `FavoritesStore` in SkiPatrolKit:
 * - the first favourite automatically becomes the default;
 * - setting a default also makes the resort a favourite;
 * - removing the default promotes the oldest remaining favourite.
 *
 * Kept in a small JSON file on the device, never sent anywhere. A change is
 * published only once written, and a failed write throws [IOException] and
 * changes nothing, so the screen never disagrees with what is stored.
 */
class FavoritesStore(private val file: File, private val now: () -> Instant = Instant::now) {
    private val state = MutableStateFlow(load())

    /** Favourites, oldest first, updated after each saved change. */
    val favorites: StateFlow<List<FavoriteResort>> = state.asStateFlow()

    fun isFavorite(resortID: String): Boolean = state.value.any { it.resortID == resortID }

    fun defaultResortID(): String? = state.value.firstOrNull { it.isDefault }?.resortID

    @Synchronized
    fun toggleFavorite(resortID: String) {
        val current = state.value
        val existing = current.firstOrNull { it.resortID == resortID }
        save(
            if (existing != null) {
                val remaining = current - existing
                if (existing.isDefault && remaining.isNotEmpty()) {
                    listOf(remaining.first().copy(isDefault = true)) + remaining.drop(1)
                } else {
                    remaining
                }
            } else {
                current + FavoriteResort(resortID, isDefault = current.isEmpty(), addedAt = now())
            },
        )
    }

    @Synchronized
    fun setDefault(resortID: String) {
        val current = state.value
        val withTarget = if (current.any { it.resortID == resortID }) current else current + FavoriteResort(resortID, addedAt = now())
        save(withTarget.map { it.copy(isDefault = it.resortID == resortID) })
    }

    private fun save(favorites: List<FavoriteResort>) {
        val sorted = favorites.sortedWith(oldestFirst)
        // Written beside the file then moved over it, so a crash mid-write never leaves half a file.
        val temporary = File(file.absoluteFile.parentFile, "${file.name}.tmp")
        try {
            temporary.writeText(json.encodeToString(sorted))
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            temporary.delete()
            throw e as? IOException ?: IOException(e)
        }
        state.value = sorted
    }

    /**
     * The stored favourites. A missing file is no favourites; so is an
     * unreadable one, which the next change then replaces.
     */
    private fun load(): List<FavoriteResort> = runCatching {
        json.decodeFromString<List<FavoriteResort>>(file.readText()).sortedWith(oldestFirst)
    }.getOrDefault(emptyList())

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val oldestFirst = compareBy<FavoriteResort>({ it.addedAt }, { it.resortID })
    }
}

internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}
