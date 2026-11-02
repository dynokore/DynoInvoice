package com.dynokore.gst.access.adapters.elevation

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.contract.model.DecideElevation200Response
import com.dynokore.gst.access.contract.model.DiagnosticInvitationResponse
import com.dynokore.gst.access.contract.model.ElevationResponse
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.IdempotencyOutcome
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Who sends which command under which Idempotency-Key (API-15). */
data class CommandKey(
    val actor: IdentityRef,
    val key: UUID,
    val operation: String,
)

/** The API-15 key handling of the five elevation operations (TPL-04): command and key record in one transaction. */
@Component
class ElevationCommands(
    private val tx: TransactionRunner,
    private val idempotency: IdempotencyStore,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
) {
    /** What a command answers: the status and the body, or null for a 204. */
    class Reply<T : Any>(
        val status: HttpStatus,
        val body: T?,
    )

    /**
     * API-15, TPL-04: the command and the key record commit in one transaction. A replay of the same key with the same
     * method, operation and body returns the stored status and body; the same key with another one is 422, one still
     * running is 409. A refusal thrown by [command] rolls back and stores nothing.
     */
    fun <B : Any, T> once(
        id: CommandKey,
        request: B?,
        read: ((String) -> T)?,
        command: () -> Reply<T>,
    ): ResponseEntity<T> where T : Any {
        val fingerprint = fingerprint(id.operation, request?.let { mapper.writeValueAsBytes(it) } ?: ByteArray(0))
        val outcome =
            tx.inTransaction {
                idempotency.once(tenants.current(), id.actor.value, id.key, fingerprint) { _ ->
                    val reply = command()
                    StoredResponse(reply.status.value(), reply.body?.let { mapper.writeValueAsString(it) }.orEmpty())
                }
            }
        val stored =
            when (outcome) {
                is IdempotencyOutcome.Fresh -> outcome.response
                is IdempotencyOutcome.Replay -> outcome.response
                IdempotencyOutcome.InFlight -> throw ApiRefusal(ClosedErrorCode.IN_FLIGHT)
                IdempotencyOutcome.DifferentRequest -> throw ApiRefusal(ClosedErrorCode.DIFFERENT_REQUEST)
            }
        val status = HttpStatus.valueOf(stored.status)
        return if (read == null || stored.body.isEmpty()) {
            ResponseEntity.status(status).build()
        } else {
            ResponseEntity.status(status).body(read(stored.body))
        }
    }

    private fun fingerprint(
        operation: String,
        body: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$operation\n".toByteArray(StandardCharsets.UTF_8))
        digest.update(body)
        return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }

    fun <T : Any> reader(type: Class<T>): (String) -> T = { mapper.readValue(it, type) }

    /** A decision answers the record it settled: an elevation (has a case) or a diagnostic invitation. */
    fun decisionBody(stored: String): DecideElevation200Response =
        if (mapper.readTree(stored).has("caseId")) {
            mapper.readValue(stored, ElevationResponse::class.java)
        } else {
            mapper.readValue(stored, DiagnosticInvitationResponse::class.java)
        }
}
