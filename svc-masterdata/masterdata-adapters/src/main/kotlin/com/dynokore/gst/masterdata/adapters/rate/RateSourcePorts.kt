package com.dynokore.gst.masterdata.adapters.rate

import com.dynokore.gst.masterdata.application.rate.ApprovedRateSources
import com.dynokore.gst.masterdata.application.rate.ElectedRateSource
import com.dynokore.platform.configresolver.ElectionRequired
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate

/**
 * [ElectedRateSource] over the policy replica (`gst.platform.policy-changed.v1`): the exchange-rate-source election
 * (VP-24) of the entity as `PolicyReader.resolve` gives it on the date. Fails closed: no election, an option other than
 * the one the election writes, or a value without a source gives no source.
 */
class PolicyElectedRateSource(
    private val policy: PolicyReader,
    private val tenants: TenantContext,
    private val tx: TransactionRunner,
    private val mapper: ObjectMapper,
) : ElectedRateSource {
    override fun elected(
        entity: String,
        date: LocalDate,
    ): String? = election(entity, date)?.first

    /** The elected (source, basis) of [entity] on [date], or null. */
    fun election(
        entity: String,
        date: LocalDate,
    ): Pair<String, String>? {
        val option =
            try {
                tx.inTransaction {
                    policy
                        .resolve(
                            ELECTION,
                            PolicyScope.entity(tenants.current(), entity),
                            date,
                        ).selected
                        .singleOrNull()
                }
            } catch (_: ElectionRequired) {
                null
            } ?: return null
        if (option.key != APPROVED_SOURCE) return null
        val value = option.valueJson?.let { mapper.readTree(it) } ?: return null
        val source = value["source"]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotEmpty() }
        val basis = value["basis"]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotEmpty() }
        return if (source != null && basis != null) source to basis else null
    }

    companion object {
        /** VP-24: the exchange rate source an entity elects (FR-AG.11), entity scope. */
        val ELECTION = VariationPointKey("VP-24")

        /** The option key tenancy-svc writes for an election. */
        const val APPROVED_SOURCE = "APPROVED_SOURCE"
    }
}

/**
 * [ApprovedRateSources] over the scope pack replica: approved exactly when a scope pack version active on the date lists
 * the source (equality) with the basis. No active pack gives no approval.
 */
class ReplicaApprovedRateSources(
    private val packs: ScopePackStore,
    private val tx: TransactionRunner,
) : ApprovedRateSources {
    override fun isApproved(
        source: String,
        basis: String,
        date: LocalDate,
    ): Boolean =
        tx.inTransaction { packs.approvedOn(date) }.orEmpty().any { it.sourceId == source && basis in it.bases }
}
