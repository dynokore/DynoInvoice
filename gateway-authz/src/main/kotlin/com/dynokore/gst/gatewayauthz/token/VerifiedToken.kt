package com.dynokore.gst.gatewayauthz.token

import com.dynokore.gst.gatewayauthz.revocation.TokenIdentity
import java.time.Instant

/**
 * The claims of a token that passed the RFC 9068 checks (coordinator ruling INV-Q-4081): [clientId] is the machine
 * principal's principalRef, [subject] the same value for the client credentials grant, [machineScope] the token's one
 * API-23 machine scope (every token the gateway validates is a machine token, owner ruling INV-Q-4085 A) and [tenantId]
 * the principal's tenant, when it has one;
 * [environment] is the exact `environment` claim (INV-Q-9032 A), null when absent.
 */
data class VerifiedToken(
    val clientId: String?,
    val subject: String,
    val tokenId: String?,
    val issuedAt: Instant,
    val tenantId: String?,
    val machineScope: String,
    val environment: String? = null,
) {
    /** The principal the protective limits count: the OAuth client, or the subject of a token without one. */
    val principal: String get() = clientId ?: subject

    /** A PRINCIPAL entry matches client_id, a SUBJECT entry sub and a TOKEN entry jti (INV-Q-4081, INV-Q-4008). */
    val identity: TokenIdentity get() = TokenIdentity(clientId, subject, tokenId, issuedAt)

    companion object {
        /** The API-23 machine scopes, one per machine principal type; no token combines two. */
        val MACHINE_SCOPES =
            setOf("ingest", "filing-workflow", "ap-callback", "reporting", "monitoring", "certification")
    }
}
