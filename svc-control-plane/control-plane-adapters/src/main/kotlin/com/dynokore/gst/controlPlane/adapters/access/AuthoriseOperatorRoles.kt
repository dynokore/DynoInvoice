package com.dynokore.gst.controlPlane.adapters.access

import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
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

/**
 * The [OperatorRoles] port over the live access-svc `authorise` query (INV-Q-8687 A, INV-Q-8723 A): for each
 * permission key of the role, `GET /internal/v1/access/authorise?principalRef=<actor>&permission=<key>&scope=platform`.
 * A role is held only if every key answers HTTP 200, `decision` ALLOW and a `role` equal to the role asked. DENY, any
 * other status, a timeout, an unreadable body, a connection error and an actor above the `principalRef` bound all give
 * false; no exception reaches the caller.
 */
class AuthoriseOperatorRoles(
    private val baseUrl: URI,
    private val timeout: Duration = TIMEOUT,
    private val client: HttpClient = HttpClient.newHttpClient(),
) : OperatorRoles {
    private val log = LoggerFactory.getLogger(AuthoriseOperatorRoles::class.java)
    private val json = JsonMapper.builder().build()

    override fun holds(
        actor: String,
        role: String,
    ): Boolean {
        val keys = ROLE_PERMISSIONS[role]
        return keys != null && actor.length <= PRINCIPAL_REF_MAX && decide(actor, role, keys)
    }

    // Fail closed on anything that goes wrong: the caller gets false, never an exception.
    @Suppress("TooGenericExceptionCaught")
    private fun decide(
        actor: String,
        role: String,
        keys: List<String>,
    ): Boolean =
        try {
            keys.all { allows(actor, it, role) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            log.warn("authorise did not complete: {}", e.javaClass.simpleName)
            false
        }

    private fun allows(
        actor: String,
        permission: String,
        role: String,
    ): Boolean {
        val query =
            "principalRef=${encode(actor)}&permission=${encode(permission)}&scope=${encode(SCOPE)}"
        val request =
            HttpRequest
                .newBuilder(URI.create(baseUrl.toString().trimEnd('/') + PATH + "?" + query))
                .timeout(timeout)
                .header("Accept", "application/json")
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
        if (response.statusCode() != HTTP_OK) return false
        val body = json.readTree(response.body())
        return body.path("decision").asString("") == ALLOW && body.path("role").asString("") == role
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        const val PATH = "/internal/v1/access/authorise"
        const val SCOPE = "platform"

        /** The `x-timeout` of `authorise` in the pinned access contract. */
        val TIMEOUT: Duration = Duration.ofMillis(500)

        /** Catalogue keys per operator role, compared by equality (INV-Q-8687 A, INV-Q-9779 A, INV-Q-11433 A). */
        val ROLE_PERMISSIONS: Map<String, List<String>> =
            mapOf(
                "commercial.admin" to listOf("plan.manage", "entitlement.manage", "licence.manage"),
                "release.manager" to listOf("release-train.certify", "release-train.publish"),
                "regulatory.author" to listOf("regulatory-pack.author"),
                "regulatory.approver" to listOf("regulatory-pack.approve"),
                "platform.engineer" to listOf("audit-entry.purge"),
            )
        private const val PRINCIPAL_REF_MAX = 64
        private const val HTTP_OK = 200
        private const val ALLOW = "ALLOW"
    }
}
