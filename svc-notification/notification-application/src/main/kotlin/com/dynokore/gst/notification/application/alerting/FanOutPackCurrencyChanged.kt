package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.DEP_UNACTIVATED_PACK
import com.dynokore.platform.tenancykernel.TenantId

/** The registered tenants as the structure replica knows them (INV-REQ-1200). */
sealed interface RegisteredTenantList {
    /** A complete snapshot: only then may the list be used. */
    data class Complete(
        val tenants: Set<TenantId>,
    ) : RegisteredTenantList

    /** The replica holds no complete snapshot yet; the event must be retried. */
    data object Incomplete : RegisteredTenantList
}

interface RegisteredTenantSource {
    fun registered(): RegisteredTenantList
}

/** Runs the block in a transaction of its own, bound to the tenant (forced row-level security). */
interface PerTenantTransaction {
    fun <T> run(
        tenant: TenantId,
        block: () -> T,
    ): T
}

data class FanOutResult(
    val raised: Int = 0,
    val alreadyOpen: Int = 0,
    val resolved: Int = 0,
    val nothingOpen: Int = 0,
    val tenantsVisited: Int = 0,
    val ignored: Boolean = false,
) {
    val applied: Boolean get() = raised + resolved > 0
}

/**
 * Fans the platform-class PackCurrencyChanged out over the registered tenants (INV-REQ-1080, option A):
 * one alert per tenant, each in a transaction of its own with that tenant's own policy. Every step fails closed.
 */
class FanOutPackCurrencyChanged(
    private val tenants: RegisteredTenantSource,
    private val transactions: PerTenantTransaction,
    private val handler: HandlePackCurrencyChanged,
) {
    fun handle(payload: Map<String, Any?>): FanOutResult {
        val event = handler.read(payload)
        if (event.depCode != DEP_UNACTIVATED_PACK) return FanOutResult(ignored = true)
        val list =
            when (val registered = tenants.registered()) {
                is RegisteredTenantList.Complete -> registered.tenants
                RegisteredTenantList.Incomplete -> throw PackAlertNotDelivered("registered tenant list is incomplete")
            }
        var result = FanOutResult()
        val undelivered = mutableListOf<TenantId>()
        for (tenant in list.sortedBy { it.value.toString() }) {
            try {
                val outcome = transactions.run(tenant) { handler.handle(tenant, payload) }
                result = result.plus(outcome)
            } catch (_: PackAlertNotDelivered) {
                undelivered += tenant
                result = result.copy(tenantsVisited = result.tenantsVisited + 1)
            }
        }
        if (undelivered.isNotEmpty()) {
            val ids = undelivered.joinToString(",") { it.value.toString() }
            throw PackAlertNotDelivered("no delivery for tenants $ids")
        }
        return result
    }

    private fun FanOutResult.plus(outcome: HandlePackCurrencyChangedResult): FanOutResult =
        copy(
            tenantsVisited = tenantsVisited + 1,
            raised = raised + if (outcome == HandlePackCurrencyChangedResult.Raised) 1 else 0,
            alreadyOpen = alreadyOpen + if (outcome == HandlePackCurrencyChangedResult.AlreadyOpen) 1 else 0,
            resolved = resolved + if (outcome == HandlePackCurrencyChangedResult.Resolved) 1 else 0,
            nothingOpen = nothingOpen + if (outcome == HandlePackCurrencyChangedResult.NothingOpen) 1 else 0,
        )
}
