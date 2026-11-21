package com.dynokore.gst.submission.application.readiness

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** One pack activation that proceeded without review (INV-REQ-952); shown in the readiness output as a fact. */
data class UnreviewedActivation(
    val packType: String,
    val packVersion: String,
    val effectiveFrom: LocalDate,
)

/** Stored unreviewed activations of a tenant. */
fun interface UnreviewedActivationSource {
    /** The activations of [tenant] up to [periodEnd], ordered by effectiveFrom, packType, packVersion. */
    fun upTo(
        tenant: TenantId,
        periodEnd: LocalDate,
    ): List<UnreviewedActivation>
}

interface UnreviewedActivationStore {
    /** Idempotent: a repeated activation of the tenant stores no second row. Returns true when a row was added. */
    fun record(
        tenant: TenantId,
        activation: UnreviewedActivation,
    ): Boolean
}

/** The decoded members of the arming event. */
data class UnreviewedActivationData(
    val tenant: TenantId,
    val activation: UnreviewedActivation,
)

/** Reads the event data JSON (the JSON library stays in the adapters, ARC-03). */
fun interface UnreviewedActivationDataReader {
    /** @throws ContractViolation when a member is missing or malformed, or `reviewed` is not false. */
    fun read(dataJson: String): UnreviewedActivationData
}

/** The end date of the stored period record of an entity; null when the period is not stored. */
fun interface PeriodEndReader {
    fun periodEnd(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): LocalDate?
}

/** Looks the unreviewed activations of the current tenant up for a period (INV-AC-26295). */
class UnreviewedActivationLookup(
    private val tenants: TenantContext,
    private val periods: PeriodEndReader,
    private val source: UnreviewedActivationSource,
) {
    fun forPeriod(
        entityRef: String,
        periodRef: String,
    ): List<UnreviewedActivation> {
        val tenant = tenants.current()
        val periodEnd = periods.periodEnd(tenant, entityRef, periodRef) ?: return emptyList()
        return source
            .upTo(tenant, periodEnd)
            .filter { !it.effectiveFrom.isAfter(periodEnd) }
            .sortedWith(compareBy({ it.effectiveFrom }, { it.packType }, { it.packVersion }))
    }
}
