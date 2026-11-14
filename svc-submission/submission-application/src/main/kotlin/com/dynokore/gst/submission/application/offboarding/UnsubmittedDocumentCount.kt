package com.dynokore.gst.submission.application.offboarding

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant

/**
 * Port: the number of in-scope documents of a tenant (or of one entity when [entityRef] is non-null) that are
 * unsubmitted by the readiness verdict's definition (INV-REQ-956, INV-REQ-71). A failed read throws; it never
 * answers 0 (fail closed, OWASP ASVS V1).
 */
fun interface UnsubmittedDocumentSource {
    fun count(
        tenant: TenantId,
        entityRef: String?,
    ): Int
}

/** The answer of the offboarding count read. */
data class UnsubmittedCount(
    val unsubmittedDocumentCount: Int,
    val countedAt: Instant,
)

/**
 * Counts the unsubmitted in-scope documents of a tenant or entity (INV-REQ-956). A workload read: no permission
 * key is checked here, and a source exception propagates unchanged.
 */
class CountUnsubmittedDocuments(
    private val source: UnsubmittedDocumentSource,
    private val clock: Clock,
) {
    fun execute(
        tenant: TenantId,
        entityRef: String?,
    ): UnsubmittedCount {
        val count = source.count(tenant, entityRef)
        return UnsubmittedCount(count, clock.instant())
    }
}
