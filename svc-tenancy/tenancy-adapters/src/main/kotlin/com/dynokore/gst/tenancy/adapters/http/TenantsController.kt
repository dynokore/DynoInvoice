package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.tenant.CreateTenant
import com.dynokore.gst.tenancy.application.tenant.TenantCreation
import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.application.tenant.TenantRefused
import com.dynokore.gst.tenancy.contract.api.TenantsApi
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.TenantResponse
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TPL-08. createTenant (scope platform, caller control-plane-svc): the tenant is recorded under the `tenantRef` sent, or
 * under the ref derived from the contract reference; a repeat of the same tenant and contract returns the original
 * (INV-Q-7466 A, INV-Q-7468 A).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin)
open class TenantsController(
    private val createTenant: CreateTenant,
) : TenantsApi {
    override fun createTenant(
        idempotencyKey: UUID,
        createTenantRequest: CreateTenantRequest,
    ): ResponseEntity<TenantResponse> {
        val ref = createTenantRequest.tenantRef ?: TenantRefs.refOfContract(createTenantRequest.contractReference)
        val id = runCatching { TenantRefs.idOf(ref) }.getOrElse { invalid("tenantRef is not a usable tenant reference") }
        val requested =
            TenantRecord(
                id.value,
                ref,
                createTenantRequest.legalName,
                createTenantRequest.planRef,
                createTenantRequest.contractReference,
            )
        val stored =
            try {
                when (val outcome = createTenant.handle(requested)) {
                    is TenantCreation.Created -> outcome.tenant
                    is TenantCreation.Repeated -> outcome.tenant
                    TenantCreation.Conflict -> conflict("the tenant or its contract reference is already recorded")
                }
            } catch (e: TenantRefused) {
                if (e.invalid) invalid("the tenant is refused") else throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(TenantResponse(stored.ref, stored.legalName, stored.planRef))
    }
}
