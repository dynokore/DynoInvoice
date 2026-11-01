package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.queue.InFlightStore
import com.dynokore.gst.submission.application.queue.VersionState
import com.dynokore.gst.submission.domain.ordering.InFlight
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext

/** [InFlightStore] over the generated jOOQ classes of `submission` (CS-18), in the open tenant transaction. */
class JooqInFlightStore(
    private val dsl: DSLContext,
) : InFlightStore {
    override fun lockVersions(
        tenant: TenantId,
        docUuid: String,
    ): List<VersionState> {
        val s = Submission.SUBMISSION_
        return dsl
            .select(s.VERSION, s.STATE)
            .from(s)
            .where(s.TENANT_ID.eq(tenant.value))
            .and(s.DOC_UUID.eq(docUuid))
            .orderBy(s.VERSION)
            .forUpdate()
            .fetch()
            .map { VersionState(checkNotNull(it.value1()), LifecycleState.valueOf(checkNotNull(it.value2()))) }
    }

    override fun waitingDocUuids(
        tenant: TenantId,
        limit: Int,
    ): List<String> {
        val s = Submission.SUBMISSION_
        return dsl
            .selectDistinct(s.DOC_UUID)
            .from(s)
            .where(s.TENANT_ID.eq(tenant.value))
            .and(s.STATE.eq(LifecycleState.READY.name))
            .and(
                s.DOC_UUID.notIn(
                    dsl
                        .select(s.DOC_UUID)
                        .from(s)
                        .where(s.TENANT_ID.eq(tenant.value))
                        .and(s.STATE.`in`(InFlight.NAMES)),
                ),
            ).orderBy(s.DOC_UUID)
            .limit(limit)
            .fetch(s.DOC_UUID)
            .map { checkNotNull(it) }
    }
}
