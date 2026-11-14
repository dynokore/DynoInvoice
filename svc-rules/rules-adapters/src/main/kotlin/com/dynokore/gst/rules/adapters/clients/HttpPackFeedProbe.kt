package com.dynokore.gst.rules.adapters.clients

import com.dynokore.gst.rules.application.PackFeedProbe
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Asks the control-plane pack feed for its list of packs (`GET /packs`) and answers whether it replied with a success
 * status. Every failure (refused connection, timeout, error status) is "not reachable": nothing is written and
 * nothing is thrown (INV-REQ-1075). The answer's body is not read: the data plane receives packs only by import.
 */
class HttpPackFeedProbe(
    baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(5),
) : PackFeedProbe {
    private val uri = URI.create(baseUrl.trimEnd('/') + "/packs")
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    override fun reachable(): Boolean =
        try {
            val request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build()
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
        } catch (_: java.io.IOException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
}
