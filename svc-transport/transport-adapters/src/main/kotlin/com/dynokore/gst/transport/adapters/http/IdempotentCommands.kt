package com.dynokore.gst.transport.adapters.http

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.IdempotencyOutcome
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * The API-15 key handling of an idempotent command (TPL-04): the command and the key record commit in one
 * transaction. A replay of the same key, operation and body returns the stored 200 and body and runs nothing; the same
 * key with another request is 422, one still running 409. A refusal thrown by the command rolls back and stores nothing.
 * The principal of the key is the actor, never a role (INV-Q-10647).
 */
@Component
class IdempotentCommands(
    private val tx: TransactionRunner,
    private val idempotency: IdempotencyStore,
    private val tenants: TenantContext,
) {
    private val mapper = JsonMapper.builder().build()

    fun <T : Any> once(
        actor: String,
        key: UUID,
        operation: String,
        type: Class<T>,
        command: () -> T,
    ): ResponseEntity<T> {
        val outcome =
            tx.inTransaction {
                idempotency.once(tenants.current(), actor, key, fingerprint(operation)) { _ ->
                    StoredResponse(OK, mapper.writeValueAsString(command()))
                }
            }
        val stored =
            when (outcome) {
                is IdempotencyOutcome.Fresh -> outcome.response
                is IdempotencyOutcome.Replay -> outcome.response
                IdempotencyOutcome.InFlight -> throw ApiRefusal(ClosedErrorCode.IN_FLIGHT)
                IdempotencyOutcome.DifferentRequest -> throw ApiRefusal(ClosedErrorCode.DIFFERENT_REQUEST)
            }
        return ResponseEntity.ok(mapper.readValue(stored.body, type))
    }

    private fun fingerprint(operation: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(operation.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    private companion object {
        const val OK = 200
    }
}
