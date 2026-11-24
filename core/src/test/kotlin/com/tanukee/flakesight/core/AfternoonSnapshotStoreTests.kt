package com.tanukee.flakesight.core

import com.tanukee.flakesight.core.AfternoonWindowTests.Companion.paris
import com.tanukee.flakesight.core.AfternoonWindowTests.Companion.thursday
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of `AfternoonSnapshotStoreTests.swift`. Downscaling decodes images,
 * which is the app's (Android's decoder), so the two downscaling tests check
 * that the store keeps what its downscaler gives and nothing when it refuses.
 * Keep the two files in step.
 */
class AfternoonSnapshotStoreTests {
    private val directory = Files.createTempDirectory("AfternoonSnapshotStoreTests").toFile()
    private val store = AfternoonSnapshotStore(directory)

    private fun snapshot(capturedAt: Instant?, id: String = "test.cam", name: String = "a", data: ByteArray = testImage) =
        WebcamSnapshot(id, "https://example.com/$name.jpg", data, capturedAt, capturedAt ?: Instant.now())

    @AfterTest
    fun cleanUp() = store.removeAll()

    @Test
    fun keepsAnAfternoonImage() {
        val image = snapshot(AfternoonWindowTests.paris(29, 15, 50))
        assertTrue(store.offer(image, paris))

        val stored = assertNotNull(store.snapshot("test.cam", thursday))
        assertEquals(thursday, stored.day)
        assertEquals(image.capturedAt, stored.capturedAt)
        assertEquals(image.imageURL, stored.imageURL)
        assertTrue(image.imageData.contentEquals(stored.imageData))
        assertEquals(listOf(thursday), store.days("test.cam"))
    }

    @Test
    fun ignoresImagesOutsideTheWindowOrUndated() {
        assertFalse(store.offer(snapshot(AfternoonWindowTests.paris(29, 9)), paris))
        assertFalse(store.offer(snapshot(null), paris))
        assertTrue(store.days("test.cam").isEmpty())
    }

    @Test
    fun onlyACloserImageReplacesTheStoredOne() {
        store.offer(snapshot(AfternoonWindowTests.paris(29, 15, 0), name = "first"), paris)
        assertTrue(store.offer(snapshot(AfternoonWindowTests.paris(29, 16, 10), name = "closer"), paris))
        assertFalse(store.offer(snapshot(AfternoonWindowTests.paris(29, 16, 40), name = "further"), paris))
        assertTrue(assertNotNull(store.snapshot("test.cam", thursday)).imageURL.endsWith("/closer.jpg"))
    }

    @Test
    fun keepsOneImagePerDayNewestFirst() {
        store.offer(snapshot(AfternoonWindowTests.paris(27, 16)), paris)
        store.offer(snapshot(AfternoonWindowTests.paris(29, 16)), paris)
        store.offer(snapshot(AfternoonWindowTests.paris(28, 16)), paris)
        assertEquals(listOf(29, 28, 27), store.days("test.cam").map { it.dayOfMonth })
    }

    @Test
    fun pruneDropsOldDaysAndFormerFavourites() {
        store.offer(snapshot(AfternoonWindowTests.paris(20, 16)), paris)
        store.offer(snapshot(AfternoonWindowTests.paris(29, 16)), paris)
        store.offer(snapshot(AfternoonWindowTests.paris(29, 16), id = "other.cam"), paris)

        store.prune(setOf(thursday), setOf("test.cam"))

        assertEquals(listOf(thursday), store.days("test.cam"))
        assertTrue(store.days("other.cam").isEmpty())
        assertNull(store.snapshot("test.cam", LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun corruptMetadataIsIgnored() {
        store.offer(snapshot(AfternoonWindowTests.paris(29, 16)), paris)
        File(directory, "test.cam/2026-10-29.json").writeText("not json")
        assertNull(store.snapshot("test.cam", thursday))
        assertTrue(store.offer(snapshot(AfternoonWindowTests.paris(29, 17)), paris), "A broken entry can be replaced")
    }

    @Test
    fun largeImagesAreDownscaled() {
        val halving = AfternoonSnapshotStore(directory) { it.copyOf(it.size / 2) }
        assertTrue(halving.offer(snapshot(AfternoonWindowTests.paris(29, 16)), paris))
        assertEquals(testImage.size / 2, assertNotNull(halving.snapshot("test.cam", thursday)).imageData.size)
    }

    @Test
    fun rejectsDataThatIsNotAnImage() {
        val decoding = AfternoonSnapshotStore(directory) { data -> data.takeUnless { it.decodeToString().startsWith("<html>") } }
        assertFalse(decoding.offer(snapshot(AfternoonWindowTests.paris(29, 16), data = "<html>".encodeToByteArray()), paris))
    }
}
