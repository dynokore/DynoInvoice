package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.DRY_RUN_DELTA
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_REGISTRY
import com.dynokore.gst.rules.application.RegulatoryPackRecord
import com.dynokore.gst.rules.application.RegulatoryPackStore
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * The regulatory pack registry as the approval use case sees it (INV-REQ-489): jOOQ only. A platform pack is read and
 * written under the platform binding, as the platform write path does; the approval is written by
 * `rules.approve_platform_pack` only, which records the bound actor (the approving user ID, never a role) and moves
 * STAGED to APPROVED and nothing else. It is called outside any open transaction: each call opens its own.
 */
class PersistedRegulatoryPackStore(
    private val dsl: DSLContext,
    private val platform: PlatformTenantBinding,
    private val transactions: TransactionRunner,
) : RegulatoryPackStore {
    override fun find(
        packType: String,
        version: String,
    ): RegulatoryPackRecord? = platform.bindPlatform(READER) { transactions.inTransaction { read(packType, version) } }

    private fun read(
        packType: String,
        version: String,
    ): RegulatoryPackRecord? {
        val row =
            dsl
                .select(PACK_REGISTRY.STATUS, PACK_REGISTRY.REGISTERED_BY)
                .from(PACK_REGISTRY)
                .where(PACK_REGISTRY.PACK_TYPE.eq(packType))
                .and(PACK_REGISTRY.PACK_VERSION.eq(version))
                .and(PACK_REGISTRY.TENANT_ID.eq(TenantId.PLATFORM.value))
                .and(PACK_REGISTRY.LAYER.eq(REGULATORY))
                .fetchOne() ?: return null
        // The arming rule is stated per pack, not per tenant: a COMPLETE run of this type and version is enough.
        val delta =
            dsl.fetchExists(
                DSL
                    .selectOne()
                    .from(DRY_RUN_DELTA)
                    .where(DRY_RUN_DELTA.PACK_TYPE.eq(packType))
                    .and(DRY_RUN_DELTA.PACK_VERSION.eq(version))
                    .and(DRY_RUN_DELTA.STATE.eq(COMPLETE)),
            )
        return RegulatoryPackRecord(
            status = PackStatus.valueOf(checkNotNull(row[PACK_REGISTRY.STATUS]) { "pack_registry.status is NOT NULL" }),
            stagedByUserId = row[PACK_REGISTRY.REGISTERED_BY]?.takeIf { it.isNotBlank() },
            dryRunDeltaExists = delta,
        )
    }

    /** The binding carries [approvedBy] as the actor, and the function records the bound actor. */
    override fun markApproved(
        packType: String,
        version: String,
        approvedBy: String,
    ) = platform.bindPlatform(approvedBy) { transactions.inTransaction { approve(packType, version) } }

    private fun approve(
        packType: String,
        version: String,
    ) {
        dsl
            .select(
                DSL.function(
                    DSL.name("rules", "approve_platform_pack"),
                    SQLDataType.OTHER,
                    DSL.`val`(packType, SQLDataType.CLOB),
                    DSL.`val`(version, SQLDataType.CLOB),
                ),
            ).execute()
    }

    private companion object {
        /** The workload principal a read of the approval decision runs as (ARC-21). */
        const val READER = "svc:rules-svc/op:approve-regulatory-pack"
        const val REGULATORY = "REGULATORY"
        const val COMPLETE = "COMPLETE"
    }
}
