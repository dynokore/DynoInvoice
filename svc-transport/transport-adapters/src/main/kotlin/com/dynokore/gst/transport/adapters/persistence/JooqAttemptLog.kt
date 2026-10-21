package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.AttemptEgress.Companion.ATTEMPT_EGRESS
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.SubmissionAttempt.Companion.SUBMISSION_ATTEMPT
import com.dynokore.gst.transport.application.AttemptLog
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.AttemptCounts
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.FailureReason
import com.dynokore.gst.transport.domain.OutcomeKind
import com.dynokore.gst.transport.domain.TransportOutcome
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The attempt log over jOOQ (INV-REQ-538). Every write is in the command's tenant binding and transaction; the rows
 * are tenant data under forced row level security. The failure rate reads the finished attempts of all tenants
 * through the sys-owned count function, which returns counts only (INV-REQ-565).
 */
class JooqAttemptLog(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
    private val platform: PlatformTenantBinding,
    private val workloadPrincipal: String,
) : AttemptLog {
    private val mapper = JsonMapper.builder().build()

    override fun adapterInFlight(doc: DocRef): AdapterId? =
        runner.inTransaction {
            dsl
                .select(SUBMISSION_ATTEMPT.ADAPTER_ID)
                .from(SUBMISSION_ATTEMPT)
                .where(SUBMISSION_ATTEMPT.DOC_UUID.eq(doc.docUuid), SUBMISSION_ATTEMPT.VERSION.eq(doc.version))
                .orderBy(SUBMISSION_ATTEMPT.STARTED_AT.asc())
                .limit(1)
                .fetchOne(SUBMISSION_ATTEMPT.ADAPTER_ID)
                ?.let(::AdapterId)
        }

    override fun entityOf(docUuid: UUID): EntityRef? =
        runner.inTransaction {
            dsl
                .select(SUBMISSION_ATTEMPT.ENTITY_REF)
                .from(SUBMISSION_ATTEMPT)
                .where(SUBMISSION_ATTEMPT.DOC_UUID.eq(docUuid))
                .orderBy(SUBMISSION_ATTEMPT.STARTED_AT.asc())
                .limit(1)
                .fetchOne(SUBMISSION_ATTEMPT.ENTITY_REF)
                ?.let(::EntityRef)
        }

    override fun acceptedAt(doc: DocRef): Instant? =
        runner.inTransaction {
            dsl
                .select(SUBMISSION_ATTEMPT.FINISHED_AT)
                .from(SUBMISSION_ATTEMPT)
                .where(
                    SUBMISSION_ATTEMPT.DOC_UUID.eq(doc.docUuid),
                    SUBMISSION_ATTEMPT.VERSION.eq(doc.version),
                    SUBMISSION_ATTEMPT.OUTCOME.eq(OutcomeKind.ACCEPTED.name),
                ).orderBy(SUBMISSION_ATTEMPT.STARTED_AT.asc())
                .limit(1)
                .fetchOne(SUBMISSION_ATTEMPT.FINISHED_AT)
                ?.toInstant()
        }

    override fun settledOutcome(doc: DocRef): TransportOutcome? =
        runner.inTransaction {
            val row =
                dsl
                    .selectFrom(SUBMISSION_ATTEMPT)
                    .where(
                        SUBMISSION_ATTEMPT.DOC_UUID.eq(doc.docUuid),
                        SUBMISSION_ATTEMPT.VERSION.eq(doc.version),
                        SUBMISSION_ATTEMPT.OUTCOME.`in`(OutcomeKind.ACCEPTED.name, OutcomeKind.REJECTED.name),
                    ).orderBy(SUBMISSION_ATTEMPT.STARTED_AT.asc())
                    .limit(1)
                    .fetchOne()
            when (row?.outcome) {
                OutcomeKind.ACCEPTED.name -> TransportOutcome.Accepted(checkNotNull(row.acknowledgementId))
                OutcomeKind.REJECTED.name -> TransportOutcome.Rejected(readReasons(checkNotNull(row.reasons)))
                else -> null
            }
        }

    override fun begin(
        entity: EntityRef,
        doc: DocRef,
        adapter: AdapterId,
        at: Instant,
    ): UUID {
        val attempt = UUID.randomUUID()
        val tenant = tenants.current().value
        val startedAt = at.atOffset(ZoneOffset.UTC)
        runner.inTransaction {
            dsl
                .insertInto(SUBMISSION_ATTEMPT)
                .set(SUBMISSION_ATTEMPT.ID, attempt)
                .set(SUBMISSION_ATTEMPT.TENANT_ID, tenant)
                .set(SUBMISSION_ATTEMPT.ENTITY_REF, entity.value)
                .set(SUBMISSION_ATTEMPT.DOC_UUID, doc.docUuid)
                .set(SUBMISSION_ATTEMPT.VERSION, doc.version)
                .set(SUBMISSION_ATTEMPT.ADAPTER_ID, adapter.value)
                .set(SUBMISSION_ATTEMPT.STARTED_AT, startedAt)
                .execute()
            dsl
                .insertInto(ATTEMPT_EGRESS)
                .set(ATTEMPT_EGRESS.ID, UUID.randomUUID())
                .set(ATTEMPT_EGRESS.TENANT_ID, tenant)
                .set(ATTEMPT_EGRESS.ATTEMPT_ID, attempt)
                .set(ATTEMPT_EGRESS.ADAPTER_ID, adapter.value)
                .set(ATTEMPT_EGRESS.SENT_AT, startedAt)
                .execute()
        }
        return attempt
    }

    override fun complete(
        attempt: UUID,
        outcome: TransportOutcome,
        at: Instant,
    ) {
        runner.inTransaction {
            val update =
                dsl
                    .update(SUBMISSION_ATTEMPT)
                    .set(SUBMISSION_ATTEMPT.OUTCOME, outcome.kind.name)
                    .set(SUBMISSION_ATTEMPT.FINISHED_AT, at.atOffset(ZoneOffset.UTC))
            when (outcome) {
                is TransportOutcome.Accepted -> {
                    update.set(SUBMISSION_ATTEMPT.ACKNOWLEDGEMENT_ID, outcome.acknowledgementId)
                }

                is TransportOutcome.Rejected -> {
                    update.set(SUBMISSION_ATTEMPT.REASONS, writeReasons(outcome.reasons))
                }

                TransportOutcome.TransientFailure -> {
                    update
                }
            }.where(SUBMISSION_ATTEMPT.ID.eq(attempt)).execute()
        }
    }

    override fun counts(
        adapter: AdapterId,
        since: Instant,
    ): AttemptCounts = AdapterAttemptCountsQuery.counts(dsl, runner, platform, workloadPrincipal, adapter, since)

    private fun writeReasons(reasons: List<FailureReason>): String =
        mapper.writeValueAsString(
            reasons.map { mapOf("code" to it.code, "field" to it.field, "message" to it.message) },
        )

    private fun readReasons(json: String): List<FailureReason> =
        mapper.readTree(json).toList().map { node ->
            FailureReason(
                node.get("code").asString(),
                node.get("field")?.takeUnless { it.isNull }?.asString(),
                node.get("message").asString(),
            )
        }
}
