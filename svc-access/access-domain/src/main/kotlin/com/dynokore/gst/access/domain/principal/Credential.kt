package com.dynokore.gst.access.domain.principal

import java.time.Instant

enum class CredentialMethod { PRIVATE_KEY_JWT, MTLS, CLIENT_SECRET_BASIC }

enum class CredentialStatus { ACTIVE, WITHDRAWN }

/**
 * One credential of a service principal (INV-REQ-880). It carries the material of its method only: the public key of a
 * PRIVATE_KEY_JWT, the thumbprint of an MTLS certificate, the verifier of a CLIENT_SECRET_BASIC secret (never the
 * secret). It authenticates while it is ACTIVE and before [validUntil].
 */
data class Credential(
    val ref: String,
    val principalRef: String,
    val method: CredentialMethod,
    val status: CredentialStatus,
    val validUntil: Instant?,
    val publicKeyJwk: String?,
    val certificateThumbprint: String?,
    val secretVerifier: String?,
    val version: Long,
) {
    init {
        require(ref.isNotBlank() && ref.length <= MAX_REF) { "a credential reference is required, at most $MAX_REF" }
        require(
            (method == CredentialMethod.PRIVATE_KEY_JWT) == (publicKeyJwk != null) &&
                (method == CredentialMethod.MTLS) == (certificateThumbprint != null) &&
                (method == CredentialMethod.CLIENT_SECRET_BASIC) == (secretVerifier != null),
        ) { "a credential carries exactly the material of its method" }
    }

    fun isLiveAt(at: Instant): Boolean =
        status == CredentialStatus.ACTIVE && (validUntil == null || at.isBefore(validUntil))

    fun withdrawn(): Credential =
        if (status ==
            CredentialStatus.WITHDRAWN
        ) {
            this
        } else {
            copy(status = CredentialStatus.WITHDRAWN, version = version + 1)
        }

    fun endingAt(at: Instant): Credential = copy(validUntil = at, version = version + 1)

    private companion object {
        const val MAX_REF = 64
    }
}
