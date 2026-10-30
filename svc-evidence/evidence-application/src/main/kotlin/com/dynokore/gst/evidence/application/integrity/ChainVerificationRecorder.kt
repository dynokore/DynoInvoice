package com.dynokore.gst.evidence.application.integrity

import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.gst.evidence.domain.integrity.evidenceType
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Records one verification of an (entity, period) in the caller's tenant transaction (INV-Q-7251 A (4), INV-Q-7253 A
 * (1) to (4)): it runs [ChainVerification], appends each finding that is not open yet to `integrity_failure` with one
 * `EvidenceIntegrityFailed` in the outbox, or one `TenantWideEvidenceIntegrityFailed` when the entity is the
 * tenant-wide one (INV-Q-12994 A), and appends one `integrity_run` row. A repeat of an open finding appends neither a
 * row nor an event. It stops nothing else: evidence-svc has no call on the submission path.
 */
class ChainVerificationRecorder(
    private val verification: ChainVerification,
    private val chains: ChainReads,
    private val store: IntegrityStore,
    private val outbox: OutboxWriter,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun periods(
        tenant: TenantId,
        entityRef: String,
    ): List<String> = chains.periods(tenant, entityRef)

    fun verifyPeriod(
        tenant: TenantId,
        entityRef: String,
        period: String,
        runId: String,
    ): RunCause {
        store.lock(tenant, entityRef, period)
        val runAt = clock.instant().truncatedTo(ChronoUnit.MICROS) // the precision of timestamptz
        val outcome = verification.verifyPeriod(tenant, entityRef, period)
        outcome.failures.forEach {
            record(
                tenant,
                IntegrityFailureRow(
                    entityRef,
                    period,
                    it.kind,
                    it.failure,
                    it.failingSequence,
                    it.rangeFrom,
                    it.rangeTo,
                    runAt,
                    runId,
                ),
            )
        }
        store.insertRun(
            tenant,
            IntegrityRunRow(
                entityRef,
                period,
                runId,
                runAt,
                outcome.result,
                outcome.cause,
                outcome.chainLength,
                outcome.periodDigest,
            ),
        )
        return outcome.cause
    }

    private fun record(
        tenant: TenantId,
        row: IntegrityFailureRow,
    ) {
        if (store.failureExists(tenant, row)) return
        store.insertFailure(tenant, row)
        outbox.append(
            listOf(OutboundEvent(eventType(row), payload(row))),
            EventContext(
                tenant,
                EntityRef(row.entityRef),
                null,
                null,
                "${row.entityRef}:${row.period}",
                null,
                CorrelationId(row.runId),
                CausationId(row.runId),
            ),
        )
    }

    /** The platform event for the tenant-wide entity, which a domain event may not name; the domain event otherwise. */
    private fun eventType(row: IntegrityFailureRow): String =
        if (EntityRef(row.entityRef).isTenantWide) TENANT_WIDE_EVENT_TYPE else EVENT_TYPE

    /** The payload of `EvidenceIntegrityFailed`: the range and the failing record's key, nothing of its content. */
    private fun payload(row: IntegrityFailureRow): String =
        "{\"range\":{\"from\":${quoted(iso(row.rangeFrom))},\"to\":${quoted(iso(row.rangeTo))}}," +
            "\"failingRecord\":{\"entityRef\":${quoted(row.entityRef)},\"periodRef\":${quoted(row.period)}," +
            "\"evidenceType\":${quoted(row.kind.evidenceType)},\"sequence\":${row.failingSequence ?: "null"}," +
            "\"kind\":${quoted(row.failure.name)}}}"

    private fun iso(at: Instant): String = DateTimeFormatter.ISO_INSTANT.format(at)

    private fun quoted(text: String): String {
        val out = StringBuilder("\"")
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append("\\u%04x".format(Locale.ROOT, c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.evidence.EvidenceIntegrityFailed.v1"
        const val TENANT_WIDE_EVENT_TYPE = "com.dynokore.gst.platform.TenantWideEvidenceIntegrityFailed.v1"
    }
}
