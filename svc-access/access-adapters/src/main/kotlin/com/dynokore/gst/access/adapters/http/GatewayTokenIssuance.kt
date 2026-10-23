package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.principal.AuthenticationDecision
import com.dynokore.gst.access.application.token.IssueAccessTokenUseCase
import com.dynokore.gst.access.application.token.IssuedToken
import com.dynokore.gst.access.domain.principal.CredentialMethod

/** The outcome of `issueAccessToken`: a token, or the reason code of the refusal. */
sealed interface TokenDecision {
    data class Issued(
        val token: IssuedToken,
    ) : TokenDecision

    data class Refused(
        val reasonCode: String,
    ) : TokenDecision
}

/**
 * The adapter of `issueAccessToken`: the proof is decided by [GatewayAuthentication] (tenant resolved by the claim
 * port, INV-Q-8982 A; a revoked or unknown principal is REFUSED there), and only an authenticated principal reaches the
 * signer. A key that cannot sign propagates as `SigningKeyUnavailable`.
 */
class GatewayTokenIssuance(
    val authentication: GatewayAuthentication,
    private val issuer: IssueAccessTokenUseCase,
) {
    fun issue(
        principalRef: String,
        method: CredentialMethod,
        proof: String,
    ): TokenDecision =
        when (val decision = authentication.authenticate(principalRef, method, proof)) {
            is AuthenticationDecision.Refused -> TokenDecision.Refused(decision.reasonCode)
            is AuthenticationDecision.Authenticated -> TokenDecision.Issued(issuer.issue(decision.principal))
        }
}
