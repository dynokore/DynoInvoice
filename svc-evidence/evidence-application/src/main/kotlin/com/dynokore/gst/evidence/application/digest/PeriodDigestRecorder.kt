package com.dynokore.gst.evidence.application.digest

import com.dynokore.gst.evidence.domain.digest.ChainTail
import com.dynokore.gst.evidence.domain.digest.DigestChainType
import com.dynokore.gst.evidence.domain.digest.periodDigest
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** What a `GstReturnRecorded` event says about the filing (INV-Q-7251 A (1)). */
data class FiledReturn(
    val eventId: String,
    val entityRef: String,
    val period: String,
    val returnVersion: Int,
    val filedAt: Instant,
)

/**
 * Writes the period digest of a filed return (INV-Q-7251 A (2) and (3)): reads the tail of the three document-bound
 * chains of (tenant, entity, period), puts the JSON copy to the object store (kept when it is already there with the
 * same bytes), then appends the digest rows. It runs in
 * the caller's tenant transaction (the inbox consumer's), so the object is put before the transaction commits.
 * A key that holds other bytes raises [DigestObjectConflict].
 */
class PeriodDigestRecorder(
    private val tails: ChainTails,
    private val store: PeriodDigestStore,
    private val objects: DigestObjects,
    private val retention: MaxRetention,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun record(
        tenant: TenantId,
        filed: FiledReturn,
    ): PeriodDigestEntry {
        store.lock(tenant, filed.entityRef, filed.period)
        val chains: List<ChainTail> =
            DigestChainType.entries.map { tails.tail(tenant, filed.entityRef, filed.period, it) }
        val entry =
            PeriodDigestEntry(
                entityRef = filed.entityRef,
                period = filed.period,
                digestSequence = store.nextSequence(tenant, filed.entityRef, filed.period),
                returnVersion = filed.returnVersion,
                filedAt = filed.filedAt,
                recordedAt = clock.instant().truncatedTo(ChronoUnit.MICROS), // the precision of timestamptz
                eventId = filed.eventId,
                chains = chains,
                digest = periodDigest(filed.entityRef, filed.period, filed.returnVersion, chains),
            )
        val retainUntil = entry.recordedAt.atZone(ZoneOffset.UTC).plusYears(retention.longest().years.toLong())
        objects.putOrVerify(tenant, entry, retainUntil.toInstant())
        store.insert(tenant, entry)
        return entry
    }
}
