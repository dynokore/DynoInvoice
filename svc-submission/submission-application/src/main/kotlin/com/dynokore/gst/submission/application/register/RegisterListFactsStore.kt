package com.dynokore.gst.submission.application.register

import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.tenancykernel.TenantId

/** Stores and reads the named list facts of a register entry (INV-REQ-975); it never creates an entry. */
interface RegisterListFactsStore {
    /** True when the entry of this tenant and document exists and its facts were written; false otherwise. */
    fun store(
        tenant: TenantId,
        docUuid: String,
        facts: ListFacts,
    ): Boolean

    /** The facts of the entry, or null when the entry does not exist or holds none. */
    fun facts(
        tenant: TenantId,
        docUuid: String,
    ): ListFacts?
}
