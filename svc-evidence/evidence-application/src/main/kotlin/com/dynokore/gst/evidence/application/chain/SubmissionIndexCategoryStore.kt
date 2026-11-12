package com.dynokore.gst.evidence.application.chain

import com.dynokore.platform.tenancykernel.TenantId
import java.util.UUID

/** The category child rows of the reverse-lookup read table, one per distinct line tax code (INV-REQ-724, E4e). */
interface SubmissionIndexCategoryStore {
    /** Inserts one row per code, as given, and ignores a row that already exists. */
    fun insertAll(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
        codes: Set<String>,
    )
}
