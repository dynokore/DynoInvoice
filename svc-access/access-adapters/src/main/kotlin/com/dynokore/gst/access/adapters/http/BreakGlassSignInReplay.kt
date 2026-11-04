package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.breakglass.SignInWithBackoffOutcome
import com.dynokore.gst.access.application.breakglass.SignInWithBackoffUseCase
import com.dynokore.gst.access.contract.model.BreakGlassSignInRequest
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.IdempotencyOutcome
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * API-15, INV-Q-16785 A. `signInBreakGlass` under its Idempotency-Key: the sign-in (and with it the back-off counter,
 * the alert and the audit entry) and the key record commit in one transaction, keyed by (tenant, actor, key). A repeat
 * of the same key with the same request returns the stored reply and counts nothing again; the same key with another
 * request is 422 and one still running is 409. [render] turns the outcome into the stored reply; it throws for the
 * waiting back-off, which therefore stores nothing and rolls back nothing that was written.
 */
class BreakGlassSignInReplay(
    private val tx: TransactionRunner,
    private val idempotency: IdempotencyStore,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) {
    @Suppress("LongParameterList") // the identity of the command plus the two steps of the pattern
    fun once(
        signIn: SignInWithBackoffUseCase,
        actor: String,
        unlockRef: String,
        key: UUID,
        request: BreakGlassSignInRequest,
        render: (SignInWithBackoffOutcome) -> StoredResponse,
    ): StoredResponse {
        val fingerprint = fingerprint(unlockRef, mapper.writeValueAsBytes(request))
        val outcome =
            tx.inTransaction {
                idempotency.once(tenants.current(), actor, key, fingerprint) { _ ->
                    render(signIn.handle(actor, unlockRef, request.sealingCredential))
                }
            }
        return when (outcome) {
            is IdempotencyOutcome.Fresh -> outcome.response
            is IdempotencyOutcome.Replay -> outcome.response
            IdempotencyOutcome.InFlight -> throw ApiRefusal(ClosedErrorCode.IN_FLIGHT)
            IdempotencyOutcome.DifferentRequest -> throw ApiRefusal(ClosedErrorCode.DIFFERENT_REQUEST)
        }
    }

    /** SHA-256 hex of `POST signInBreakGlass <unlockRef>`, a line feed and the request body bytes. */
    private fun fingerprint(
        unlockRef: String,
        body: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("POST signInBreakGlass $unlockRef\n".toByteArray(StandardCharsets.UTF_8))
        digest.update(body)
        return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }
}
