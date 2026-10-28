package com.dynokore.gst.evidence.application.cases

import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.platform.tenancykernel.TenantId
import java.util.UUID

/** The cases of one tenant. Both methods run in the caller's tenant transaction. */
interface CaseStore {
    fun insert(
        tenant: TenantId,
        case: CaseRecord,
    )

    fun find(
        tenant: TenantId,
        caseId: UUID,
    ): CaseRecord?
}
