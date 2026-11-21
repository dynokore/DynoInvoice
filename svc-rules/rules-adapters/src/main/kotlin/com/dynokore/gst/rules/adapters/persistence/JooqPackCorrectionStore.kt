package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_REGISTRY
import com.dynokore.gst.rules.application.CorrectionPackRow
import com.dynokore.gst.rules.application.PackCorrectionRegistry
import com.dynokore.gst.rules.application.PackCorrectionRegistryTx
import com.dynokore.gst.rules.application.PackEvent
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.domain.pack.EarlierPack
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.SupersedingSpec
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.util.UUID

/**
 * The registry side of a pack correction over `rules.pack_registry` (INV-T-4978; INV-REQ-962, INV-REQ-1002), jOOQ only.
 * The superseding row is inserted ARMED by `rules.register_superseding_pack` (SECURITY DEFINER, platform binding only)
 * already carrying `artefact_version`, the pack_version of the earlier pack whose signed artefact rows it uses: no
 * artefact row is copied, renamed or re-signed, and the bad pack row is never written. The reads and the write run
 * under the platform binding in one transaction, so the row commits together with whatever the caller does in
 * the same [inTransaction], or not at all.
 */
class JooqPackCorrectionStore(
    private val dsl: DSLContext,
    private val platform: PlatformTenantBinding,
    private val transactions: TransactionRunner,
    private val events: PackEventEmitter,
) : PackCorrectionRegistry {
    /** The correction in flight on this thread, set by [correct]. */
    private class Correction(
        val packType: String,
        val actor: String,
        val hint: String,
    )

    private val inFlight = ThreadLocal<Correction?>()

    /** The hint read before the transaction no longer names the current active pack row. */
    private class StaleHint : RuntimeException(null, null, false, false)

    /** The correction kept losing the race for the current active pack row. */
    class StaleCorrectionException : RuntimeException("the active pack changed during the correction")

    override fun <T> inTransaction(work: (PackCorrectionRegistryTx) -> T): T =
        platform.bindPlatform(WORKLOAD) {
            transactions.inTransaction {
                inFlight.get()?.let { c ->
                    // pessimistic lock on the current active row, then the hint is compared inside the transaction
                    if (lockActive(c.packType).orEmpty() != c.hint) throw StaleHint()
                }
                work(Tx())
            }
        }

    /**
     * A rollback of [packType] by the user [actor] (the request names the pack type and the date only). The current
     * active pack row (the latest ARMED non-superseding platform row) is read as a hint and handed to [work]; the
     * registry transaction [work] opens (it is the use case's, after its permission decision) locks that row
     * `FOR UPDATE` first and compares it with the hint, then date comparison and supersede run in the same
     * transaction. A concurrent change of the active row is retried; two rollbacks of one type serialise on the
     * lock, with no read-then-write race. The superseding row records [actor] as `activated_by` (INV-Q-10647) and
     * `RulePackActivated` goes out through the outbox in the same transaction.
     */
    fun <T> correct(
        packType: String,
        actor: String,
        work: (badVersion: String) -> T,
    ): T {
        repeat(ATTEMPTS) {
            val hint = badVersionOf(packType).orEmpty()
            inFlight.set(Correction(packType, actor, hint))
            try {
                return work(hint)
            } catch (_: StaleHint) {
                // another correction changed the active row between the read and the lock: read again
            } finally {
                inFlight.remove()
            }
        }
        throw StaleCorrectionException()
    }

    /**
     * The version of the armed pack of [packType] that a correction supersedes: the latest ARMED platform pack that is
     * not itself a superseding row, or null.
     */
    fun badVersionOf(packType: String): String? = armedVersion(packType, superseding = false)

    /** The version of the latest ARMED superseding pack of [packType], or null: the pack a reprocessing run uses. */
    fun supersedingVersionOf(packType: String): String? = armedVersion(packType, superseding = true)

    /** True when [version] of [packType] is an ARMED platform pack row. Read in the caller's open transaction. */
    fun isArmed(
        packType: String,
        version: String,
    ): Boolean =
        dsl.fetchExists(
            PACK_REGISTRY,
            PACK_REGISTRY.PACK_TYPE.eq(packType),
            PACK_REGISTRY.PACK_VERSION.eq(version),
            PACK_REGISTRY.TENANT_ID.eq(TenantId.PLATFORM.value),
            PACK_REGISTRY.STATUS.eq(ARMED),
        )

    /** The `effective_from` of a platform pack row, read in the caller's open transaction. */
    fun effectiveFromOf(
        packType: String,
        version: String,
    ): java.time.LocalDate? =
        dsl
            .select(PACK_REGISTRY.EFFECTIVE_FROM)
            .from(PACK_REGISTRY)
            .where(PACK_REGISTRY.PACK_TYPE.eq(packType))
            .and(PACK_REGISTRY.PACK_VERSION.eq(version))
            .and(PACK_REGISTRY.TENANT_ID.eq(TenantId.PLATFORM.value))
            .fetchOne(PACK_REGISTRY.EFFECTIVE_FROM)

    private fun armedVersion(
        packType: String,
        superseding: Boolean,
    ): String? =
        platform.bindPlatform(WORKLOAD) {
            transactions.inTransaction { readVersion(packType, superseding) }
        }

    /** The latest ARMED platform row of [packType] (superseding or not), read without a lock. */
    private fun readVersion(
        packType: String,
        superseding: Boolean,
    ): String? =
        dsl
            .select(PACK_REGISTRY.PACK_VERSION)
            .from(PACK_REGISTRY)
            .where(PACK_REGISTRY.PACK_TYPE.eq(packType))
            .and(PACK_REGISTRY.TENANT_ID.eq(TenantId.PLATFORM.value))
            .and(PACK_REGISTRY.STATUS.eq(ARMED))
            .and(PACK_REGISTRY.ARTEFACT_VERSION.let { if (superseding) it.isNotNull else it.isNull })
            .orderBy(
                PACK_REGISTRY.EFFECTIVE_FROM.desc(),
                PACK_REGISTRY.REGISTERED_AT.desc(),
                PACK_REGISTRY.ID.desc(),
            ).limit(1)
            .fetchOne(PACK_REGISTRY.PACK_VERSION)

    /** Locks the current active row of [packType] `FOR UPDATE` through the sys-owned function; returns its version. */
    private fun lockActive(packType: String): String? =
        dsl
            .select(
                DSL.function(
                    DSL.name("rules", "lock_active_platform_pack"),
                    SQLDataType.CLOB,
                    DSL.`val`(packType, SQLDataType.CLOB),
                ),
            ).fetchOne(0, String::class.java)

    private inner class Tx : PackCorrectionRegistryTx {
        override fun find(
            packType: String,
            version: String,
        ): CorrectionPackRow? = row(packType, version)?.let(::rowOf)

        @Suppress("ReturnCount") // one early return per absent link, in order
        override fun latestSuperseded(bad: CorrectionPackRow): EarlierPack? {
            val predecessor = row(bad.packType, bad.version)?.get(PACK_REGISTRY.SUPERSEDES) ?: return null
            val earlier = row(bad.packType, predecessor) ?: return null
            val content = earlier.get(PACK_REGISTRY.ARTEFACT_VERSION) ?: predecessor
            return EarlierPack(content, content)
        }

        override fun insertSuperseding(
            spec: SupersedingSpec,
            copyFrom: String,
        ): CorrectionPackRow {
            val bad = checkNotNull(row(spec.packType, spec.version.removeSuffix(SUFFIX))) { "the bad pack row is gone" }
            dsl
                .select(
                    DSL.function(
                        DSL.name("rules", "register_superseding_pack"),
                        SQLDataType.UUID,
                        DSL.`val`(UUID.randomUUID(), SQLDataType.UUID),
                        DSL.`val`(bad.get(PACK_REGISTRY.PACK_ID), SQLDataType.CLOB),
                        DSL.`val`(spec.packType, SQLDataType.CLOB),
                        DSL.`val`(spec.version, SQLDataType.CLOB),
                        DSL.`val`(spec.effectiveFrom, SQLDataType.LOCALDATE),
                        DSL.`val`(bad.get(PACK_REGISTRY.MIN_ENGINE_VERSION), SQLDataType.CLOB),
                        DSL.`val`(bad.get(PACK_REGISTRY.PACK_VERSION), SQLDataType.CLOB),
                        DSL.`val`(copyFrom, SQLDataType.CLOB),
                        DSL.`val`(checkNotNull(inFlight.get()?.actor) { "no acting user is bound" }, SQLDataType.CLOB),
                    ),
                ).fetchOne()
            events.emit(
                listOf(PackEvent.RulePackActivated(spec.packType, spec.version, spec.effectiveFrom)),
                activationContext(spec),
            )
            return checkNotNull(find(spec.packType, spec.version)) {
                "the superseding row just written is not readable"
            }
        }

        private fun activationContext(spec: SupersedingSpec): EventContext {
            val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
            val causation = CorrelationContext.currentCausationId()?.value ?: correlation
            return EventContext(
                tenant = TenantId.PLATFORM,
                entity = EntityRef.PLATFORM,
                docUuid = null,
                transactionDate = null,
                aggregateKey = "${spec.packType}@${spec.version}",
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            )
        }

        private fun row(
            packType: String,
            version: String,
        ): Record? =
            dsl
                .selectFrom(PACK_REGISTRY)
                .where(PACK_REGISTRY.PACK_TYPE.eq(packType))
                .and(PACK_REGISTRY.PACK_VERSION.eq(version))
                .and(PACK_REGISTRY.TENANT_ID.eq(TenantId.PLATFORM.value))
                .fetchOne()

        private fun rowOf(r: Record) =
            CorrectionPackRow(
                packType = checkNotNull(r.get(PACK_REGISTRY.PACK_TYPE)),
                version = checkNotNull(r.get(PACK_REGISTRY.PACK_VERSION)),
                effectiveFrom = checkNotNull(r.get(PACK_REGISTRY.EFFECTIVE_FROM)),
                status = PackStatus.valueOf(checkNotNull(r.get(PACK_REGISTRY.STATUS))),
                contentRef = r.get(PACK_REGISTRY.ARTEFACT_VERSION) ?: checkNotNull(r.get(PACK_REGISTRY.PACK_VERSION)),
            )
    }

    private companion object {
        /** The workload principal the platform-bound calls run as (ARC-21). */
        const val WORKLOAD = "svc:rules-svc/op:rollback-pack"
        const val ARMED = "ARMED"
        const val ATTEMPTS = 3
        const val SUFFIX = "-rollback"
    }
}
