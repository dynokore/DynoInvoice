package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.DRY_RUN_DELTA
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_DELTA_ACK
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_READINESS_ACK
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_REGISTRY
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.TENANTS_WITH_DELTA
import com.dynokore.gst.rules.application.ArmingStore
import com.dynokore.gst.rules.application.ArmingWrite
import com.dynokore.gst.rules.application.CompleteDeltaReader
import com.dynokore.gst.rules.application.DeltaAcknowledgement
import com.dynokore.gst.rules.application.DeltaAcknowledgementStore
import com.dynokore.gst.rules.application.PackEvent
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.application.RegistryPackRow
import com.dynokore.gst.rules.application.RegistryRowReader
import com.dynokore.gst.rules.application.StoredReadiness
import com.dynokore.gst.rules.application.StoredReadinessReader
import com.dynokore.gst.rules.application.TenantDelta
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.rulepackkernel.fence.ReadinessFailure
import com.dynokore.platform.rulepackkernel.fence.ReadinessOutcome
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The arming records over `rules.pack_delta_ack`, `rules.pack_activation_review` and `rules.pack_readiness_ack`
 * (INV-T-22942, INV-REQ-952), jOOQ only, written like [PersistedRegulatoryPackStore]. The platform-side reads and the
 * arming write run under the platform binding, each in its own transaction; a tenant's own delta and acknowledgement
 * run under the caller's tenant binding, whose row-level policies hide every other tenant's rows. The tenants that
 * hold a delta are listed by `rules.tenants_with_delta`, and the arming is written by `rules.arm_platform_pack`
 * (SECURITY DEFINER, platform binding only): the registry status ARMED, the review rows and the `RulePackActivated`
 * outbox row commit together or not at all.
 */
@Suppress("LongParameterList", "TooManyFunctions") // one store behind five ports of the arming use cases
class JooqPackArmingStore(
    private val dsl: DSLContext,
    private val platform: PlatformTenantBinding,
    private val transactions: TransactionRunner,
    private val events: PackEventEmitter,
) : RegistryRowReader,
    CompleteDeltaReader,
    StoredReadinessReader,
    ArmingStore,
    DeltaAcknowledgementStore {
    override fun find(
        packType: String,
        version: String,
    ): RegistryPackRow? =
        platform.bindPlatform(READER) {
            transactions.inTransaction {
                dsl
                    .select(PACK_REGISTRY.STATUS, PACK_REGISTRY.REGISTERED_AT, PACK_REGISTRY.EFFECTIVE_FROM)
                    .from(PACK_REGISTRY)
                    .where(PACK_REGISTRY.PACK_TYPE.eq(packType))
                    .and(PACK_REGISTRY.PACK_VERSION.eq(version))
                    .and(PACK_REGISTRY.TENANT_ID.eq(TenantId.PLATFORM.value))
                    .and(PACK_REGISTRY.LAYER.eq(REGULATORY))
                    .fetchOne()
                    ?.let {
                        RegistryPackRow(
                            status = PackStatus.valueOf(checkNotNull(it[PACK_REGISTRY.STATUS])),
                            registeredAt = checkNotNull(it[PACK_REGISTRY.REGISTERED_AT]).toInstant(),
                            effectiveFrom = checkNotNull(it[PACK_REGISTRY.EFFECTIVE_FROM]),
                        )
                    }
            }
        }

    override fun tenantsWithCompleteDelta(
        packType: String,
        version: String,
    ): List<TenantDelta> =
        platform.bindPlatform(READER) {
            transactions.inTransaction {
                dsl
                    .selectFrom(TENANTS_WITH_DELTA.call(packType, version))
                    .fetch()
                    .map {
                        TenantDelta(
                            tenantId = checkNotNull(it[TENANTS_WITH_DELTA.DELTA_TENANT_ID]).toString(),
                            completedAt = checkNotNull(it[TENANTS_WITH_DELTA.COMPLETED_AT]).toInstant(),
                            acknowledged = checkNotNull(it[TENANTS_WITH_DELTA.ACKNOWLEDGED]),
                        )
                    }
            }
        }

    /** Tenant-bound: the row-level policy of `rules.dry_run_delta` admits the calling tenant's own rows only. */
    override fun completedAt(
        tenantId: String,
        packType: String,
        version: String,
    ): Instant? =
        dsl
            .select(DSL.max(DRY_RUN_DELTA.COMPLETED_AT))
            .from(DRY_RUN_DELTA)
            .where(DRY_RUN_DELTA.TENANT_ID.eq(UUID.fromString(tenantId)))
            .and(DRY_RUN_DELTA.PACK_TYPE.eq(packType))
            .and(DRY_RUN_DELTA.PACK_VERSION.eq(version))
            .and(DRY_RUN_DELTA.STATE.eq(COMPLETE))
            .fetchOne(0, OffsetDateTime::class.java)
            ?.toInstant()

    override fun answers(
        packType: String,
        version: String,
    ): List<StoredReadiness> =
        platform.bindPlatform(READER) {
            transactions.inTransaction {
                dsl
                    .selectFrom(PACK_READINESS_ACK)
                    .where(PACK_READINESS_ACK.PACK_TYPE.eq(packType))
                    .and(PACK_READINESS_ACK.PACK_VERSION.eq(version))
                    .orderBy(PACK_READINESS_ACK.INSTANCE)
                    .fetch()
                    .map {
                        StoredReadiness(checkNotNull(it.instance), outcome(checkNotNull(it.outcome), it.failureReason))
                    }
            }
        }

    /**
     * One transaction: the review rows and the status ARMED through the definer function, then the `RulePackActivated`
     * outbox row. Any failure rolls the three back together.
     */
    override fun arm(write: ArmingWrite) {
        platform.bindPlatform(ARMER) {
            transactions.inTransaction {
                dsl
                    .select(
                        DSL.function(
                            DSL.name("rules", "arm_platform_pack"),
                            SQLDataType.OTHER,
                            DSL.`val`(write.packType, SQLDataType.CLOB),
                            DSL.`val`(write.version, SQLDataType.CLOB),
                            DSL.`val`(tenantIds(write), SQLDataType.UUID.array()),
                            DSL.`val`(write.reviews.map { it.reviewed }.toTypedArray(), SQLDataType.BOOLEAN.array()),
                            DSL.`val`(write.reviews.map { it.decidedOn }.toTypedArray(), SQLDataType.LOCALDATE.array()),
                        ),
                    ).execute()
                events.emit(
                    listOf(PackEvent.RulePackActivated(write.packType, write.version, write.effectiveFrom)),
                    activationContext(write),
                )
            }
        }
    }

    /**
     * Stores one readiness answer in the caller's open transaction (the inbox consumer's). The latest answer of an
     * instance wins: an answer with an older [receivedAt] than the stored one changes nothing. True when a row was
     * written, false when the stored answer is newer.
     */
    @Suppress("LongParameterList") // one parameter per column of the row
    fun recordReadiness(
        packType: String,
        version: String,
        instance: String,
        outcome: String,
        failureReason: String?,
        receivedAt: Instant,
    ): Boolean =
        dsl
            .insertInto(PACK_READINESS_ACK)
            .set(PACK_READINESS_ACK.PACK_TYPE, packType)
            .set(PACK_READINESS_ACK.PACK_VERSION, version)
            .set(PACK_READINESS_ACK.INSTANCE, instance)
            .set(PACK_READINESS_ACK.OUTCOME, outcome)
            .set(PACK_READINESS_ACK.FAILURE_REASON, failureReason)
            .set(PACK_READINESS_ACK.RECEIVED_AT, receivedAt.atOffset(ZoneOffset.UTC))
            .onConflict(PACK_READINESS_ACK.PACK_TYPE, PACK_READINESS_ACK.PACK_VERSION, PACK_READINESS_ACK.INSTANCE)
            .doUpdate()
            .set(PACK_READINESS_ACK.OUTCOME, DSL.excluded(PACK_READINESS_ACK.OUTCOME))
            .set(PACK_READINESS_ACK.FAILURE_REASON, DSL.excluded(PACK_READINESS_ACK.FAILURE_REASON))
            .set(PACK_READINESS_ACK.RECEIVED_AT, DSL.excluded(PACK_READINESS_ACK.RECEIVED_AT))
            .where(PACK_READINESS_ACK.RECEIVED_AT.le(DSL.excluded(PACK_READINESS_ACK.RECEIVED_AT)))
            .execute() > 0

    /** Tenant-bound; the first row stays: a second call changes nothing, not even the acknowledging user. */
    override fun recordOnce(acknowledgement: DeltaAcknowledgement) {
        dsl
            .insertInto(PACK_DELTA_ACK)
            .set(PACK_DELTA_ACK.TENANT_ID, UUID.fromString(acknowledgement.tenantId))
            .set(PACK_DELTA_ACK.PACK_TYPE, acknowledgement.packType)
            .set(PACK_DELTA_ACK.PACK_VERSION, acknowledgement.version)
            .set(PACK_DELTA_ACK.ACKNOWLEDGED_BY, acknowledgement.acknowledgedBy)
            .set(PACK_DELTA_ACK.ACKNOWLEDGED_AT, acknowledgement.acknowledgedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute()
    }

    override fun find(
        tenantId: String,
        packType: String,
        version: String,
    ): DeltaAcknowledgement? =
        dsl
            .selectFrom(PACK_DELTA_ACK)
            .where(PACK_DELTA_ACK.TENANT_ID.eq(UUID.fromString(tenantId)))
            .and(PACK_DELTA_ACK.PACK_TYPE.eq(packType))
            .and(PACK_DELTA_ACK.PACK_VERSION.eq(version))
            .fetchOne()
            ?.let {
                DeltaAcknowledgement(
                    tenantId = tenantId,
                    packType = packType,
                    version = version,
                    acknowledgedBy = checkNotNull(it.acknowledgedBy),
                    acknowledgedAt = checkNotNull(it.acknowledgedAt).toInstant(),
                )
            }

    private fun tenantIds(write: ArmingWrite): Array<UUID> =
        write.reviews.map { UUID.fromString(it.tenantId) }.toTypedArray()

    private fun activationContext(write: ArmingWrite): EventContext {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        return EventContext(
            tenant = TenantId.PLATFORM,
            entity = EntityRef.PLATFORM,
            docUuid = null,
            transactionDate = null,
            aggregateKey = "${write.packType}@${write.version}",
            aggregateVersion = null,
            correlation = CorrelationId(correlation),
            causation = CausationId(causation),
        )
    }

    private fun outcome(
        outcome: String,
        reason: String?,
    ): ReadinessOutcome =
        when (outcome) {
            "LOADED" -> ReadinessOutcome.Loaded
            else -> ReadinessOutcome.Failed(failure(reason), reason.orEmpty())
        }

    /**
     * The contract carries the failure reason as free text; one that names no [ReadinessFailure] reads as the
     * first.
     */
    private fun failure(reason: String?): ReadinessFailure =
        ReadinessFailure.entries.firstOrNull { it.name == reason } ?: ReadinessFailure.VERIFICATION_FAILED

    private companion object {
        /** The workload principals the platform-bound calls run as (ARC-21). */
        const val READER = "svc:rules-svc/op:arm-regulatory-pack"
        const val ARMER = READER
        const val REGULATORY = "REGULATORY"
        const val COMPLETE = "COMPLETE"
    }
}
