package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.application.HolderCopyPort
import com.dynokore.gst.rules.application.HolderCopyRequest
import com.dynokore.gst.rules.application.HolderCopyResult
import com.dynokore.gst.rules.application.PackActionAuthority
import com.dynokore.gst.rules.application.PackApprovalDecision
import com.dynokore.gst.rules.application.PackApprovalDecisionPort
import com.dynokore.gst.rules.application.RateSourcePort
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

// The production defaults of the ports the mapping pack commands ask. Each refuses until the task that owns the real
// adapter lands (fail closed, INV-Q-14647 A): a service never grants a permission, an approval, a rate-source answer
// or a holder copy because a collaborator is missing. Each is registered `@ConditionalOnMissingBean`, so the real
// adapter replaces it by being a bean of the port type.

/** Holds no permission for anyone until the adapter over `AuthorityReplica.decide` lands (INV-Q-14652 A). */
class RefusingPackActionAuthority : PackActionAuthority {
    override fun holds(
        actor: String,
        entityRef: String,
        permission: String,
    ): Boolean = false
}

/** Cannot ask access-svc until INV-T-10700 replaces it. */
class RefusingPackApprovalDecisionPort : PackApprovalDecisionPort {
    override fun decide(
        actor: String,
        entityRef: String,
        packId: String,
        deltaId: String,
        priorApproverRef: List<String>,
    ): PackApprovalDecision = PackApprovalDecision.Unavailable
}

/** Cannot read the approved rate sources until INV-T-16546 replaces it (INV-Q-14651 A, INV-Q-16533 A). */
class RefusingRateSourcePort : RateSourcePort {
    override fun blocking(
        tenant: TenantId,
        asOf: LocalDate,
    ): List<String>? = null
}

/** Cannot write the signed holder copy until INV-T-16544 replaces it (INV-Q-14650 A, INV-Q-16531 A). */
class RefusingHolderCopyPort : HolderCopyPort {
    override fun write(request: HolderCopyRequest): HolderCopyResult = HolderCopyResult.Unavailable
}
