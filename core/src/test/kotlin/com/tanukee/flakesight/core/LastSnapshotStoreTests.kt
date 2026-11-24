package com.tanukee.flakesight.core

import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of `LastSnapshotStoreTests.swift`. Keep the two files in step. */
class LastSnapshotStoreTests {
    private val store = LastSnapshotStore(Files.createTempDirectory("LastSnapshotStoreTests").toFile())
    private val webcam = testWebcam("test.cam")
    private val snapshot = WebcamSnapshot(
        webcamID = "test.cam",
        imageURL = "https://example.com/a.jpg",
        imageData = testImage,
        capturedAt = Instant.ofEpochSecond(1_800_000_000),
        fetchedAt = Instant.ofEpochSecond(1_800_000_600),
    )

    @AfterTest
    fun cleanUp() = store.removeAll()

    @Test
    fun roundTrips() {
        assertTrue(store.save(snapshot, SnapshotSize.THUMBNAIL))
        assertEquals(snapshot, store.snapshot(webcam, SnapshotSize.THUMBNAIL))
        assertNull(store.snapshot(webcam, SnapshotSize.FULL), "Sizes are stored apart")
    }

    @Test
    fun keepsAnUnknownCaptureTimeUnknown() {
        val undated = WebcamSnapshot("test.cam", snapshot.imageURL, snapshot.imageData, null, snapshot.fetchedAt)
        assertTrue(store.save(undated, SnapshotSize.THUMBNAIL))
        assertEquals(undated, store.snapshot(webcam, SnapshotSize.THUMBNAIL))
    }

    @Test
    fun overwritesThePreviousSnapshot() {
        store.save(snapshot, SnapshotSize.THUMBNAIL)
        val newer = WebcamSnapshot(
            "test.cam", "https://example.com/b.jpg", ByteArray(32) { 7 },
            snapshot.capturedAt?.plusSeconds(600), snapshot.fetchedAt.plusSeconds(600),
        )
        store.save(newer, SnapshotSize.THUMBNAIL)
        assertEquals(newer, store.snapshot(webcam, SnapshotSize.THUMBNAIL))
    }

    @Test
    fun missingSnapshotIsNil() {
        assertNull(store.snapshot(webcam, SnapshotSize.THUMBNAIL))
    }

    @Test
    fun corruptMetadataIsNil() {
        store.save(snapshot, SnapshotSize.THUMBNAIL)
        store.directory.listFiles { file -> file.extension == "json" }!!.first().writeText("not json")
        assertNull(store.snapshot(webcam, SnapshotSize.THUMBNAIL))
    }

    @Test
    fun removeAllEmptiesTheStore() {
        store.save(snapshot, SnapshotSize.THUMBNAIL)
        store.removeAll()
        assertNull(store.snapshot(webcam, SnapshotSize.THUMBNAIL))
        assertFalse(store.directory.exists())
    }
}
