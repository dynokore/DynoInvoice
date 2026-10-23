package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.idp.IdpGroupMapping
import com.dynokore.gst.access.application.idp.MapIdpGroupCommand
import com.dynokore.gst.access.application.idp.MapIdpGroupOutcome
import com.dynokore.gst.access.application.idp.MapIdpGroupUseCase
import com.dynokore.gst.access.application.idp.UnmapIdpGroupUseCase
import com.dynokore.gst.access.contract.api.IdentityProviderApi
import com.dynokore.gst.access.contract.model.IdpGroupMappingRequest
import com.dynokore.gst.access.contract.model.IdpGroupMappingResponse
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TPL-08. mapIdpGroup and unmapIdpGroup (INV-REQ-892, INV-REQ-1263): `tenant.admin` only ([GrantManagerCheck]), for the
 * bound tenant. A mapping carries the validity window of the grants it makes; a repeat of the same group, role and
 * scope returns the original mapping; removing an unknown or removed mapping is a no-op.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class IdpGroupController(
    private val map: MapIdpGroupUseCase,
    private val unmap: UnmapIdpGroupUseCase,
    private val tenants: TenantContext,
    private val manager: GrantManagerCheck,
) : IdentityProviderApi {
    override fun mapIdpGroup(
        idempotencyKey: UUID,
        idpGroupMappingRequest: IdpGroupMappingRequest,
    ): ResponseEntity<IdpGroupMappingResponse> {
        requireManager()
        val tenant = TenantRef(tenants.current().toString())
        val scope = GrantRequests.scopeOf(idpGroupMappingRequest.scope, tenant)
        val outcome =
            map.handle(
                MapIdpGroupCommand(
                    actor = checkNotNull(tenants.currentActor()) { "no actor is bound" },
                    tenant = tenant,
                    idpGroup = idpGroupMappingRequest.idpGroup,
                    roleWire = idpGroupMappingRequest.role,
                    scope = scope,
                    validFrom = idpGroupMappingRequest.validFrom,
                    validTo = idpGroupMappingRequest.validTo,
                ),
            )
        return when (outcome) {
            is MapIdpGroupOutcome.Mapped -> {
                ResponseEntity
                    .status(if (outcome.repeated) HttpStatus.OK else HttpStatus.CREATED)
                    .body(responseOf(outcome.mapping))
            }

            MapIdpGroupOutcome.ScopeMissing -> {
                GrantRequests.invalid("a mapping without a scope is invalid")
            }

            is MapIdpGroupOutcome.UnknownRole -> {
                GrantRequests.invalid("role is not in the role catalogue")
            }

            MapIdpGroupOutcome.WindowInvalid -> {
                GrantRequests.invalid("validTo must be after validFrom")
            }
        }
    }

    override fun unmapIdpGroup(
        mappingRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<Unit> {
        requireManager()
        unmap.handle(
            checkNotNull(tenants.currentActor()) { "no actor is bound" },
            TenantRef(tenants.current().toString()),
            mappingRef,
        )
        return ResponseEntity.noContent().build()
    }

    private fun requireManager() {
        if (!manager.mayManageGrants()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
    }

    private fun responseOf(mapping: IdpGroupMapping) =
        IdpGroupMappingResponse(
            mappingRef = mapping.ref,
            idpGroup = mapping.idpGroup,
            role = mapping.role.wire,
            scope = scopeText(mapping.scope),
            validFrom = mapping.validFrom,
            validTo = mapping.validTo,
        )

    private fun scopeText(scope: Scope): String =
        when (scope) {
            is Scope.Tenant -> "tenant:${scope.tenant.value}"
            is Scope.Entity -> "entity:${scope.entity.value}"
            else -> error("a group mapping holds a tenant or an entity scope only")
        }
}
