package com.dynokore.gst.access.application.principal

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.principal.Credential
import com.dynokore.gst.access.domain.principal.CredentialMethod
import com.dynokore.gst.access.domain.principal.CredentialStatus
import com.dynokore.gst.access.domain.principal.PrincipalStatus
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Persistence of the credentials of service principals; every read runs under the bound tenant. */
interface CredentialRepository {
    fun insert(
        principalRef: String,
        credential: Credential,
    )

    fun findByPrincipal(principalRef: String): List<Credential>

    fun find(
        principalRef: String,
        credentialRef: String,
    ): Credential?

    /** Replaces [before] by [after]; throws the platform's StaleAggregate when [before] is no longer current. */
    fun update(
        principalRef: String,
        before: Credential,
        after: Credential,
    )

    fun nextRef(): String
}

/** VP-77 (INV-REQ-2760): whether the installation still allows `client_secret_basic`. */
fun interface ClientSecretPolicy {
    fun clientSecretBasicAllowed(): Boolean
}

/** The cryptographic work on credential material, kept out of the use cases. */
interface CredentialMaterial {
    /** A new random client secret, shown once. */
    fun newSecret(): String

    /** What is stored in place of a secret. */
    fun verifierOf(secret: String): String

    /** True when [json] is a public JWK (no private parameter) that a client assertion can be verified with. */
    fun isPublicJwk(json: String): Boolean

    /** True when [proof] proves possession of the credential for [principalRef] at [at]. */
    fun proves(
        credential: Credential,
        principalRef: String,
        proof: String,
        at: Instant,
    ): Boolean
}

/** The credential fields of a create or rotate request, as the contract carries them. */
data class CredentialRequest(
    val method: CredentialMethod?,
    val publicKeyJwk: String?,
    val certificateThumbprint: String?,
) {
    val isEmpty: Boolean get() = method == null && publicKeyJwk == null && certificateThumbprint == null
}

/** A credential just issued; [clientSecret] is set for CLIENT_SECRET_BASIC and is shown in this one response only. */
data class IssuedCredential(
    val credential: Credential,
    val clientSecret: String?,
)

/**
 * Validates and issues credentials (INV-REQ-880, INV-REQ-584): PRIVATE_KEY_JWT needs a public JWK, MTLS a thumbprint,
 * CLIENT_SECRET_BASIC no key material and VP-77 still allowing it; any other combination is refused.
 */
class CredentialIssuer(
    private val credentials: CredentialRepository,
    private val material: CredentialMaterial,
    private val secretPolicy: ClientSecretPolicy,
) {
    /** The reason the request is invalid, or null. */
    fun refusal(request: CredentialRequest): String? =
        when (request.method) {
            null -> {
                "key material needs a credentialMethod".takeUnless { request.isEmpty }
            }

            CredentialMethod.PRIVATE_KEY_JWT -> {
                when {
                    request.certificateThumbprint != null -> "PRIVATE_KEY_JWT takes no certificateThumbprint"
                    request.publicKeyJwk == null -> "PRIVATE_KEY_JWT needs publicKeyJwk"
                    !material.isPublicJwk(request.publicKeyJwk) -> "publicKeyJwk is not a public key"
                    else -> null
                }
            }

            CredentialMethod.MTLS -> {
                when {
                    request.publicKeyJwk != null -> "MTLS takes no publicKeyJwk"
                    request.certificateThumbprint == null -> "MTLS needs certificateThumbprint"
                    else -> null
                }
            }

            CredentialMethod.CLIENT_SECRET_BASIC -> {
                when {
                    request.publicKeyJwk != null || request.certificateThumbprint != null -> {
                        "CLIENT_SECRET_BASIC takes no key material"
                    }

                    !secretPolicy.clientSecretBasicAllowed() -> {
                        "client_secret_basic is switched off (VP-77)"
                    }

                    else -> {
                        null
                    }
                }
            }
        }

    /** Issues the credential of an already validated [request]; the caller holds the transaction. */
    fun issue(
        principalRef: String,
        request: CredentialRequest,
    ): IssuedCredential {
        val method = checkNotNull(request.method) { "a credential is issued for a validated request" }
        val secret = if (method == CredentialMethod.CLIENT_SECRET_BASIC) material.newSecret() else null
        val credential =
            Credential(
                ref = credentials.nextRef(),
                principalRef = principalRef,
                method = method,
                status = CredentialStatus.ACTIVE,
                validUntil = null,
                publicKeyJwk = request.publicKeyJwk,
                certificateThumbprint = request.certificateThumbprint,
                secretVerifier = secret?.let(material::verifierOf),
                version = 0L,
            )
        credentials.insert(principalRef, credential)
        return IssuedCredential(credential, secret)
    }
}

sealed interface RotatePrincipalOutcome {
    data class Rotated(
        val principal: ServicePrincipal,
        val issued: IssuedCredential?,
        val previous: Credential?,
    ) : RotatePrincipalOutcome

    data class CredentialInvalid(
        val reason: String,
    ) : RotatePrincipalOutcome

    /** A REVOKED principal gets no new credential. */
    data object PrincipalRevoked : RotatePrincipalOutcome

    data object NotFound : RotatePrincipalOutcome
}

/**
 * `rotatePrincipal`: a new credential is issued and every credential that was live ends [overlap] after the rotation,
 * so both authenticate in the window and the old one stops by itself at its end (INV-REQ-880, INV-AC-2044). A request
 * with no credential fields rotates nothing.
 */
class RotatePrincipalUseCase(
    private val tx: TransactionRunner,
    private val principals: PrincipalRepository,
    private val credentials: CredentialRepository,
    private val issuer: CredentialIssuer,
    private val audit: GrantAudit,
    private val clock: Clock,
) {
    fun handle(
        actor: String,
        ref: String,
        overlapMinutes: Int,
        request: CredentialRequest,
    ): RotatePrincipalOutcome =
        tx.inTransaction {
            val principal = principals.lock(ref) ?: return@inTransaction RotatePrincipalOutcome.NotFound
            if (principal.status ==
                PrincipalStatus.REVOKED
            ) {
                return@inTransaction RotatePrincipalOutcome.PrincipalRevoked
            }
            issuer.refusal(request)?.let { return@inTransaction RotatePrincipalOutcome.CredentialInvalid(it) }
            if (request.isEmpty) return@inTransaction RotatePrincipalOutcome.Rotated(principal, null, null)
            val now = clock.instant()
            val end = now.plus(Duration.ofMinutes(overlapMinutes.toLong()))
            val live = credentials.findByPrincipal(ref).filter { it.isLiveAt(now) }
            val issued = issuer.issue(ref, request)
            val previous =
                live.map { old ->
                    // a credential that already ends earlier keeps its earlier end
                    val ends = old.validUntil
                    val limited = if (ends == null || ends.isAfter(end)) old.endingAt(end) else old
                    if (limited !== old) credentials.update(ref, old, limited)
                    limited
                }
            audit.record(
                GrantAuditEntry(actor, "rotatePrincipal", "principal:$ref", null, """{"method":"${request.method}"}"""),
            )
            RotatePrincipalOutcome.Rotated(principal, issued, previous.lastOrNull())
        }
}

sealed interface WithdrawCredentialOutcome {
    data class Withdrawn(
        val principal: ServicePrincipal,
        val credential: Credential,
    ) : WithdrawCredentialOutcome

    data object NotFound : WithdrawCredentialOutcome
}

/** `withdrawCredential`: the credential is refused from then on; a repeat changes nothing and answers the same. */
class WithdrawCredentialUseCase(
    private val tx: TransactionRunner,
    private val principals: PrincipalRepository,
    private val credentials: CredentialRepository,
    private val audit: GrantAudit,
) {
    fun handle(
        actor: String,
        principalRef: String,
        credentialRef: String,
    ): WithdrawCredentialOutcome =
        tx.inTransaction {
            val principal = principals.lock(principalRef) ?: return@inTransaction WithdrawCredentialOutcome.NotFound
            val current =
                credentials.find(principalRef, credentialRef) ?: return@inTransaction WithdrawCredentialOutcome.NotFound
            val after = current.withdrawn()
            if (after !== current) {
                credentials.update(principalRef, current, after)
                audit.record(
                    GrantAuditEntry(actor, "withdrawCredential", "principal:$principalRef", null, credentialRef),
                )
            }
            WithdrawCredentialOutcome.Withdrawn(principal, after)
        }
}

sealed interface AuthenticationDecision {
    data class Authenticated(
        val principal: ServicePrincipal,
        val credentialRef: String,
    ) : AuthenticationDecision

    data class Refused(
        val reasonCode: String,
    ) : AuthenticationDecision
}

/**
 * `authenticatePrincipal`: AUTHENTICATED only when the principal exists in the bound tenant, is not REVOKED, and one
 * of its credentials of the presented method is live now and proves possession. Everything else is REFUSED, an unknown
 * principal included (the operation always answers 200). With VP-77 switched off, CLIENT_SECRET_BASIC is refused.
 */
class AuthenticatePrincipalUseCase(
    private val tx: TransactionRunner,
    private val principals: PrincipalRepository,
    private val credentials: CredentialRepository,
    private val material: CredentialMaterial,
    private val secretPolicy: ClientSecretPolicy,
    private val clock: Clock,
) {
    fun handle(
        principalRef: String,
        method: CredentialMethod,
        proof: String,
    ): AuthenticationDecision =
        tx.inTransaction {
            val principal =
                principals.find(principalRef) ?: return@inTransaction AuthenticationDecision.Refused(UNKNOWN)
            val now = clock.instant()
            when {
                principal.status != PrincipalStatus.ACTIVE -> {
                    AuthenticationDecision.Refused(REVOKED)
                }

                method == CredentialMethod.CLIENT_SECRET_BASIC && !secretPolicy.clientSecretBasicAllowed() -> {
                    AuthenticationDecision.Refused(METHOD_OFF)
                }

                else -> {
                    credentials
                        .findByPrincipal(principalRef)
                        .firstOrNull {
                            it.method == method && it.isLiveAt(now) &&
                                material.proves(it, principalRef, proof, now)
                        }?.let { AuthenticationDecision.Authenticated(principal, it.ref) }
                        ?: AuthenticationDecision.Refused(NO_CREDENTIAL)
                }
            }
        }

    companion object {
        const val UNKNOWN = "UNKNOWN"
        const val REVOKED = "REVOKED"
        const val METHOD_OFF = "METHOD_OFF"
        const val NO_CREDENTIAL = "NO_CREDENTIAL"
    }
}
