package com.dynokore.gst.access.application.link

import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.platform.tenancykernel.TenantId
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The claims of a link-session token (INV-REQ-899, ruling option A of the owner, INV-AC-17809): `sub` is `link:` plus
 * the linkRef, `tenant_id` the link's tenant. No `act`, no `scope`, no `aud`, no role and no permission.
 */
data class LinkSessionClaims(
    val sub: String,
    val tenantId: String,
    val iat: Instant,
    val exp: Instant,
    val jti: String,
)

/** A minted link session: the compact token and the instant it ends. */
data class LinkSession(
    val claims: LinkSessionClaims,
    val token: String,
    val expiresAt: Instant,
)

/**
 * Port: signs [LinkSessionClaims] with the signing key of use `LINK_SESSION` (one key, one use; NIST SP 800-57) as a
 * compact JWS whose header `typ` is `link-session+jwt` (RFC 8725 section 3.11), and returns the compact token.
 */
fun interface LinkSessionSigner {
    fun sign(claims: LinkSessionClaims): String
}

/**
 * Mints the short-lived end-user token of a verified signed link. Only a verified outcome of a usable, unexpired link
 * gives a session; every other case gives null and the signer is not called. `exp` is the earlier of the link's expiry
 * and `iat` plus 15 minutes. It reads no role name and no permission (INV-Q-10027).
 */
class LinkSessionIssuer(
    private val tenants: LinkTenants,
    private val signer: LinkSessionSigner,
    private val random: SecureRandom = SecureRandom(),
) {
    fun sessionFor(
        link: SignedLink,
        outcome: VerifyLinkOutcome,
        now: Instant,
    ): LinkSession? {
        val eligible =
            outcome is VerifyLinkOutcome.Verified && outcome.ref == link.ref && link.usable &&
                now.isBefore(link.expiresAt)
        val tenant = if (eligible) tenants.tenantOf(link.ref) else null
        return tenant?.let { mint(link, it, now) }
    }

    private fun mint(
        link: SignedLink,
        tenant: TenantId,
        now: Instant,
    ): LinkSession {
        val ceiling = now.plus(MAX_LIFETIME)
        val exp = if (link.expiresAt.isBefore(ceiling)) link.expiresAt else ceiling
        val claims =
            LinkSessionClaims(
                sub = VerifySignatoryLinkUseCase.actorOf(link.ref),
                tenantId = tenant.toString(),
                iat = now,
                exp = exp,
                jti = newJti(),
            )
        return LinkSession(claims, signer.sign(claims), exp)
    }

    private fun newJti(): String {
        val bytes = ByteArray(JTI_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        /** The 15-minute ceiling of operator sessions (API-23), applied as the short-lived bound. */
        val MAX_LIFETIME: Duration = Duration.ofMinutes(15)
        const val JTI_BYTES = 16
    }
}
