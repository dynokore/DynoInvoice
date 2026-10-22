package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.principal.AuthenticatePrincipalUseCase
import com.dynokore.gst.access.application.principal.AuthenticationDecision
import com.dynokore.gst.access.application.principal.CreatePrincipalOutcome
import com.dynokore.gst.access.application.principal.CreatePrincipalUseCase
import com.dynokore.gst.access.application.principal.CredentialRequest
import com.dynokore.gst.access.application.principal.IssuedCredential
import com.dynokore.gst.access.application.principal.RevokePrincipalOutcome
import com.dynokore.gst.access.application.principal.RevokePrincipalUseCase
import com.dynokore.gst.access.application.principal.RotatePrincipalOutcome
import com.dynokore.gst.access.application.principal.RotatePrincipalUseCase
import com.dynokore.gst.access.application.principal.WithdrawCredentialOutcome
import com.dynokore.gst.access.application.principal.WithdrawCredentialUseCase
import com.dynokore.gst.access.contract.api.PrincipalsApi
import com.dynokore.gst.access.contract.model.AuthenticatePrincipalRequest
import com.dynokore.gst.access.contract.model.AuthenticatePrincipalResponse
import com.dynokore.gst.access.contract.model.CreatePrincipalRequest
import com.dynokore.gst.access.contract.model.CredentialInfo
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
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.ZoneOffset
import java.util.UUID

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
    private val create: CreatePrincipalUseCase,
    private val rotate: RotatePrincipalUseCase,
    private val revoke: RevokePrincipalUseCase,
    private val withdraw: WithdrawCredentialUseCase,
    private val authenticate: AuthenticatePrincipalUseCase,
    private val tenants: TenantContext,
    private val manager: GrantManagerCheck,
    private val gateway: GatewayCaller,
    private val mapper: ObjectMapper,
) : PrincipalsApi {
    override fun createPrincipal(
        idempotencyKey: UUID,
        createPrincipalRequest: CreatePrincipalRequest,
    ): ResponseEntity<PrincipalResponse> {
        requireManager()
        val tenant = TenantRef(tenants.current().toString())
        val type =
            PrincipalType.ofContract(createPrincipalRequest.principalType.value)
                ?: GrantRequests.invalid("principalType is not one of the six issuable types")
        // the scope must be one the service can read; its grammar is the one of grantRole, plus the literal
        // `installation` for a MONITORING principal (INV-Q-8779 A)
        val tenantOrEntity =
            !PrincipalScopes.isInstallation(createPrincipalRequest.scope) &&
                GrantRequests.scopeOf(createPrincipalRequest.scope, tenant) != null
        if (!PrincipalScopes.accepts(type, createPrincipalRequest.scope, tenantOrEntity)) {
            GrantRequests.invalid("the scope is not one a ${type.name} principal may carry")
        }
        val outcome =
            create.handle(
                actor = checkNotNull(tenants.currentActor()) { "no actor is bound" },
                tenant = tenant,
                requestedType = createPrincipalRequest.principalType.value,
                scope = createPrincipalRequest.scope,
                callingSystem = createPrincipalRequest.callingSystem,
                credential =
                    CredentialRequest(
                        createPrincipalRequest.credentialMethod?.let { CredentialMethod.valueOf(it.name) },
                        jwkText(createPrincipalRequest.publicKeyJwk?.let(::jwkFields)),
                        createPrincipalRequest.certificateThumbprint,
                    ),
            )
        return when (outcome) {
            is CreatePrincipalOutcome.Created -> {
                ResponseEntity
                    .status(if (outcome.repeated) HttpStatus.OK else HttpStatus.CREATED)
                    .body(responseOf(outcome.principal, outcome.issued))
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
            CredentialRequest(
                rotatePrincipalRequest.credentialMethod?.let { CredentialMethod.valueOf(it.name) },
                jwkText(rotatePrincipalRequest.publicKeyJwk?.let(::jwkFields)),
                rotatePrincipalRequest.certificateThumbprint,
            )
        return when (val outcome = rotate.handle(actor, principalRef, rotatePrincipalRequest.overlapMinutes, request)) {
            is RotatePrincipalOutcome.Rotated -> {
                ResponseEntity.ok(responseOf(outcome.principal, outcome.issued, outcome.previous))
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
            is RevokePrincipalOutcome.Revoked -> ResponseEntity.ok(responseOf(outcome.principal))
            is RevokePrincipalOutcome.AlreadyRevoked -> ResponseEntity.ok(responseOf(outcome.principal))
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
                ResponseEntity.ok(responseOf(outcome.principal, credential = outcome.credential))
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
            authenticate.handle(
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

    private fun requireManager() {
        if (!manager.mayManageGrants()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
    }

    private fun jwkFields(jwk: Any): Map<String, String?> = mapper.convertValue(jwk, JWK_FIELDS)

    /** The public JWK as JSON text, or null when the request carries none. */
    private fun jwkText(fields: Map<String, String?>?): String? =
        fields?.filterValues { !it.isNullOrEmpty() }?.let { mapper.writeValueAsString(it) }

    private fun responseOf(
        principal: ServicePrincipal,
        issued: IssuedCredential? = null,
        previous: Credential? = null,
        credential: Credential? = null,
    ) = PrincipalResponse(
        principalRef = principal.ref,
        principalType = principal.type.name,
        status = PrincipalResponse.Status.valueOf(principal.status.name),
        callingSystem = principal.callingSystem,
        credential = issued?.let { infoOf(it.credential, it.clientSecret) } ?: credential?.let { infoOf(it, null) },
        previousCredential = previous?.let { infoOf(it, null) },
    )

    private fun infoOf(
        credential: Credential,
        clientSecret: String?,
    ) = CredentialInfo(
        credentialRef = credential.ref,
        method = CredentialInfo.Method.valueOf(credential.method.name),
        status = CredentialInfo.Status.valueOf(credential.status.name),
        validUntil = credential.validUntil?.atOffset(ZoneOffset.UTC),
        clientSecret = clientSecret,
    )

    private companion object {
        val JWK_FIELDS = object : tools.jackson.core.type.TypeReference<Map<String, String?>>() {}
    }
}
