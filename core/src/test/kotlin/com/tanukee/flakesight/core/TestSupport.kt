package com.tanukee.flakesight.core

import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import kotlin.test.fail

/** Port of `StubURLProtocol.swift`: answers from canned responses, without a network. */
class StubHttpClient(private val responses: Map<String, HttpResponse>) : HttpClient {
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** How many times [url] was requested, for cache and coalescing tests. */
    fun requestCount(url: String): Int = counts[url] ?: 0

    override fun get(url: String, useCaches: Boolean): HttpResponse {
        counts.merge(url, 1, Int::plus)
        return responses[url] ?: error("No stub for $url")
    }
}

/** A clock tests can move, for cache expiry. */
class TestClock(@Volatile var now: Instant = Instant.ofEpochSecond(1_000_000)) {
    fun advance(by: Duration) {
        now += by
    }
}

/** Stand-in image bytes: the service never decodes them. */
val testImage = ByteArray(64) { it.toByte() }

fun imageResponse(headers: Map<String, String> = emptyMap()) =
    HttpResponse(200, mapOf("Content-Type" to "image/png") + headers, testImage)

fun testWebcam(id: String, name: String = "Cam") = Webcam(
    id = id, resortID = id.substringBefore('.'), name = name, provider = WebcamProviderType.DIRECT_IMAGE,
    sourcePageURL = "https://example.com/$name",
    attribution = WebcamAttribution(providerName = "Test"), capabilities = setOf(WebcamCapability.STATIC_SNAPSHOT),
)

/** Polls until [condition] holds, failing the test after a second. */
suspend fun waitUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + 1_000_000_000
    while (!condition()) {
        if (System.nanoTime() > deadline) fail("Timed out waiting for condition")
        delay(2)
    }
}
