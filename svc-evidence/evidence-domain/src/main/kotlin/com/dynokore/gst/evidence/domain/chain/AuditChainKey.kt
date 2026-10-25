package com.dynokore.gst.evidence.domain.chain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The chain of an AuditEntryRecorded (INV-Q-7213 A (4)). The period text is a storage key of the audit log, not a
 * prescribed period; which entries an audit pack holds is decided by the pack slice from the period's time window.
 */
object AuditChainKey {
    const val TENANT_ENTITY = "TENANT"
    private const val PREFIX = "AUDIT-"
    private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC)

    /** [entityRef] or, when null, `TENANT`; the period is `AUDIT-` and the UTC year and month of [time]. */
    fun of(
        entityRef: String?,
        time: Instant,
    ): ChainKey {
        require(entityRef == null || entityRef.isNotBlank()) { "entityRef must be null or not blank" }
        return ChainKey(entityRef ?: TENANT_ENTITY, PREFIX + MONTH.format(time))
    }
}
