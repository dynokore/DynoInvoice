package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.BreakGlassController
import com.dynokore.gst.access.contract.model.ApproveBreakGlassUnlockRequest
import com.dynokore.gst.access.contract.model.ApproveBreakGlassUnlockResponse
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.http.ResponseEntity
import java.util.UUID

/** The approval step that every unlock of INV-T-9314 needs: each approver other than the caller confirms first. */
object BreakGlassApproving {
    /** `approveBreakGlassUnlock` for [tenant] called by [approver] through the controller. */
    fun approve(
        context: AssertableWebApplicationContext,
        tenant: TenantId,
        approver: String,
        requestedTenant: String = tenant.toString(),
        key: UUID = UUID.randomUUID(),
    ): ResponseEntity<ApproveBreakGlassUnlockResponse> =
        context.getBean(TenantContext::class.java).bind(tenant, approver) {
            context
                .getBean(BreakGlassController::class.java)
                .approveBreakGlassUnlock(key, ApproveBreakGlassUnlockRequest(requestedTenant))
        }

    /**
     * Approves for every name in [approvers] other than [caller] that is allowed to; a person who may not approve
     * (a role outside the pairing) gets no approval, so the unlock then shows the refusal the test is about.
     */
    fun approveOthers(
        context: AssertableWebApplicationContext,
        tenant: TenantId,
        caller: String,
        approvers: Collection<String>,
    ) {
        approvers.filter { it != caller }.distinct().forEach { approver ->
            runCatching { approve(context, tenant, approver) }
        }
    }
}
