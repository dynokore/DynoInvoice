package com.dynokore.gst.access.application.token

import com.dynokore.gst.access.domain.principal.MachinePermissionSets
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import com.dynokore.gst.access.domain.revocation.VerificationKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The settings of the machine access token (owner answer to INV-Q-8809 A). The lifetime default is 900 seconds and
 * 900 is the ceiling: the value may only be tightened (INV-API-4001 sense), and no operation changes it at run time.
 * A value outside the range stops the service at start-up with a message naming the property.
 */
data class TokenSettings(
    val issuer: String,
    val audience: String,
    val lifetime: Duration,
    val keyRotationInterval: Duration,
) {
    init {
        require(issuer.isNotBlank()) { "access.token.issuer is required" }
        require(audience.isNotBlank()) { "access.token.audience is required" }
        require(!lifetime.isNegative && lifetime.seconds in MIN_LIFETIME_SECONDS..MAX_LIFETIME_SECONDS) {
            "access.token.lifetime-seconds must be between $MIN_LIFETIME_SECONDS and $MAX_LIFETIME_SECONDS, " +
                "was ${lifetime.seconds}"
        }
        require(!keyRotationInterval.isNegative && !keyRotationInterval.isZero) {
            "access.token.key-rotation-interval must be positive"
        }
    }

    companion object {
        const val MIN_LIFETIME_SECONDS = 1L
        const val MAX_LIFETIME_SECONDS = 900L
        const val DEFAULT_LIFETIME_SECONDS = 900L
    }
}

/** The claims of one access token; the machine scope is exactly one member (INV-REQ-584). */
data class AccessTokenClaims(
    val issuer: String,
    val subject: String,
    val audience: String,
    val scope: String,
    val principalScope: String,
    val permissions: Set<String>,
    val tenantId: String?,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val tokenId: String,
    /** The environment of the principal's tenant; absent when the principal has no tenant or it is not known. */
    val environment: TenantEnvironment? = null,
)

/** The signing key cannot be used now: unreadable, malformed, or past its signing interval. Fails closed. */
class SigningKeyUnavailable(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The key that signs now, opened once per token. It exposes only the public side: no member returns the secret key
 * bytes, and an implementation redacts the key in `toString()`.
 */
interface SigningSession {
    val kid: String
    val alg: String

    /** DER SubjectPublicKeyInfo as standard base64, the form of `getVerificationKeys`. */
    val publicKey: String

    fun sign(claims: AccessTokenClaims): String
}

/**
 * The signing port (STK-29, CS-24). [open] reads the key and throws [SigningKeyUnavailable] when it cannot; only
 * [SigningSession.sign] signs, so a token that was never going to be issued costs no signing call.
 */
fun interface AccessTokenSigner {
    fun open(): SigningSession
}

/**
 * Publishes the public key of a signing key: the stored row of its `kid` when there is one, else the candidate
 * inserted (platform-bound transaction, INV-Q-8785 A).
 */
fun interface VerificationKeyRegistry {
    fun register(candidate: VerificationKey): VerificationKey
}

data class IssuedToken(
    val accessToken: String,
    val expiresIn: Int,
    val keyId: String,
    val tokenId: String,
)

/**
 * `issueAccessToken` for an authenticated principal. The key signs for [TokenSettings.keyRotationInterval] from the
 * first use of its `kid`; its public key stays published for one token lifetime longer, so a token it signed verifies
 * until its `exp`. A key past its interval is refused (fail closed) until the secret holds a new one.
 */
class IssueAccessTokenUseCase(
    private val signer: AccessTokenSigner,
    private val registry: VerificationKeyRegistry,
    private val sets: MachinePermissionSets,
    private val settings: TokenSettings,
    private val clock: Clock,
    private val newTokenId: () -> String = { UUID.randomUUID().toString() },
) {
    fun issue(
        principal: ServicePrincipal,
        environment: TenantEnvironment? = null,
    ): IssuedToken {
        val now = clock.instant()
        val session = signer.open()
        val published =
            registry.register(
                VerificationKey(
                    kid = session.kid,
                    alg = session.alg,
                    publicKey = session.publicKey,
                    validFrom = now,
                    validUntil = now.plus(settings.keyRotationInterval).plus(settings.lifetime),
                ),
            )
        if (published.publicKey != session.publicKey || published.alg != session.alg) {
            throw SigningKeyUnavailable("the published key of ${session.kid} differs from the signing key")
        }
        if (!now.isBefore(published.validFrom.plus(settings.keyRotationInterval))) {
            throw SigningKeyUnavailable("the signing key ${session.kid} is past its signing interval")
        }
        val tokenId = newTokenId()
        val claims =
            AccessTokenClaims(
                issuer = settings.issuer,
                subject = principal.ref,
                audience = settings.audience,
                scope = scopeOf(principal.type),
                principalScope = principal.scope,
                permissions = sets.permissionsOf(principal.type),
                tenantId = principal.tenant.value.takeUnless { principal.type == PrincipalType.MONITORING },
                issuedAt = now,
                expiresAt = now.plus(settings.lifetime),
                tokenId = tokenId,
                environment = environment,
            )
        return IssuedToken(session.sign(claims), settings.lifetime.seconds.toInt(), session.kid, tokenId)
    }

    /** The machine scope of a principal type as the gateway names it: lower case, hyphenated. */
    private fun scopeOf(type: PrincipalType): String = type.name.lowercase().replace('_', '-')
}
