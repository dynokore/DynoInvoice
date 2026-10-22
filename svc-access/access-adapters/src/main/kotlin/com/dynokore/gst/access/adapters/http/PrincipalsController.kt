package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.principal.CreatePrincipalOutcome
import com.dynokore.gst.access.application.principal.CreatePrincipalUseCase
import com.dynokore.gst.access.application.principal.RevokePrincipalOutcome
import com.dynokore.gst.access.application.principal.RevokePrincipalUseCase
import com.dynokore.gst.access.contract.api.PrincipalsApi
import com.dynokore.gst.access.contract.model.CreatePrincipalRequest
import com.dynokore.gst.access.contract.model.PrincipalResponse
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TPL-08. createPrincipal and revokePrincipal: the caller must be allowed to manage grants ([GrantManagerCheck], the
 * `tenant.admin` role of the contract's x-operation). A type outside the six, a scope this version cannot read, or a
 * FILING_WORKFLOW request without callingSystem is refused with 400 (SRT-001) and creates nothing; a repeat of the
 * same (type, scope, callingSystem) returns the original principal. rotatePrincipal is not implemented here (501).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin)
open class PrincipalsController(
    private val create: CreatePrincipalUseCase,
    private val revoke: RevokePrincipalUseCase,
    private val tenants: TenantContext,
    private val manager: GrantManagerCheck,
) : PrincipalsApi {
    override fun createPrincipal(
        idempotencyKey: UUID,
        createPrincipalRequest: CreatePrincipalRequest,
    ): ResponseEntity<PrincipalResponse> {
        requireManager()
        val tenant = TenantRef(tenants.current().toString())
        // the scope must be one the service can read; its grammar is the one of grantRole
        GrantRequests.scopeOf(createPrincipalRequest.scope, tenant)
            ?: GrantRequests.invalid("a principal without a scope is invalid")
        val outcome =
            create.handle(
                actor = checkNotNull(tenants.currentActor()) { "no actor is bound" },
                tenant = tenant,
                requestedType = createPrincipalRequest.principalType.value,
                scope = createPrincipalRequest.scope,
                callingSystem = createPrincipalRequest.callingSystem,
            )
        return when (outcome) {
            is CreatePrincipalOutcome.Created -> {
                ResponseEntity
                    .status(if (outcome.repeated) HttpStatus.OK else HttpStatus.CREATED)
                    .body(responseOf(outcome.principal))
            }

            is CreatePrincipalOutcome.TypeNotIssuable -> {
                GrantRequests.invalid("principalType is not one of the six issuable types")
            }

            CreatePrincipalOutcome.CallingSystemMissing -> {
                GrantRequests.invalid("callingSystem is required for a FILING_WORKFLOW principal")
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

    private fun requireManager() {
        if (!manager.mayManageGrants()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
    }

    private fun responseOf(principal: ServicePrincipal) =
        PrincipalResponse(
            principalRef = principal.ref,
            principalType = principal.type.name,
            status = PrincipalResponse.Status.valueOf(principal.status.name),
            callingSystem = principal.callingSystem,
        )
}
