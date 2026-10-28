package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.Acknowledgement.Companion.ACKNOWLEDGEMENT
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.DecisionTrace.Companion.DECISION_TRACE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.EvidencePayload.Companion.EVIDENCE_PAYLOAD
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PeriodDigest.Companion.PERIOD_DIGEST
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PeriodDigestChain.Companion.PERIOD_DIGEST_CHAIN
import com.dynokore.gst.evidence.application.digest.ChainTails
import com.dynokore.gst.evidence.application.digest.PeriodDigestEntry
import com.dynokore.gst.evidence.application.digest.PeriodDigestStore
import com.dynokore.gst.evidence.domain.digest.ChainTail
import com.dynokore.gst.evidence.domain.digest.DigestChainType
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.util.UUID

/** `period_digest` and `period_digest_chain` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqPeriodDigestStore(
    private val dsl: DSLContext,
) : PeriodDigestStore {
    override fun lock(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ) {
        val key = DSL.`val`("period-digest:${tenant.value}:$entityRef:$period")
        val hash = DSL.function(DSL.name("hashtextextended"), SQLDataType.BIGINT, key, DSL.`val`(0L))
        dsl.select(DSL.function(DSL.name("pg_advisory_xact_lock"), SQLDataType.OTHER, hash)).fetch()
    }

    override fun nextSequence(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): Int =
        (
            dsl
                .select(DSL.max(PERIOD_DIGEST.DIGEST_SEQ))
                .from(PERIOD_DIGEST)
                .where(
                    PERIOD_DIGEST.TENANT_ID.eq(tenant.value),
                    PERIOD_DIGEST.ENTITY_REF.eq(entityRef),
                    PERIOD_DIGEST.PERIOD_REF.eq(period),
                ).fetchOne(0, Int::class.java) ?: 0
        ) + 1

    override fun insert(
        tenant: TenantId,
        entry: PeriodDigestEntry,
    ) {
        dsl
            .insertInto(PERIOD_DIGEST)
            .set(PERIOD_DIGEST.TENANT_ID, tenant.value)
            .set(PERIOD_DIGEST.ENTITY_REF, entry.entityRef)
            .set(PERIOD_DIGEST.PERIOD_REF, entry.period)
            .set(PERIOD_DIGEST.DIGEST_SEQ, entry.digestSequence)
            .set(PERIOD_DIGEST.RETURN_VERSION, entry.returnVersion)
            .set(PERIOD_DIGEST.FILED_AT, entry.filedAt.utc())
            .set(PERIOD_DIGEST.RECORDED_AT, entry.recordedAt.utc())
            .set(PERIOD_DIGEST.SOURCE_EVENT_ID, entry.eventId)
            .set(PERIOD_DIGEST.DIGEST, entry.digest)
            .execute()
        entry.chains.forEach {
            dsl
                .insertInto(PERIOD_DIGEST_CHAIN)
                .set(PERIOD_DIGEST_CHAIN.TENANT_ID, tenant.value)
                .set(PERIOD_DIGEST_CHAIN.ENTITY_REF, entry.entityRef)
                .set(PERIOD_DIGEST_CHAIN.PERIOD_REF, entry.period)
                .set(PERIOD_DIGEST_CHAIN.DIGEST_SEQ, entry.digestSequence)
                .set(PERIOD_DIGEST_CHAIN.EVIDENCE_TYPE, it.type.name)
                .set(PERIOD_DIGEST_CHAIN.CHAIN_LENGTH, it.length)
                .set(PERIOD_DIGEST_CHAIN.TAIL_HASH, it.tailText)
                .execute()
        }
    }
}

/** The tail of the three document-bound chain tables: the record of the highest `seq` (a chain starts at 0). */
class JooqChainTails(
    private val dsl: DSLContext,
) : ChainTails {
    override fun tail(
        tenant: TenantId,
        entityRef: String,
        period: String,
        type: DigestChainType,
    ): ChainTail =
        when (type) {
            DigestChainType.PAYLOAD -> {
                tail(
                    EVIDENCE_PAYLOAD,
                    EVIDENCE_PAYLOAD.TENANT_ID,
                    EVIDENCE_PAYLOAD.ENTITY_REF,
                    EVIDENCE_PAYLOAD.PERIOD_REF,
                    EVIDENCE_PAYLOAD.SEQ,
                    EVIDENCE_PAYLOAD.CONTENT_HASH,
                    tenant.value,
                    entityRef,
                    period,
                    type,
                )
            }

            DigestChainType.ACKNOWLEDGEMENT -> {
                tail(
                    ACKNOWLEDGEMENT,
                    ACKNOWLEDGEMENT.TENANT_ID,
                    ACKNOWLEDGEMENT.ENTITY_REF,
                    ACKNOWLEDGEMENT.PERIOD_REF,
                    ACKNOWLEDGEMENT.SEQ,
                    ACKNOWLEDGEMENT.CONTENT_HASH,
                    tenant.value,
                    entityRef,
                    period,
                    type,
                )
            }

            DigestChainType.DECISION_TRACE -> {
                tail(
                    DECISION_TRACE,
                    DECISION_TRACE.TENANT_ID,
                    DECISION_TRACE.ENTITY_REF,
                    DECISION_TRACE.PERIOD_REF,
                    DECISION_TRACE.SEQ,
                    DECISION_TRACE.CONTENT_HASH,
                    tenant.value,
                    entityRef,
                    period,
                    type,
                )
            }
        }

    @Suppress("LongParameterList") // the columns of one chain table and the key of one chain
    private fun tail(
        table: Table<*>,
        tenantColumn: Field<UUID?>,
        entityColumn: Field<String?>,
        periodColumn: Field<String?>,
        seqColumn: Field<Long?>,
        hashColumn: Field<String?>,
        tenant: UUID,
        entityRef: String,
        period: String,
        type: DigestChainType,
    ): ChainTail {
        val last =
            dsl
                .select(seqColumn, hashColumn)
                .from(table)
                .where(tenantColumn.eq(tenant), entityColumn.eq(entityRef), periodColumn.eq(period))
                .orderBy(seqColumn.desc())
                .limit(1)
                .fetchOne()
        return if (last == null) {
            ChainTail(type, 0, null)
        } else {
            ChainTail(type, checkNotNull(last.get(seqColumn)) + 1, checkNotNull(last.get(hashColumn)))
        }
    }
}
