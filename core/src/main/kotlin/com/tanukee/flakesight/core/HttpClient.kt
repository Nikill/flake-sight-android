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

    /**
     * Blocking. Whether [url] exists without downloading it, for the archive
     * lookups that try several file names. Clients without HEAD fall back to a GET.
     */
    fun head(url: String): HttpResponse = get(url, useCaches = true)
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
object UrlConnectionHttpClient : HttpClient {
    override fun get(url: String, useCaches: Boolean): HttpResponse = request(url, "GET", useCaches)

    override fun head(url: String): HttpResponse = request(url, "HEAD", useCaches = true)

    private fun request(url: String, method: String, useCaches: Boolean): HttpResponse {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            if (!useCaches) connection.setRequestProperty("Cache-Control", "no-cache")
            val status = connection.responseCode
            val headers = connection.headerFields.entries
                .filter { it.key != null }
                .associate { it.key to it.value.joinToString(", ") }
            if (method == "HEAD") return HttpResponse(status, headers, ByteArray(0))
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return HttpResponse(status, headers, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }
}

/** The body of a successful answer, read off the calling thread. */
internal suspend fun HttpClient.fetchBody(url: String): ByteArray {
    val response = withContext(Dispatchers.IO) { get(url, useCaches = true) }
    if (response.statusCode !in 200..299) throw WebcamError.BadStatusCode(response.statusCode)
    return response.body
}
