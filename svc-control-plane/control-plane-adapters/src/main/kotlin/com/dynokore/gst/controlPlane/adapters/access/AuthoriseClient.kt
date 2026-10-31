package com.dynokore.gst.controlPlane.adapters.access

import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenSource
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit

/** The answer of access-svc `authorise` to one question: the `decision` is ALLOW, and the `role` it names (or null). */
class AuthoriseAnswer(
    val allow: Boolean,
    val role: String?,
)

/**
 * The one HTTP client of the live `authorise` bindings (INV-Q-8687 A, INV-Q-13897 A, INV-Q-13899 A): URL, timeout (the
 * `x-timeout` of `authorise`), the `principalRef` bound, the credentials and the fail-closed handling live here.
 * [ask] returns null for everything but an HTTP 200 with a readable body: a status other than 200, a timeout, an
 * unreadable body, a connection error, an actor above the bound, and a missing credential (no call is made then).
 * Neither credential is logged or put in a message.
 */
class AuthoriseClient(
    private val baseUrl: URI,
    private val workloadToken: WorkloadTokenSource,
    private val authorization: () -> String?,
    private val timeout: Duration = TIMEOUT,
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    private val log = LoggerFactory.getLogger(AuthoriseClient::class.java)
    private val json = JsonMapper.builder().build()

    // Fail closed on anything that goes wrong: the caller gets null, never an exception.
    @Suppress("TooGenericExceptionCaught")
    fun ask(
        actor: String,
        permission: String,
    ): AuthoriseAnswer? =
        try {
            if (actor.length > PRINCIPAL_REF_MAX) null else send(actor, permission)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            log.warn("authorise did not complete: {}", e.javaClass.simpleName)
            null
        }

    private fun send(
        actor: String,
        permission: String,
    ): AuthoriseAnswer? {
        val operator = authorization()?.takeIf { it.isNotBlank() } ?: return null
        val workload: WorkloadToken = workloadToken.current()
        val query = "principalRef=${encode(actor)}&permission=${encode(permission)}&scope=${encode(SCOPE)}"
        val request =
            HttpRequest
                .newBuilder(URI.create(baseUrl.toString().trimEnd('/') + PATH + "?" + query))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header(WorkloadToken.HEADER_NAME, workload.value)
                .header("Authorization", operator)
                .GET()
                .build()
        // The request timeout bounds the answer's headers; the future bounds the whole call, body included.
        val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        val response =
            try {
                future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } finally {
                future.cancel(true)
            }
        if (response.statusCode() != HTTP_OK) return null
        val body = json.readTree(response.body())
        val decision = body.path("decision").asString("")
        return if (decision.isEmpty()) null else AuthoriseAnswer(decision == ALLOW, body.path("role").asString(null))
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        const val PATH = "/internal/v1/access/authorise"
        const val SCOPE = "platform"

        /** The `x-timeout` of `authorise` in the pinned access contract. */
        val TIMEOUT: Duration = Duration.ofMillis(500)
        const val PRINCIPAL_REF_MAX = 64
        private const val HTTP_OK = 200
        private const val ALLOW = "ALLOW"
    }
}
