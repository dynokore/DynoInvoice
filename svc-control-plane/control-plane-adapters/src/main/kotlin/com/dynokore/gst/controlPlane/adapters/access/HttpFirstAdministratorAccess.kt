package com.dynokore.gst.controlPlane.adapters.access

import com.dynokore.gst.controlPlane.application.commercial.FirstAdministratorAccess
import com.dynokore.gst.controlPlane.application.commercial.FirstAdministratorAnswer
import com.dynokore.gst.controlPlane.application.commercial.InvitationCode
import com.dynokore.gst.controlPlane.application.commercial.ProvisionFirstAdministratorCall
import com.dynokore.gst.controlPlane.application.commercial.ReissueFirstAdministratorCall
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenSource
import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * `provisionFirstAdministrator` and `reissueFirstAdministratorInvitation` of access-svc over HTTP
 * (control-plane-svc->access-svc, INV-Q-8185 A, INV-Q-8232 A). The call carries the workload token of this service
 * and no end-user token. 2xx with a readable body is an issued invitation, 409 a refusal, 503 and a failure to get
 * an answer (timeout, I/O failure) are unavailable, and any other status or an unreadable body is failed
 * (INV-Q-16204 A). The response body, which holds
 * the invitation code, is never logged.
 */
class HttpFirstAdministratorAccess(
    private val baseUrl: URI,
    private val workloadToken: WorkloadTokenSource,
    private val timeout: Duration = TIMEOUT,
    private val client: HttpClient = HttpClient.newHttpClient(),
) : FirstAdministratorAccess {
    private val log = LoggerFactory.getLogger(HttpFirstAdministratorAccess::class.java)
    private val json = JsonMapper.builder().build()

    override fun provision(call: ProvisionFirstAdministratorCall) =
        post(
            PROVISION_PATH,
            call.idempotencyKey.toString(),
            linkedMapOf(
                "tenantRef" to call.tenantRef.value,
                "administratorEmail" to call.administratorEmail,
                "invitationExpiresAt" to call.invitationExpiresAt.toString(),
            ),
        )

    override fun reissue(call: ReissueFirstAdministratorCall) =
        post(
            "$PROVISION_PATH/${URLEncoder.encode(call.tenantRef.value, StandardCharsets.UTF_8)}/invitation-reissues",
            call.idempotencyKey.toString(),
            linkedMapOf("invitationExpiresAt" to call.invitationExpiresAt.toString()),
        )

    // Anything that goes wrong is Unavailable: the caller keeps its state and retries with the same key.
    @Suppress("TooGenericExceptionCaught")
    private fun post(
        path: String,
        idempotencyKey: String,
        body: Map<String, String>,
    ): FirstAdministratorAnswer =
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create(baseUrl.toString().trimEnd('/') + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .header(WorkloadToken.HEADER_NAME, workloadToken.current().value)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build()
            answerOf(client.send(request, HttpResponse.BodyHandlers.ofString()))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            FirstAdministratorAnswer.Unavailable
        } catch (e: IOException) {
            log.warn("access-svc first administrator call did not complete: {}", e.javaClass.simpleName)
            FirstAdministratorAnswer.Unavailable
        } catch (e: RuntimeException) {
            log.warn("access-svc first administrator call failed: {}", e.javaClass.simpleName)
            FirstAdministratorAnswer.Unavailable
        }

    private fun answerOf(response: HttpResponse<String>): FirstAdministratorAnswer =
        when (response.statusCode()) {
            HTTP_CONFLICT -> FirstAdministratorAnswer.Refused
            in SUCCESS_FIRST..SUCCESS_LAST -> issuedOf(response.body())
            HTTP_UNAVAILABLE -> FirstAdministratorAnswer.Unavailable
            else -> FirstAdministratorAnswer.Failed
        }

    private fun issuedOf(body: String): FirstAdministratorAnswer {
        // A 2xx the access contract does not define (not JSON, no ref or code) is Failed (INV-Q-16204 A).
        val tree =
            try {
                json.readTree(body)
            } catch (_: JacksonException) {
                return FirstAdministratorAnswer.Failed
            }
        val ref = tree.path("invitationRef").asString("")
        val code = tree.path("invitationCode").asString("")
        return if (ref.isEmpty() || code.isEmpty()) {
            FirstAdministratorAnswer.Failed
        } else {
            FirstAdministratorAnswer.Issued(ref, InvitationCode(code))
        }
    }

    companion object {
        const val PROVISION_PATH = "/internal/v1/access/first-administrators"

        /** The `x-timeout` of both operations in the pinned access contract. */
        val TIMEOUT: Duration = Duration.ofMillis(2000)
        private const val HTTP_CONFLICT = 409
        private const val HTTP_UNAVAILABLE = 503
        private const val SUCCESS_FIRST = 200
        private const val SUCCESS_LAST = 299
    }
}
