package com.dynokore.gst.evidence.domain.chain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The chain of an AuditEntryRecorded (INV-Q-7213 A (4)). The period text is a storage key of the audit log, not a
 * prescribed period; which entries an audit pack holds is decided by the pack slice from the period's time window.
 */
object AuditChainKey {
    private const val PREFIX = "AUDIT-"
    private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC)

    /**
     * [entityRef] exactly as given, with no special case: an entry with no wire `entityref` arrives as the value of
     * `EntityRef.TENANT_WIDE` from the platform decode (INV-Q-10814 A). The period is `AUDIT-` and the UTC year and
     * month of [time].
     */
    fun of(
        entityRef: String,
        time: Instant,
    ): ChainKey = ChainKey(entityRef, PREFIX + MONTH.format(time))
}
