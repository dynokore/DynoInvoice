package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.access.api.ElevationApi
import com.dynokore.gst.consoleBff.adapters.clients.access.model.OperatorSessionRequest
import com.dynokore.gst.consoleBff.application.session.AccessAnswer
import com.dynokore.gst.consoleBff.application.session.AccessOperatorSessions
import com.dynokore.gst.consoleBff.application.session.ContentMode
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ARC-08, STK-46, INV-REQ-862 b4, owner ruling INV-Q-4119: the call of access-svc's `openOperatorSession`
 * (`POST /internal/v1/access/operator-sessions`) for one tenant and one case (the contract's `reason` is not sent).
 * The [RestClient] comes from the bean `internalRestClientBuilder`, whose interceptors (service-runtime) set the
 * `Authorization` header to the end user's token of the request being served. This class sets no header except the
 * `Idempotency-Key` of the operation, a fresh random UUID per open.
 *
 * The response is read into a tolerant private shape instead of the generated enum, because the contract marks
 * `contentMode` `x-extensible-enum`: a value that is neither `MASKED` nor `ELEVATED` is stored as `MASKED` (fail
 * closed). The token is never logged and never put in an exception message.
 */
class AccessOperatorSessionClient(
    builder: RestClient.Builder,
    baseUrl: String,
    private val idempotencyKeys: () -> UUID = UUID::randomUUID,
) : AccessOperatorSessions {
    private val restClient = builder.baseUrl(baseUrl).build()

    override fun open(
        tenantRef: String,
        caseId: String,
    ): AccessAnswer {
        val body = OperatorSessionRequest(tenantRef = tenantRef, caseId = caseId)
        return try {
            val reply =
                restClient
                    .post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                    .header(IDEMPOTENCY_KEY, idempotencyKeys().toString())
                    .body(body)
                    .retrieve()
                    .body(Reply::class.java)
            answerOf(reply)
        } catch (e: RestClientResponseException) {
            val status = e.statusCode.value()
            LOG.warn("access-svc openOperatorSession answered status {}", status)
            if (status in CLIENT_ERRORS) AccessAnswer.Refused(status) else AccessAnswer.Failed
        } catch (e: RestClientException) {
            // the message of a deserialisation failure can quote the body, which holds the token: class name only
            LOG.warn("access-svc openOperatorSession failed: {}", e.javaClass.name)
            AccessAnswer.Failed
        }
    }

    private fun answerOf(reply: Reply?): AccessAnswer {
        val bearer = reply?.token
        val expiresAt = reply?.expiresAt?.let(::parseInstant)
        return if (bearer.isNullOrEmpty() || bearer.length > MAX_TOKEN_LENGTH || expiresAt == null) {
            LOG.warn("access-svc openOperatorSession answer unusable")
            AccessAnswer.Failed
        } else {
            AccessAnswer.Opened(bearer, expiresAt.toInstant(), contentModeOf(reply.contentMode))
        }
    }

    private fun parseInstant(text: String): OffsetDateTime? =
        try {
            OffsetDateTime.parse(text)
        } catch (
            @Suppress("SwallowedException") e: DateTimeException,
        ) {
            null
        }

    /** Fail closed: anything but `ELEVATED` is `MASKED`. */
    private fun contentModeOf(value: String?): ContentMode =
        if (value == ContentMode.ELEVATED.name) ContentMode.ELEVATED else ContentMode.MASKED

    @JsonIgnoreProperties(ignoreUnknown = true)
    private class Reply(
        val token: String? = null,
        val expiresAt: String? = null,
        val contentMode: String? = null,
    ) {
        override fun toString(): String = "Reply(contentMode=$contentMode, token=<redacted>)"
    }

    companion object {
        const val URL_PROPERTY = "console.access-svc-url"
        const val IDEMPOTENCY_KEY = "Idempotency-Key"

        /** The contract's `maxLength` of `token`; the table check refuses more. */
        const val MAX_TOKEN_LENGTH = 8192

        /** The path of `openOperatorSession`, read from the generated client so that it cannot drift. */
        val PATH: String =
            ElevationApi(RestClient.create())
                .openOperatorSessionRequestConfig(UUID(0, 0), OperatorSessionRequest("t", "c"))
                .path

        private val CLIENT_ERRORS = 400..499
        private val LOG: Logger = LoggerFactory.getLogger(AccessOperatorSessionClient::class.java)
    }
}
