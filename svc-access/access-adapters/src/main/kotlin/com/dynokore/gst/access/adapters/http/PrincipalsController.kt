package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.principal.AuthenticationDecision
import com.dynokore.gst.access.application.principal.CreatePrincipalOutcome
import com.dynokore.gst.access.application.principal.CreatePrincipalUseCase
import com.dynokore.gst.access.application.principal.CredentialRequest
import com.dynokore.gst.access.application.principal.IssuedCredential
import com.dynokore.gst.access.application.principal.NewPrincipal
import com.dynokore.gst.access.application.principal.RevokePrincipalOutcome
import com.dynokore.gst.access.application.principal.RevokePrincipalUseCase
import com.dynokore.gst.access.application.principal.RotatePrincipalOutcome
import com.dynokore.gst.access.application.principal.RotatePrincipalUseCase
import com.dynokore.gst.access.application.principal.WithdrawCredentialOutcome
import com.dynokore.gst.access.application.principal.WithdrawCredentialUseCase
import com.dynokore.gst.access.application.token.SigningKeyUnavailable
import com.dynokore.gst.access.contract.api.PrincipalsApi
import com.dynokore.gst.access.contract.model.AuthenticatePrincipalRequest
import com.dynokore.gst.access.contract.model.AuthenticatePrincipalResponse
import com.dynokore.gst.access.contract.model.CreatePrincipalRequest
import com.dynokore.gst.access.contract.model.CredentialInfo
import com.dynokore.gst.access.contract.model.IssueAccessTokenRequest
import com.dynokore.gst.access.contract.model.IssueAccessTokenResponse
import com.dynokore.gst.access.contract.model.PrincipalResponse
import com.dynokore.gst.access.contract.model.RotatePrincipalRequest
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.principal.Credential
import com.dynokore.gst.access.domain.principal.CredentialMethod
import com.dynokore.gst.access.domain.principal.PrincipalScopes
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.ZoneOffset
import java.util.UUID

/** The four mutating use cases of the principal operations, injected into [PrincipalsController] as one. */
@Component
class PrincipalOperations(
    val create: CreatePrincipalUseCase,
    val rotate: RotatePrincipalUseCase,
    val revoke: RevokePrincipalUseCase,
    val withdraw: WithdrawCredentialUseCase,
)

/**
 * TPL-08. The principal operations: the caller of createPrincipal, rotatePrincipal, revokePrincipal and
 * withdrawCredential must be allowed to manage grants ([GrantManagerCheck], the `tenant.admin` role of the contract's
 * x-operation); `authenticatePrincipal` answers only the API gateway's service account. A type outside the six, a scope
 * the type may not carry, an unusable credential request, or a FILING_WORKFLOW request without callingSystem is refused
 * with 400 (SRT-001) and creates nothing; a repeat of the same (type, scope, callingSystem) returns the original
 * principal and issues no second credential. `authenticatePrincipal` always answers 200: the decision carries the
 * refusal.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin)
open class PrincipalsController(
    useCases: PrincipalOperations,
    private val authenticate: GatewayAuthentication,
    private val tokens: GatewayTokenIssuance,
    private val tenants: TenantContext,
    private val manager: GrantManagerCheck,
    private val gateway: GatewayCaller,
    private val mapper: ObjectMapper,
) : PrincipalsApi {
    private val create = useCases.create
    private val rotate = useCases.rotate
    private val revoke = useCases.revoke
    private val withdraw = useCases.withdraw

    private fun newPrincipalOf(request: CreatePrincipalRequest) =
        NewPrincipal(
            requestedType = request.principalType.value,
            scope = request.scope,
            callingSystem = request.callingSystem,
            credential =
                credentialOf(
                    request.credentialMethod?.name,
                    request.publicKeyJwk,
                    request.certificateThumbprint,
                ),
        )

    override fun createPrincipal(
        idempotencyKey: UUID,
        createPrincipalRequest: CreatePrincipalRequest,
    ): ResponseEntity<PrincipalResponse> {
        requireManager()
        val tenant = TenantRef(tenants.current().toString())
        val type =
            PrincipalType.ofContract(createPrincipalRequest.principalType.value)
                ?: GrantRequests.invalid("principalType is not one of the six issuable types")
        requireScope(type, createPrincipalRequest.scope, tenant)
        val outcome =
            create.handle(
                actor = checkNotNull(tenants.currentActor()) { "no actor is bound" },
                tenant = tenant,
                request = newPrincipalOf(createPrincipalRequest),
            )
        return when (outcome) {
            is CreatePrincipalOutcome.Created -> {
                ResponseEntity
                    .status(if (outcome.repeated) HttpStatus.OK else HttpStatus.CREATED)
                    .body(principalResponse(outcome.principal, outcome.issued))
            }

            is CreatePrincipalOutcome.TypeNotIssuable -> {
                GrantRequests.invalid("principalType is not one of the six issuable types")
            }

            CreatePrincipalOutcome.CallingSystemMissing -> {
                GrantRequests.invalid("callingSystem is required for a FILING_WORKFLOW principal")
            }

            is CreatePrincipalOutcome.CredentialInvalid -> {
                GrantRequests.invalid(outcome.reason)
            }
        }
    }

    override fun rotatePrincipal(
        principalRef: String,
        idempotencyKey: UUID,
        rotatePrincipalRequest: RotatePrincipalRequest,
    ): ResponseEntity<PrincipalResponse> {
        requireManager()
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        val request =
            credentialOf(
                rotatePrincipalRequest.credentialMethod?.name,
                rotatePrincipalRequest.publicKeyJwk,
                rotatePrincipalRequest.certificateThumbprint,
            )
        return when (val outcome = rotate.handle(actor, principalRef, rotatePrincipalRequest.overlapMinutes, request)) {
            is RotatePrincipalOutcome.Rotated -> {
                ResponseEntity.ok(principalResponse(outcome.principal, outcome.issued, outcome.previous))
            }

            is RotatePrincipalOutcome.CredentialInvalid -> {
                GrantRequests.invalid(outcome.reason)
            }

            RotatePrincipalOutcome.PrincipalRevoked -> {
                GrantRequests.invalid("a revoked principal cannot be rotated")
            }

            RotatePrincipalOutcome.NotFound -> {
                throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }
        }
    }

    override fun revokePrincipal(
        principalRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<PrincipalResponse> {
        requireManager()
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        return when (val outcome = revoke.handle(actor, principalRef)) {
            is RevokePrincipalOutcome.Revoked -> ResponseEntity.ok(principalResponse(outcome.principal))
            is RevokePrincipalOutcome.AlreadyRevoked -> ResponseEntity.ok(principalResponse(outcome.principal))
            RevokePrincipalOutcome.NotFound -> throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
        }
    }

    override fun withdrawCredential(
        principalRef: String,
        credentialRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<PrincipalResponse> {
        requireManager()
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        return when (val outcome = withdraw.handle(actor, principalRef, credentialRef)) {
            is WithdrawCredentialOutcome.Withdrawn -> {
                ResponseEntity.ok(principalResponse(outcome.principal, credential = outcome.credential))
            }

            WithdrawCredentialOutcome.NotFound -> {
                throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }
        }
    }

    override fun authenticatePrincipal(
        authenticatePrincipalRequest: AuthenticatePrincipalRequest,
    ): ResponseEntity<AuthenticatePrincipalResponse> {
        if (!gateway.isGateway()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        val decision =
            authenticate.authenticate(
                authenticatePrincipalRequest.principalRef,
                CredentialMethod.valueOf(authenticatePrincipalRequest.method.name),
                authenticatePrincipalRequest.proof,
            )
        val body =
            when (decision) {
                is AuthenticationDecision.Authenticated -> {
                    AuthenticatePrincipalResponse(
                        decision = AuthenticatePrincipalResponse.Decision.AUTHENTICATED,
                        principalRef = decision.principal.ref,
                        principalType = decision.principal.type.name,
                        scope = decision.principal.scope,
                        credentialRef = decision.credentialRef,
                    )
                }

                is AuthenticationDecision.Refused -> {
                    AuthenticatePrincipalResponse(
                        decision = AuthenticatePrincipalResponse.Decision.REFUSED,
                        reasonCode = decision.reasonCode,
                    )
                }
            }
        return ResponseEntity.ok(body)
    }

    /**
     * `issueAccessToken`: always 200 with the decision, like `authenticatePrincipal`; a key that cannot sign (the
     * stored key unreadable, or past its signing interval) is 503 SRT-011 with no token, and nothing says why (API-22).
     */
    override fun issueAccessToken(
        issueAccessTokenRequest: IssueAccessTokenRequest,
    ): ResponseEntity<IssueAccessTokenResponse> {
        if (!gateway.isGateway()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        val decision =
            try {
                tokens.issue(
                    issueAccessTokenRequest.principalRef,
                    CredentialMethod.valueOf(issueAccessTokenRequest.method.name),
                    issueAccessTokenRequest.proof,
                )
            } catch (_: SigningKeyUnavailable) {
                throw ApiRefusal(ClosedErrorCode.ISSUER_UNAVAILABLE)
            }
        val body =
            when (decision) {
                is TokenDecision.Issued -> {
                    IssueAccessTokenResponse(
                        decision = IssueAccessTokenResponse.Decision.ISSUED,
                        accessToken = decision.token.accessToken,
                        expiresIn = decision.token.expiresIn,
                        keyId = decision.token.keyId,
                        tokenId = decision.token.tokenId,
                    )
                }

                is TokenDecision.Refused -> {
                    IssueAccessTokenResponse(
                        decision = IssueAccessTokenResponse.Decision.REFUSED,
                        reasonCode = decision.reasonCode,
                    )
                }
            }
        return ResponseEntity.ok(body)
    }

    /**
     * The scope must be one the service can read; its grammar is the one of grantRole, plus the literal `installation`
     * for a MONITORING principal (INV-Q-8779 A).
     */
    private fun requireScope(
        type: PrincipalType,
        scope: String,
        tenant: TenantRef,
    ) {
        val tenantOrEntity = !PrincipalScopes.isInstallation(scope) && GrantRequests.scopeOf(scope, tenant) != null
        if (!PrincipalScopes.accepts(type, scope, tenantOrEntity)) {
            GrantRequests.invalid("the scope is not one a ${type.name} principal may carry")
        }
    }

    private fun credentialOf(
        method: String?,
        publicKeyJwk: Any?,
        certificateThumbprint: String?,
    ) = CredentialRequest(
        method?.let { CredentialMethod.valueOf(it) },
        jwkText(publicKeyJwk?.let(::jwkFields)),
        certificateThumbprint,
    )

    private fun requireManager() {
        if (!manager.mayManageGrants()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
    }

    private fun jwkFields(jwk: Any): Map<String, String?> = mapper.convertValue(jwk, JWK_FIELDS)

    /** The public JWK as JSON text, or null when the request carries none. */
    private fun jwkText(fields: Map<String, String?>?): String? =
        fields?.filterValues { !it.isNullOrEmpty() }?.let { mapper.writeValueAsString(it) }

    private companion object {
        val JWK_FIELDS = object : tools.jackson.core.type.TypeReference<Map<String, String?>>() {}
    }
}
