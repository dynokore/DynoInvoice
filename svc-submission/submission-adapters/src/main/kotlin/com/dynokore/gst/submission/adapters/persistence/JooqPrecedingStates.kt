package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.ordering.PrecedingStates
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext

/**
 * [PrecedingStates] over the register and the version rows (INV-REQ-709): one select of the register entry by tenant and
 * Doc UUID and, when it exists, one select of the version states ordered by version ascending. Every query names the
 * tenant id besides the row level security. A state text that is not a [LifecycleState] name throws
 * [IllegalStateException]; no default state is used.
 */
class JooqPrecedingStates(
    private val dsl: DSLContext,
) : PrecedingStates {
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val version = Submission.SUBMISSION_

    override fun of(
        tenant: TenantId,
        precedingDocUuid: String,
    ): List<LifecycleState>? {
        val present =
            dsl.fetchExists(
                dsl
                    .selectOne()
                    .from(entry)
                    .where(entry.TENANT_ID.eq(tenant.value))
                    .and(entry.DOC_UUID.eq(precedingDocUuid)),
            )
        if (!present) return null
        return dsl
            .select(version.STATE)
            .from(version)
            .where(version.TENANT_ID.eq(tenant.value))
            .and(version.DOC_UUID.eq(precedingDocUuid))
            .orderBy(version.VERSION.asc())
            .fetch(version.STATE)
            .map { state ->
                try {
                    LifecycleState.valueOf(checkNotNull(state))
                } catch (e: IllegalArgumentException) {
                    throw IllegalStateException("unknown lifecycle state in a version row", e)
                }
            }
    }
}
