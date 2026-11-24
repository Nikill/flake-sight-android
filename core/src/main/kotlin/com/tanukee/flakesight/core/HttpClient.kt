package com.tanukee.flakesight.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.util.TreeMap

/**
 * The HTTP GET the providers and [WebcamService] need. Stands in for the
 * `URLSession` of SkiPatrolKit, and lets tests answer without a network.
 */
fun interface HttpClient {
    /** Blocking. [useCaches] `false` bypasses the local HTTP cache (explicit refresh). */
    fun get(url: String, useCaches: Boolean): HttpResponse
}

class HttpResponse(
    val statusCode: Int,
    headers: Map<String, String>,
    val body: ByteArray,
) {
    private val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply { putAll(headers) }

    fun header(name: String): String? = headers[name]
}

/**
 * [HttpURLConnection], available on the JVM and on Android. On Android it
 * honours providers' cache headers once the app installs an `HttpResponseCache`.
 */
val UrlConnectionHttpClient = HttpClient { url, useCaches ->
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        if (!useCaches) connection.setRequestProperty("Cache-Control", "no-cache")
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val headers = connection.headerFields.entries
            .filter { it.key != null }
            .associate { it.key to it.value.joinToString(", ") }
        HttpResponse(status, headers, stream?.use { it.readBytes() } ?: ByteArray(0))
    } finally {
        connection.disconnect()
    }
}

/** The body of a successful answer, read off the calling thread. */
internal suspend fun HttpClient.fetchBody(url: String): ByteArray {
    val response = withContext(Dispatchers.IO) { get(url, useCaches = true) }
    if (response.statusCode !in 200..299) throw WebcamError.BadStatusCode(response.statusCode)
    return response.body
}
