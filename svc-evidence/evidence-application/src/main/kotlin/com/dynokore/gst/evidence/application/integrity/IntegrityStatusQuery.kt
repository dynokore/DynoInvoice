package com.dynokore.gst.evidence.application.integrity

import com.dynokore.gst.evidence.domain.integrity.IntegrityState
import com.dynokore.gst.evidence.domain.integrity.IntegrityStatus
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.platform.tenancykernel.TenantId

/** The question `getIntegrityStatus` asks (INV-REQ-661): the integrity status of [entity] and [period]. */
data class IntegrityStatusQuery(
    val entity: String,
    val period: String,
)

/**
 * Answers an [IntegrityStatusQuery] from the latest `integrity_run` row (INV-Q-7251 A (4)), or with
 * [IntegrityStatus.NEVER_RUN] when there is none. It runs in the caller's tenant transaction.
 */
class IntegrityStatusQueryHandler(
    private val store: IntegrityStore,
) {
    fun handle(
        tenant: TenantId,
        query: IntegrityStatusQuery,
    ): IntegrityStatus =
        store.latestRun(tenant, query.entity, query.period)?.let {
            IntegrityStatus(
                when (it.result) {
                    RunResult.VERIFIED -> IntegrityState.VERIFIED
                    RunResult.FAILED -> IntegrityState.FAILED
                },
                it.runAt,
                it.chainLength,
                it.periodDigest,
            )
        } ?: IntegrityStatus.NEVER_RUN
}
