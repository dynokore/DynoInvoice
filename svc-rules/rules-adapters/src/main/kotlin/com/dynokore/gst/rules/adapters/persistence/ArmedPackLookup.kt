package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_REGISTRY
import com.dynokore.gst.rules.domain.resolution.PackLookup
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate

/**
 * `resolve(pack_type, entity, resolution_date)` over rules-svc's registry (INV-REQ-770 behaviour 1): the ARMED versions
 * of the type, for the tenant or the platform, whose `effective_from <= date < effective_to` (open-ended when null),
 * the tenant's pack before the platform's, and the later `effective_from` first within a tier, so a superseding
 * version wins from its own date while the superseded one stays resolvable for earlier dates (INV-REQ-1089).
 *
 * The date is the caller's transaction date: this class has no clock. It reads the open transaction's DSLContext of
 * the command (tenancy-jooq binds the tenant; row-level security does the rest), jOOQ only (INV-T-4201).
 */
class ArmedPackLookup(
    private val dsl: DSLContext,
) : PackLookup {
    override fun resolve(
        packType: PackType,
        tenant: TenantId?,
        date: LocalDate,
    ): PackVersion? {
        val owners = listOfNotNull(tenant?.value, TenantId.PLATFORM.value)
        val row =
            dsl
                .selectFrom(PACK_REGISTRY)
                .where(PACK_REGISTRY.PACK_TYPE.eq(packType.value))
                .and(PACK_REGISTRY.STATUS.eq(ARMED))
                .and(PACK_REGISTRY.TENANT_ID.`in`(owners))
                .and(PACK_REGISTRY.EFFECTIVE_FROM.le(date))
                .and(PACK_REGISTRY.EFFECTIVE_TO.isNull.or(PACK_REGISTRY.EFFECTIVE_TO.gt(date)))
                .orderBy(
                    DSL.`when`(PACK_REGISTRY.TENANT_ID.eq(TenantId.PLATFORM.value), 1).otherwise(0),
                    PACK_REGISTRY.EFFECTIVE_FROM.desc(),
                ).limit(1)
                .fetchOne() ?: return null
        val owner = row.tenantId!!
        return PackVersion(
            PackManifest(
                packId = PackId(row.packId!!),
                packType = packType,
                version = PackVersionLabel(row.packVersion!!),
                effectiveFrom = row.effectiveFrom!!,
                effectiveTo = row.effectiveTo,
                minEngineVersion = row.minEngineVersion!!,
            ),
            tenant = if (owner == TenantId.PLATFORM.value) null else TenantId(owner),
        )
    }

    private companion object {
        const val ARMED = "ARMED"
    }
}
