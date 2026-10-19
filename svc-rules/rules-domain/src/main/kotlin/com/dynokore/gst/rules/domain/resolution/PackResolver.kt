package com.dynokore.gst.rules.domain.resolution

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackRegistry
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.tenancykernel.TenantId

/** One pack version an evaluation applied; the caller records it on the document version (INV-REQ-771). */
data class AppliedPack(
    val packType: PackType,
    val packId: PackId,
    val version: PackVersionLabel,
)

/**
 * The pack versions an evaluation is bound to (INV-REQ-1167): resolved once, at the start, by the document's dates.
 * It is immutable and holds no reference to the registry, so a later activation cannot reach an evaluation that
 * already holds it.
 */
data class PackBinding(
    val dates: ResolutionDates,
    val applied: List<AppliedPack>,
) {
    fun appliedFor(packType: PackType): AppliedPack? = applied.firstOrNull { it.packType == packType }
}

sealed interface PackResolution {
    data class Resolved(
        val binding: PackBinding,
    ) : PackResolution

    /** No pack of [packType] is effective on [date]: RUL-002, and no default is applied (INV-REQ-1089, INV-AC-1781). */
    data class NoPackEffective(
        val packType: PackType,
        val date: TransactionDate,
        val code: ErrorCode = ErrorCode.RUL_002,
    ) : PackResolution
}

/**
 * Resolution of the packs a document needs, by its transaction dates, through the rulepack-kernel's registry
 * (INV-REQ-770). It takes dates, never a clock: there is no `Clock` and no `now()` in this path.
 */
class PackResolver(
    private val registry: PackRegistry,
) {
    fun resolve(
        packTypes: List<PackType>,
        tenant: TenantId?,
        dates: ResolutionDates,
    ): PackResolution {
        val applied =
            packTypes.map { packType ->
                val date = dates.forPack(packType)
                val version =
                    registry.resolve(packType, tenant, date.value)
                        ?: return PackResolution.NoPackEffective(packType, date)
                AppliedPack(packType, version.packId, version.version)
            }
        return PackResolution.Resolved(PackBinding(dates, applied))
    }
}
