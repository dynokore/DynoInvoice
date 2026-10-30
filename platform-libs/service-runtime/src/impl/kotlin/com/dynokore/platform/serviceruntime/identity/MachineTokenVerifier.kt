package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jose.JOSEException
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.boot.context.properties.ConfigurationProperties
import java.text.ParseException
import java.time.Clock

/** `service-runtime.machine-identity.*`: the issuer and audience of machine access tokens, as the gateway's `token.issuer` and `token.audience`. */
@ConfigurationProperties(prefix = "service-runtime.machine-identity")
data class MachineIdentityProperties(
    val issuer: String? = null,
    val audience: String? = null,
)

/** The outcome of verifying a machine token for one operation line. */
internal sealed interface MachineVerdict {
    /** 401 SRT-003: the token is not a valid machine access token. */
    data object BadToken : MachineVerdict

    /** 403 SRT-004: a valid token of a scope the operation does not take, or a certification token outside SANDBOX. */
    data object WrongScope : MachineVerdict

    data class Verified(
        val principal: Principal,
    ) : MachineVerdict
}

/**
 * Verifies a machine access token against the machine-access key set of the [authority] replica (coordinator rulings
 * INV-Q-9028 A, INV-Q-9040 A, INV-Q-9032 A; INV-REQ-584, INV-AC-9147/9148/9149). The gateway has verified the token
 * already and forwards it unchanged (INV-REQ-1202 b5); a service checks it again, locally and with no network call.
 * The token passes only when all of these hold:
 *  - header `typ` equals `at+jwt`;
 *  - the signature verifies with the machine key named by `kid`, valid now, and `alg` equals the key's `alg` (an RS, PS
 *    or ES algorithm; `none` and HMAC are refused); an empty machine key set refuses every token;
 *  - `iss` equals the configured issuer and `aud` contains the configured audience (none configured: refused);
 *  - `exp` has not passed and `iat` is present;
 *  - `sub` and `client_id` are the same non-blank string, and `tenant_id` is present for the scopes that need one;
 *  - `scope` is exactly one string and `jti` is a non-blank string (RFC 9068 section 2.2; INV-Q-13630 A).
 * Then the scope is checked against the operation line (403 when it differs): it equals the line's scope, or it is
 * `certification` on a line whose additional principal is `certification`. A `certification` token also needs the
 * claim `environment` equal to `SANDBOX` (exact), on every line.
 */
internal class MachineTokenVerifier(
    private val authority: AuthorityReplica?,
    private val properties: MachineIdentityProperties,
    private val clock: Clock,
) {
    fun verify(
        authorization: String?,
        line: MachineOperation,
    ): MachineVerdict {
        val jwt = parse(authorization) ?: return MachineVerdict.BadToken
        val claims = verifiedClaims(jwt) ?: return MachineVerdict.BadToken
        val scope = claims.claims["scope"] as? String ?: return MachineVerdict.BadToken
        val subject = claims.subject?.takeIf { it.isNotBlank() }
        if (subject == null || claims.claims["client_id"] != subject) return MachineVerdict.BadToken
        val tenant =
            when (val raw = claims.claims["tenant_id"]) {
                null -> {
                    null
                }

                is String -> {
                    runCatching { TenantId.fromClaim(raw) }.getOrNull() ?: return MachineVerdict.BadToken
                }

                else -> {
                    return MachineVerdict.BadToken
                }
            }
        if (tenant == null && scope in TENANT_SCOPES) return MachineVerdict.BadToken
        val allowed = scope == line.scope || (scope == CERTIFICATION && line.also == CERTIFICATION)
        val sandbox = scope != CERTIFICATION || claims.claims["environment"] == SANDBOX
        if (!allowed || !sandbox) return MachineVerdict.WrongScope
        val jti = claims.jwtid?.takeIf { it.isNotBlank() } ?: return MachineVerdict.BadToken
        val coverage = MachineEntityCoverage.of(claims.claims["principal_scope"])
        return MachineVerdict.Verified(
            Principal.machine(
                subject,
                tenant,
                coverage,
                scope,
                jti,
            ),
        )
    }

    private fun parse(authorization: String?): SignedJWT? {
        val parts = authorization?.trim()?.split(' ', limit = 2) ?: return null
        val token = parts.getOrNull(1)?.trim()
        if (token.isNullOrEmpty() || !parts[0].equals("Bearer", ignoreCase = true)) return null
        return try {
            SignedJWT.parse(token)
        } catch (malformed: ParseException) {
            null
        }
    }

    /** The claims once typ, key, algorithm, signature, issuer, audience and lifetime hold; null otherwise. */
    private fun verifiedClaims(jwt: SignedJWT): JWTClaimsSet? {
        val issuer = properties.issuer?.takeIf { it.isNotBlank() }
        val audience = properties.audience?.takeIf { it.isNotBlank() }
        val keys = authority?.machineAccessKeys
        val key =
            jwt.header.keyID
                ?.let { keys?.valid(it) }
                ?.takeIf { jwt.header.algorithm?.name == it.alg }
        val verifier = key?.let { signatureVerifierFor(jwt.header, it) }
        if (issuer == null || audience == null || verifier == null || jwt.header.type?.type != TYP) return null
        val signed =
            try {
                jwt.verify(verifier)
            } catch (expected: JOSEException) {
                false
            }
        val claims =
            try {
                jwt.jwtClaimsSet
            } catch (malformed: ParseException) {
                null
            }
        val live =
            claims?.let { it.expirationTime?.toInstant()?.isAfter(clock.instant()) == true && it.issueTime != null }
        return claims?.takeIf { signed && live == true && it.issuer == issuer && audience in it.audience }
    }

    private companion object {
        const val TYP = "at+jwt"
        const val CERTIFICATION = "certification"
        const val SANDBOX = "SANDBOX"
        val TENANT_SCOPES = setOf("ingest", "filing-workflow", "reporting", CERTIFICATION)
    }
}
