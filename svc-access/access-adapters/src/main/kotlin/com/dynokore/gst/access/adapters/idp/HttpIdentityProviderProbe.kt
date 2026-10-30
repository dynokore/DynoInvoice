package com.dynokore.gst.access.adapters.idp

import com.dynokore.gst.access.application.idp.IdentityProviderProbe
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The HTTP adapter of [IdentityProviderProbe] (INV-Q-9111 C): fetches the discovery document of the issuer and the
 * key set at [jwkSetUri], or at the `jwks_uri` of the discovery document when [jwkSetUri] is null, each within
 * [timeout]. It succeeds only when both answer 200 with a parseable JSON body; any other outcome, a timeout
 * included, is a failure.
 */
class HttpIdentityProviderProbe(
    issuerUri: String,
    private val jwkSetUri: String? = null,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build(),
) : IdentityProviderProbe {
    private val discoveryUri = issuerUri.trimEnd('/') + DISCOVERY_PATH
    private val mapper = JsonMapper.builder().build()

    override fun probe(): Boolean =
        try {
            val discovery = fetch(discoveryUri)
            val keySetUri = jwkSetUri ?: discovery.path("jwks_uri").asString("")
            keySetUri.isNotBlank() && fetch(keySetUri) != null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            false
        }

    private fun fetch(uri: String) =
        http
            .sendAsync(
                HttpRequest
                    .newBuilder(URI.create(uri))
                    .timeout(timeout)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .get()
            .also { check(it.statusCode() == HTTP_OK) { "the identity provider answered ${it.statusCode()}" } }
            .let { mapper.readTree(it.body()) }

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private const val DISCOVERY_PATH = "/.well-known/openid-configuration"
        private const val HTTP_OK = 200
    }
}
