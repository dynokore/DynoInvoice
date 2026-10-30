package com.dynokore.gst.evidence.adapters.jobs

import com.dynokore.gst.evidence.application.integrity.ChainFailure
import com.dynokore.gst.evidence.application.integrity.ChainReads
import com.dynokore.gst.evidence.application.integrity.ChainVerification
import com.dynokore.gst.evidence.application.integrity.IntegrityFailureRow
import com.dynokore.gst.evidence.application.integrity.IntegrityRunRow
import com.dynokore.gst.evidence.application.integrity.IntegrityStore
import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.gst.evidence.domain.integrity.evidenceType
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.jobs.JobBody
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntitySource
import com.dynokore.platform.serviceruntime.jobs.JobRunContext
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * The (tenant, entity) pairs the verification job runs for (INV-Q-7251 A (4)): key columns from the SECURITY DEFINER
 * function `verification_entities`, owned by `evidence_sys` (TPL-09, ARC 4.3 step 1a), read as the platform tenant
 * the way service-runtime's own cross-tenant maintenance reads its sys functions.
 *
 * The reserved tenant-wide entity of the audit chains (`EntityRef.TENANT_WIDE`) cannot be a [JobTarget.Entity]
 * (service-runtime refuses it), so a pair that names it is left out here and those chains are not verified by this
 * task; INV-T-13295 and INV-T-13297 add them (INV-Q-12984 A). The SQL function lists them without a filter.
 */
class VerificationEntitySource(
    private val scope: TenantScope,
    private val dsl: DSLContext,
) : JobEntitySource {
    private val mapper = JsonMapper.builder().build()

    override fun entities(job: JobDefinition): List<JobTarget.Entity> =
        scope.bindWorkload(TenantId.PLATFORM) {
            scope.transactions.inTransaction {
                val json =
                    dsl
                        .select(
                            DSL.function(DSL.name("evidence", "verification_entities"), SQLDataType.JSONB),
                        ).fetchOne()
                        ?.value1()
                        ?.data()
                        ?: "[]"
                val pairs = mapper.readTree(json)
                (0 until pairs.size())
                    .map { pairs[it] }
                    .map {
                        EntityRef(it["entity_ref"].asString()) to
                            TenantId(UUID.fromString(it["tenant_id"].asString()))
                    }.filterNot { (entity, _) -> entity.isTenantWide }
                    .map { (entity, tenant) -> JobTarget.Entity(tenant, entity) }
            }
        }
}

/**
 * Records one verification of an (entity, period) in the caller's tenant transaction (INV-Q-7251 A (4), INV-Q-7253 A
 * (1) to (4)): it runs [ChainVerification], appends each finding that is not open yet to `integrity_failure` with one
 * `EvidenceIntegrityFailed` in the outbox, and appends one `integrity_run` row. A repeat of an open finding appends
 * neither a row nor an event. It stops nothing else: evidence-svc has no call on the submission path.
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
        outcome.failures.forEach { record(tenant, entityRef, period, it, runAt, runId) }
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
        entityRef: String,
        period: String,
        failure: ChainFailure,
        runAt: Instant,
        runId: String,
    ) {
        val row =
            IntegrityFailureRow(
                entityRef,
                period,
                failure.kind,
                failure.failure,
                failure.failingSequence,
                failure.rangeFrom,
                failure.rangeTo,
                runAt,
                runId,
            )
        if (store.failureExists(tenant, row)) return
        store.insertFailure(tenant, row)
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, payload(row))),
            EventContext(
                tenant,
                EntityRef(entityRef),
                null,
                null,
                "$entityRef:$period",
                null,
                CorrelationId(runId),
                CausationId(runId),
            ),
        )
    }

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
    }
}

/**
 * The body of `evidence-chain-verification` for one entity: one tenant transaction per period, so a period that
 * cannot be recorded rolls back alone and the others are still recorded. A period whose chain or object copy could not
 * be read is recorded as a FAILED run with cause UNREADABLE (INV-Q-12988 A); the body then fails the job run at the
 * end, after every period is recorded, so the run never reports a period it could not read as verified.
 */
class ChainVerificationJobBody(
    private val service: String,
    private val scope: TenantScope,
    private val recorder: ChainVerificationRecorder,
    private val newRunId: () -> String = { UUID.randomUUID().toString() },
) : JobBody {
    @Suppress("TooGenericExceptionCaught")
    override fun run(context: JobRunContext) {
        val tenant = context.target.tenant
        val entity = context.target.entity.value
        val actor = WorkloadPrincipal(service).job(context.job.name)
        val runId = newRunId()

        fun <T> inTenant(block: () -> T): T = scope.bind(tenant, actor) { scope.transactions.inTransaction(block) }

        var failure: Exception? = null
        for (period in inTenant { recorder.periods(tenant, entity) }) {
            try {
                val cause = inTenant { recorder.verifyPeriod(tenant, entity, period, runId) }
                if (cause == RunCause.UNREADABLE) {
                    failure = failure ?: IllegalStateException("evidence chain of $entity $period is unreadable")
                }
            } catch (e: Exception) {
                failure = failure ?: e
            }
        }
        failure?.let { throw it }
    }
}
