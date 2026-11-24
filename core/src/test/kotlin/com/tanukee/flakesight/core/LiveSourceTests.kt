package com.tanukee.flakesight.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * Hits the real webcam providers. Disabled by default to avoid network-dependent
 * CI and needless load on providers. Run with:
 * `SKIPATROL_LIVE_TESTS=1 ./gradlew :core:test --tests '*LiveSourceTests' --rerun`
 */
@EnabledIfEnvironmentVariable(named = "SKIPATROL_LIVE_TESTS", matches = "1")
class LiveSourceTests {
    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedWebcams")
    fun thumbnailsAreReachable(webcamID: String) = runBlocking<Unit> {
        val (resort, webcam) = requireNotNull(ResortRepository.bundled().webcam(webcamID))
        val snapshot = WebcamService().latestSnapshot(webcam, resort, SnapshotSize.THUMBNAIL)

        assertTrue(snapshot.imageData.size > 1_000)

        // Freshness is reported, not asserted: some cameras publish no timestamp
        // and some stop updating off-season.
        val capturedAt = snapshot.capturedAt
        val freshness = when {
            capturedAt == null -> "⚠️ no capture date"
            capturedAt < Instant.now() - Duration.ofDays(2) -> "⚠️ stale, captured $capturedAt"
            else -> "captured $capturedAt"
        }
        println("✅ ${webcam.name}: ${snapshot.imageData.size} bytes, $freshness - ${snapshot.imageURL}")
    }

    /** One webcam per archived provider: yesterday's afternoon image is found on storage. */
    @ParameterizedTest
    @ValueSource(strings = ["les-saisies.le-manant", "morzine.grand-conche"])
    fun yesterdaysAfternoonIsArchived(webcamID: String) = runBlocking<Unit> {
        val (resort, webcam) = requireNotNull(ResortRepository.bundled().webcam(webcamID))
        val directory = Files.createTempDirectory("LiveAfternoon").toFile()
        try {
            val service = AfternoonSnapshotService(AfternoonSnapshotStore(directory))
            val now = Instant.now()
            val today = AfternoonWindow.day(now, resort.timeZone)
            val yesterday = AfternoonWindow.recentDays(now, resort.timeZone).first { it != today }
            val snapshot = requireNotNull(service.snapshot(webcam, resort, yesterday)) { "no archived image for $yesterday" }

            assertTrue(snapshot.imageData.size > 1_000)
            assertTrue(AfternoonWindow.accepts(snapshot.capturedAt, resort.timeZone))
            println("✅ ${webcam.name}: ${snapshot.capturedAt}, ${snapshot.imageURL}")
        } finally {
            directory.deleteRecursively()
        }
    }

    companion object {
        /**
         * Webcams whose provider is implemented. Catalogued webcams on providers
         * without an implementation (Panomax today) are skipped.
         */
        @JvmStatic
        fun supportedWebcams(): List<String> {
            val registry = WebcamProviderRegistry.standard(UrlConnectionHttpClient)
            return ResortRepository.bundled().resorts.flatMap { it.webcams }
                .filter { runCatching { registry.provider(it.provider) }.isSuccess }
                .map { it.id }
        }
    }
}
