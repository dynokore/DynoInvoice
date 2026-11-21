package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_REGISTRY
import com.dynokore.gst.rules.application.CorrectionPackRow
import com.dynokore.gst.rules.application.PackCorrectionRegistry
import com.dynokore.gst.rules.application.PackCorrectionRegistryTx
import com.dynokore.gst.rules.domain.pack.EarlierPack
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.SupersedingSpec
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
) : PackCorrectionRegistry {
    override fun <T> inTransaction(work: (PackCorrectionRegistryTx) -> T): T =
        platform.bindPlatform(WORKLOAD) { transactions.inTransaction { work(Tx()) } }

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
            transactions.inTransaction {
                dsl
                    .select(PACK_REGISTRY.PACK_VERSION)
                    .from(PACK_REGISTRY)
                    .where(PACK_REGISTRY.PACK_TYPE.eq(packType))
                    .and(PACK_REGISTRY.TENANT_ID.eq(TenantId.PLATFORM.value))
                    .and(PACK_REGISTRY.STATUS.eq(ARMED))
                    .and(
                        PACK_REGISTRY.ARTEFACT_VERSION.let { if (superseding) it.isNotNull else it.isNull },
                    ).orderBy(
                        PACK_REGISTRY.EFFECTIVE_FROM.desc(),
                        PACK_REGISTRY.REGISTERED_AT.desc(),
                        PACK_REGISTRY.ID.desc(),
                    ).limit(1)
                    .fetchOne(PACK_REGISTRY.PACK_VERSION)
            }
        }

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
                    ),
                ).fetchOne()
            return checkNotNull(find(spec.packType, spec.version)) {
                "the superseding row just written is not readable"
            }
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
        const val SUFFIX = "-rollback"
    }
}
